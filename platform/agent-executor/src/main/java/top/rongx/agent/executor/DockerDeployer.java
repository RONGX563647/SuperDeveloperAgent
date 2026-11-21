package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeploySlot;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RecipeException;
import top.rongx.agent.domain.port.Deployer;

/**
 * Docker 部署器：本机容器替换式发布。
 *
 * <pre>
 * docker rm -f agent-deploy-&lt;project&gt;      ← 停旧（不存在则忽略）
 * docker run -d --name … --restart unless-stopped \
 *   -e KEY=VALUE … -p hostPort:containerPort &lt;image&gt;
 * </pre>
 *
 * <p>环境变量来自平台「设置 → 项目环境」，按 KEY 排序注入；失败摘要会先把变量值抹掉，
 * 避免 docker 回显命令行时把凭据写进构建日志。
 *
 * <p>回滚 = 用上一版镜像再跑一遍同一套参数（因此镜像必须保留；
 * 清理策略由 {@code docker image prune} 之外的人工/定时任务负责）。
 *
 * <p>test 环境支持多服务部署：{@link #deployService} 为每个服务起一个容器
 * （{@code <namespace>-<project>-<service>}），共用环境网络并用网络别名
 * {@code <project>-<service>} 互相访问，数据卷按服务独立。
 */
public class DockerDeployer implements Deployer {

    private static final long DOCKER_TIMEOUT_MS = 120_000;

    /** 同名容器串行部署：并发发布会互相删掉对方刚起的容器。 */
    private static final ConcurrentHashMap<String, ReentrantLock> RUN_LOCKS =
            new ConcurrentHashMap<>();

    private final ProcessExecutor executor = new ProcessExecutor();
    private final DockerCli docker;
    /** 镜像准入策略（与构建沙箱共用同一份判定）；null 视为不限制。 */
    private SandboxImagePolicy imagePolicy = SandboxImagePolicy.permissive();

    public DockerDeployer() {
        this(DockerCli.defaults());
    }

    public DockerDeployer(DockerCli docker) {
        this.docker = docker == null ? DockerCli.defaults() : docker;
    }

    /** 注入镜像准入策略（由 PipelineConfig 从 DockerSandbox 取同一份，保证判定一致）。 */
    public DockerDeployer setImagePolicy(SandboxImagePolicy policy) {
        this.imagePolicy = policy == null ? SandboxImagePolicy.permissive() : policy;
        return this;
    }

    @Override
    public boolean supports(DeployTarget target) {
        return target == DeployTarget.DOCKER;
    }

    @Override
    public DeployResult deploy(DeployRequest request) {
        StringBuilder log = new StringBuilder();
        if (request.imageRef() == null || request.imageRef().isBlank()) {
            return DeployResult.failed(DeployTarget.DOCKER, ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                    "DOCKER 目标缺少镜像：流水线需要包含 image_package 且构建成功"
                            + "（仓库缺少 Dockerfile 时镜像阶段会被裁掉）", "");
        }
        Path dir = Path.of(request.workspace() == null || request.workspace().isBlank()
                ? "." : request.workspace());
        String name = containerName(request.projectId(), request.envName(), DeploySlot.of(request));
        List<String> labels = new ArrayList<>(List.of(
                "--label", "agent.project=" + request.projectId(),
                "--label", "agent.env=" + (request.envName() == null ? "" : request.envName()),
                "--label", "agent.run=" + request.runId(),
                "--label", "agent.version=" + displayVersion(request),
                "--label", "agent.sha=" + request.gitSha()));
        // 单容器部署的持久化卷：不挂卷时数据库/上传文件都在镜像层，重发即清空。
        labels.addAll(volumeArgs(request));
        return run(dir, name, request.imageRef(), request.hostPort(), request.containerPort(),
                request.env(), request.network(), request.healthPath(), labels, log, "127.0.0.1");
    }

    /**
     * 多服务部署：启动一个服务容器。
     *
     * @param namespace  环境命名空间（容器名 / 卷名 / 标签前缀，dev 与 test 完全隔离）
     * @param service    服务名（backend / frontend）
     * @param volumePath 持久化挂载点（空 = 不挂卷）
     */
    public DeployResult deployService(Path dir, String projectId, String env, String runId,
                                      String sha, String namespace, String service, String image,
                                      int hostPort, int containerPort, String network,
                                      Map<String, String> envVars, String volumePath,
                                      String version, boolean tagged) {
        StringBuilder log = new StringBuilder();
        if (image == null || image.isBlank()) {
            return DeployResult.failed(DeployTarget.DOCKER, ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                    "服务 " + service + " 缺少镜像：请在 deploy.services 里声明 image", "");
        }
        // 打 tag 的版本独立命名空间后缀：容器名与网络别名都带版本，旧版本继续运行
        String slot = DeploySlot.of(tagged, version);
        String name = serviceContainerName(namespace, projectId, service)
                + (slot == null ? "" : "-" + slot);
        String alias = serviceAlias(projectId, service) + (slot == null ? "" : "-" + slot);
        List<String> extra = new ArrayList<>(List.of(
                "--label", "agent.namespace=" + namespace,
                "--label", "agent.env=" + env,
                "--label", "agent.service=" + service,
                "--label", "agent.version=" + (version == null || version.isBlank()
                        ? "latest" : version.trim()),
                "--network-alias", alias));
        if (volumePath != null && !volumePath.isBlank()) {
            extra.add("-v");
            extra.add(volumeName(namespace, projectId, service) + ":" + volumePath);
        }
        // 前端容器的反向代理目标：<project>-backend[-<版本>]:<port>（走环境网络别名，不依赖宿主机端口）。
        if ("frontend".equals(service)) {
            String backendPort = envVars.getOrDefault("BACKEND_PORT", "8080");
            extra.add("-e");
            extra.add("BACKEND_UPSTREAM=http://" + serviceAlias(projectId, "backend")
                    + (slot == null ? "" : "-" + slot) + ":" + backendPort);
        }
        return run(dir, name, image, hostPort, containerPort, envVars, network, "/", extra, log,
                "127.0.0.1");
    }

    /**
     * 确保环境网络存在（dev / test 各自独立：agent-dev-net / agent-test-net）。
     *
     * @return true = 已存在或创建成功
     */
    public boolean ensureNetwork(Path dir, String network, StringBuilder log) {
        return DockerNetwork.ensure(executor, docker, dir, network, log);
    }

    /**
     * 清理开发环境遗留的 Docker 应用容器（dev 固定本机运行，不应再有容器占端口）。
     *
     * <p>只删平台命名的容器；容器不存在视为成功。
     *
     * @return 实际删除的容器名（空 = 本来就没有）
     */
    public List<String> stopDevContainers(Path dir, String projectId, String env, StringBuilder log) {
        BooleanSupplier never = () -> false;
        String suffix = env == null || env.isBlank() ? "dev" : env.trim();
        List<String> names = List.of(containerName(projectId, suffix), containerName(projectId));
        List<String> removed = new ArrayList<>();
        for (String name : names) {
            StringBuilder rmLog = new StringBuilder();
            ProcessExecutor.StepOutcome rm =
                    executor.exec(dir, docker.cmd("rm", "-f", name), 30_000, rmLog, never);
            if (rm.exitCode() == 0) {
                removed.add(name);
                log.append("[dev] 已清理遗留 dev 容器 ").append(name).append('\n');
            } else if (!rmLog.toString().contains("No such container")) {
                log.append(rmLog);
            }
        }
        return removed;
    }

    /**
     * 本机是否已有指定镜像。
     *
     * <p>回滚前用它区分「上一版镜像已被清理」和「本次发布失败」：前者跳过回滚即可，
     * 不应额外制造一条 E-501。
     */
    public boolean imageExists(Path dir, String image) {
        if (image == null || image.isBlank()) {
            return false;
        }
        // 走 DockerOps：与引擎预检、中间件预检共用同一份"存在性"判据与超时
        return new DockerOps(docker.binary(), dir).imageExists(image);
    }

    private DeployResult run(Path dir, String name, String image, int hostPort, int containerPort,
                             Map<String, String> env, String network, String healthPath,
                             List<String> extraArgs, StringBuilder log, String endpointHost) {
        ReentrantLock lock = RUN_LOCKS.computeIfAbsent(name, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            return DeployResult.failed(DeployTarget.DOCKER, ErrorCode.DEPLOY_START_FAILED.code(),
                    "容器 " + name + " 已有部署正在进行：请等待当前部署结束后重试", "");
        }
        try {
            return runOnce(dir, name, image, hostPort, containerPort, env, network, healthPath,
                    extraArgs, log, endpointHost);
        } finally {
            lock.unlock();
        }
    }

    private DeployResult runOnce(Path dir, String name, String image, int hostPort,
                                 int containerPort, Map<String, String> env, String network,
                                 String healthPath, List<String> extraArgs, StringBuilder log,
                                 String endpointHost) {
        BooleanSupplier never = () -> false;

        // 0. 镜像名是 docker run 的位置参数，以 - 开头会被当成选项（参数注入）：
        //    镜像 tag 来自流水线 image_package 的产物命名，属于外部可控输入，必须校验。
        String imageRef;
        try {
            imageRef = ImageRef.require(image, "deploy.image");
            imagePolicy.require(imageRef, SandboxImagePolicy.Source.DEPLOY);
        } catch (RecipeException e) {
            return DeployResult.failed(DeployTarget.DOCKER, e.code(),
                    e.getMessage(), ProcessExecutor.redact(log.toString()));
        } catch (top.rongx.agent.domain.error.SandboxDeniedException e) {
            // 镜像不在允许列表 / 浮动 tag：这是策略拒绝（E-707），不是启动失败
            return DeployResult.failed(DeployTarget.DOCKER, e.code(),
                    e.getMessage(), ProcessExecutor.redact(log.toString()));
        }
        image = imageRef;

        // 1. 镜像必须在本地：docker run 找不到镜像时会先去 registry 拉，
        //    被镜像源白名单拦下后只留下 "exit 125 … not in the allowlist"，
        //    把"本机没这个镜像"伪装成容器启动失败。回滚场景更糟：下面会先删掉在跑的容器。
        StringBuilder inspectLog = new StringBuilder();
        ProcessExecutor.StepOutcome inspect = executor.exec(dir,
                docker.cmd("image", "inspect", image), 15_000, inspectLog, never);
        if (inspect.exitCode() != 0) {
            return DeployResult.failed(DeployTarget.DOCKER,
                    ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                    "镜像 " + image + " 不在本机（可能已被清理）：无法启动容器；"
                            + "请重新执行 image_package 生成该版本；回滚需确认上一版镜像仍保留",
                    ProcessExecutor.redact(inspectLog.toString()));
        }

        // 1. 停旧（幂等：不存在时 docker 返回非 0，这里不视为失败）。
        //    daemon 的 "No such container" 原文绝不进日志：它排在最前面，
        //    曾把真实失败（应用配置校验不过、容器自行退出）掩盖成"部署报错"。
        StringBuilder rmLog = new StringBuilder();
        ProcessExecutor.StepOutcome rm =
                executor.exec(dir, docker.cmd("rm", "-f", name), 30_000, rmLog, never);
        if (rm.exitCode() != 0) {
            String rmText = rmLog.toString();
            if (rmText.contains("No such container")) {
                log.append("[deploy] 无旧容器 ").append(name).append("（首次部署，跳过停旧）\n");
            } else {
                log.append(rmText);
            }
        }

        // 2. 起新（环境变量按 key 排序，保证同样输入产生同样的 docker 参数）
        //
        // 默认加固：应用容器不需要额外 Linux capabilities（nginx 绑 80 除外，见下），
        // 也不需要 setuid 提权。这两项对正常应用无感，但能挡住"容器内提权后横向移动"。
        // 不设 --read-only：应用要写临时文件、上传目录、日志，只读会直接打断业务。
        List<String> argv = new ArrayList<>(docker.cmd(
                "run", "-d",
                "--name", name,
                "--restart", "unless-stopped",
                "--security-opt", "no-new-privileges",
                "--label", "com.docker.compose.project=" + composeProject(network, null),
                "--label", "com.docker.compose.service=" + name,
                // hostPort<=0 表示"让 docker 自己挑一个"（端口分配的真相交给 docker）：
                //   -p 127.0.0.1::8080
                // 原实现用 ServerSocket 试绑再释放，窗口期（释放→docker 真正绑定）会被抢占，
                // 而且要在 200 个候选端口里循环试。docker 分配没有窗口，事后用 docker port 回读。
                "-p", hostPort > 0
                        ? "127.0.0.1:" + hostPort + ":" + containerPort
                        : "127.0.0.1::" + containerPort));
        if (containerPort <= 1024) {
            // 绑 <1024 需要 CAP_NET_BIND_SERVICE（镜像以非 root 运行时丢了就起不来）；
            // 只补回这一项，不给 NET_RAW / SETUID 等其它能力
            argv.add("--cap-drop");
            argv.add("ALL");
            argv.add("--cap-add");
            argv.add("NET_BIND_SERVICE");
        } else {
            argv.add("--cap-drop");
            argv.add("ALL");
        }
        argv.addAll(extraArgs);
        if (network != null && !network.isBlank()) {
            argv.add("--network");
            argv.add(network.trim());
        }
        for (Map.Entry<String, String> e : new TreeMap<>(env == null ? Map.<String, String>of()
                : env).entrySet()) {
            argv.add("-e");
            argv.add(e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue()));
        }
        argv.add(image);
        ProcessExecutor.StepOutcome run = executor.exec(dir, argv, DOCKER_TIMEOUT_MS, log, never);

        if (run.exitCode() != 0) {
            String summary = sanitize(run.summary(), env);
            if (summary.contains("port is already allocated")
                    || summary.contains("address already in use")) {
                // hostPort<=0 时是 docker 自己挑端口，理论上不该冲突；
                // 真冲突说明是容器端口（镜像里已经监听的那一个）被占，报错要指向容器端口。
                String target = hostPort > 0
                        ? "宿主端口 " + hostPort
                        : "容器端口 " + containerPort + "（由 docker 分配宿主端口）";
                return DeployResult.failed(DeployTarget.DOCKER, ErrorCode.DEPLOY_START_FAILED.code(),
                        target + " 已被其它进程/容器占用：" + summary,
                        ProcessExecutor.redact(log.toString()));
            }
            return DeployResult.failed(DeployTarget.DOCKER, ErrorCode.DEPLOY_START_FAILED.code(),
                    "容器启动失败：" + summary,
                    ProcessExecutor.redact(log.toString()));
        }
        return DeployResult.ok(DeployTarget.DOCKER,
                // 部署地址 = 用户点「打开部署地址」要跳转的页面入口（根路径 → 前端首页），
                // 不是 healthPath：healthPath（/actuator/health）只用于探活判定，
                // 返回的是一段 JSON，拿它当部署地址会让用户以为部署错了地方。
                // 端口必须用"容器实际映射的端口"：hostPort<=0 时具体值只有 docker 知道。
                "http://" + endpointHost + ":"
                        + resolvePublishedPort(name, containerPort, hostPort, dir, log) + "/",
                name, ProcessExecutor.redact(log.toString()));
    }

    /**
     * 容器实际映射的宿主端口；docker 分配失败时回落请求端口（保证地址仍可拼出来）。
     *
     * <p>只问 docker，不再用 {@code ServerSocket} 试探：试绑-释放-再绑之间必然有窗口，
     * 而且既然端口是 docker 分的，它的答案才是唯一权威。
     */
    private int resolvePublishedPort(String container, int containerPort, int requested, Path dir,
                                     StringBuilder log) {
        int actual = new DockerOps(docker.binary(), dir).publishedPort(container, containerPort);
        if (actual > 0) {
            if (actual != requested) {
                log.append("[port] docker 分配宿主端口 ").append(actual)
                        .append("（请求 ").append(requested).append("）\n");
            }
            return actual;
        }
        log.append("[port] 未能回读容器端口映射，沿用请求端口 ").append(requested).append('\n');
        return requested;
    }

    /** 失败摘要里抹掉环境变量值（docker 报错可能回显完整命令行）。 */
    static String sanitize(String text, Map<String, String> env) {
        if (text == null || text.isEmpty() || env == null || env.isEmpty()) {
            return ProcessExecutor.redact(text);
        }
        String out = text;
        for (Map.Entry<String, String> e : env.entrySet()) {
            String value = e.getValue();
            if (value == null || value.isEmpty()) {
                continue;
            }
            out = out.replace(e.getKey() + "=" + value, e.getKey() + "=***");
            if (value.length() >= 6) {
                out = out.replace(value, "***");
            }
        }
        return ProcessExecutor.redact(out);
    }

    /**
     * 同一环境的容器归到一个"组"：Docker Desktop 按 {@code com.docker.compose.project} 标签分组，
     * 平台不用 compose，但打上同名标签即可让「应用 + 中间件」在一个组里统一查看/启停。
     *
     * @param network 环境网络（{@code agent-dev-net} → 组 {@code agent-dev}）
     * @param envName 环境名（network 为空时的兜底）
     */
    public static String composeProject(String network, String envName) {
        if (network != null && network.endsWith("-net")
                && network.length() > "-net".length()) {
            return network.substring(0, network.length() - "-net".length());
        }
        return envName == null || envName.isBlank() ? "agent" : "agent-" + envName.trim();
    }

    /** 平台托管的容器名（旧命名，无环境后缀）：仅用于清理升级前遗留的容器。 */
    public static String containerName(String projectId) {
        return "agent-deploy-" + normalize(projectId, "unknown");
    }

    /**
     * 平台托管的单容器名：一个项目 + 一个环境一个槽位（替换式发布），不受 runId 影响。
     *
     * <p>必须带环境后缀：否则 test 发布会把 dev 的容器删掉（同一项目多环境无法共存）。
     * env 为空时退化为旧命名，仅用于单测与过渡。
     */
    public static String containerName(String projectId, String env) {
        String base = containerName(projectId);
        return env == null || env.isBlank() ? base : base + "-" + normalize(env, "env");
    }

    /**
     * 按版本槽的容器名：{@code slot} 为空 = 默认槽（覆盖式发布）；
     * 非空 = 独立槽（旧版本容器保留），后缀即用户填写的版本号。
     */
    public static String containerName(String projectId, String env, String slot) {
        String base = containerName(projectId, env);
        return slot == null || slot.isBlank() ? base : base + "-" + normalize(slot, "v");
    }

    /** 单容器持久化卷参数：卷名按环境隔离（agent-test-<project>-app-data），版本槽共用同一份数据。 */
    static List<String> volumeArgs(DeployRequest request) {
        if (request.volumePath() == null || request.volumePath().isBlank()) {
            return List.of();
        }
        String volume = volumeName(composeProject(request.network(), request.envName()),
                request.projectId(), "app");
        return List.of("-v", volume + ":" + request.volumePath().trim());
    }

    /** 展示版本：用户没填版本号时统一显示 latest。 */
    private static String displayVersion(DeployRequest request) {
        return request.version() == null || request.version().isBlank()
                ? "latest" : request.version().trim();
    }

    /** 多服务容器名：命名空间前缀保证 dev / test / prod 各自独立。 */
    public static String serviceContainerName(String namespace, String projectId, String service) {
        return normalize(namespace, "agent-test") + "-" + normalize(projectId, "unknown")
                + "-" + normalize(service, "svc");
    }

    /** 网络别名（容器内互相访问的地址）：{@code <project>-<service>}。 */
    public static String serviceAlias(String projectId, String service) {
        return normalize(projectId, "unknown") + "-" + normalize(service, "svc");
    }

    /** 数据卷名：按命名空间 + 项目 + 服务独立，互不覆盖。 */
    public static String volumeName(String namespace, String projectId, String service) {
        return serviceContainerName(namespace, projectId, service) + "-data";
    }

    private static String normalize(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "-");
        return value.isEmpty() ? fallback : value;
    }
}
