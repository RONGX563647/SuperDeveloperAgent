package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.deploy.ReasonCode;
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

    private final ProcessExecutor executor = new ProcessExecutor();

    @Override
    public boolean supports(DeployTarget target) {
        return target == DeployTarget.DOCKER;
    }

    @Override
    public DeployResult deploy(DeployRequest request) {
        StringBuilder log = new StringBuilder();
        boolean rollback = request.rollback();
        String image = rollback ? request.previousImageRef() : request.imageRef();
        if (image == null || image.isBlank()) {
            if (rollback) {
                return DeployResult.failed(DeployTarget.DOCKER, "E-507",
                        "没有可回滚的上一版镜像（previousImageRef 为空）", "");
            }
            return DeployResult.failed(DeployTarget.DOCKER, "E-501",
                    "DOCKER 目标需要镜像：请先执行 image_package，或检查 Dockerfile", "");
        }
        Path dir = Path.of(request.workspace() == null || request.workspace().isBlank()
                ? "." : request.workspace());
        String name = containerName(request.projectId(), request.envName());
        List<String> labels = List.of(
                "--label", "agent.project=" + request.projectId(),
                "--label", "agent.run=" + request.runId(),
                "--label", "agent.sha=" + request.gitSha());
        if (rollback) {
            log.append("[deploy] 回滚到上一版镜像 ").append(image).append('\n');
        }
        DeployResult first = run(dir, name, image, request.hostPort(), request.containerPort(),
                request.env(), request.network(), request.healthPath(), labels, log, "127.0.0.1");

        // 非回滚失败且存在上一版镜像：自动回滚，保证"失败不会把环境留在半死状态"。
        if (!first.success() && !rollback
                && request.previousImageRef() != null && !request.previousImageRef().isBlank()) {
            log.append("[deploy] 新版本启动失败（").append(first.reasonCode().wireName())
                    .append("），尝试自动回滚到 ").append(request.previousImageRef()).append('\n');
            DeployResult restored = run(dir, name, request.previousImageRef(), request.hostPort(),
                    request.containerPort(), request.env(), request.network(), request.healthPath(),
                    labels, log, "127.0.0.1");
            if (restored.success()) {
                return new DeployResult(false, DeployTarget.DOCKER, restored.endpoint(),
                        restored.resource(), first.errorCode(), first.summary(),
                        ProcessExecutor.redact(log.toString()), ReasonCode.ROLLED_BACK,
                        "根因：" + first.reasonCode().label() + "；" + first.reasonCode().suggestion()
                                + "（运行态已回到上一版）", true);
            }
            log.append("[deploy] 自动回滚也失败：").append(restored.summary()).append('\n');
        }
        return first;
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
                                      Map<String, String> envVars, String volumePath) {
        StringBuilder log = new StringBuilder();
        if (image == null || image.isBlank()) {
            return DeployResult.failed(DeployTarget.DOCKER, "E-501",
                    "服务 " + service + " 缺少镜像：请在 deploy.services 里声明 image", "");
        }
        String name = serviceContainerName(namespace, projectId, service);
        String alias = serviceAlias(projectId, service);
        List<String> extra = new ArrayList<>(List.of(
                "--label", "agent.namespace=" + namespace,
                "--label", "agent.env=" + env,
                "--label", "agent.service=" + service,
                "--network-alias", alias));
        if (volumePath != null && !volumePath.isBlank()) {
            extra.add("-v");
            extra.add(volumeName(namespace, projectId, service) + ":" + volumePath);
        }
        // 前端容器的反向代理目标：<project>-backend:<port>（走环境网络别名，不依赖宿主机端口）。
        if ("frontend".equals(service)) {
            String backendPort = envVars.getOrDefault("BACKEND_PORT", "8080");
            extra.add("-e");
            extra.add("BACKEND_UPSTREAM=http://" + serviceAlias(projectId, "backend")
                    + ":" + backendPort);
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
        BooleanSupplier never = () -> false;
        ProcessExecutor.StepOutcome inspect =
                executor.exec(dir, List.of("docker", "network", "inspect", network), 15_000,
                        log, never);
        if (inspect.exitCode() == 0) {
            return true;
        }
        ProcessExecutor.StepOutcome created =
                executor.exec(dir, List.of("docker", "network", "create", network), 30_000,
                        log, never);
        if (created.exitCode() == 0) {
            log.append("[deploy] 已创建环境网络 ").append(network).append('\n');
            return true;
        }
        return false;
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
                    executor.exec(dir, List.of("docker", "rm", "-f", name), 30_000, rmLog, never);
            if (rm.exitCode() == 0) {
                removed.add(name);
                log.append("[dev] 已清理遗留 dev 容器 ").append(name).append('\n');
            } else if (!rmLog.toString().contains("No such container")) {
                log.append(rmLog);
            }
        }
        return removed;
    }

    private DeployResult run(Path dir, String name, String image, int hostPort, int containerPort,
                             Map<String, String> env, String network, String healthPath,
                             List<String> extraArgs, StringBuilder log, String endpointHost) {
        BooleanSupplier never = () -> false;

        // 1. 停旧（幂等：不存在时 docker 返回非 0，这里不视为失败）。
        //    daemon 的 "No such container" 原文绝不进日志：它排在最前面，
        //    曾把真实失败（应用配置校验不过、容器自行退出）掩盖成"部署报错"。
        StringBuilder rmLog = new StringBuilder();
        ProcessExecutor.StepOutcome rm =
                executor.exec(dir, List.of("docker", "rm", "-f", name), 30_000, rmLog, never);
        if (rm.exitCode() != 0) {
            String rmText = rmLog.toString();
            if (rmText.contains("No such container")) {
                log.append("[deploy] 无旧容器 ").append(name).append("（首次部署，跳过停旧）\n");
            } else {
                log.append(rmText);
            }
        }

        // 2. 起新（环境变量按 key 排序，保证同样输入产生同样的 docker 参数）
        List<String> argv = new ArrayList<>(List.of(
                "docker", "run", "-d",
                "--name", name,
                "--restart", "unless-stopped",
                "--label", "com.docker.compose.project=" + composeProject(network, null),
                "--label", "com.docker.compose.service=" + name,
                "-p", hostPort + ":" + containerPort));
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
            return DeployResult.failed(DeployTarget.DOCKER, "E-502",
                    "容器启动失败：" + sanitize(run.summary(), env),
                    ProcessExecutor.redact(log.toString()));
        }
        return DeployResult.ok(DeployTarget.DOCKER,
                "http://" + endpointHost + ":" + hostPort + safePath(healthPath),
                name, ProcessExecutor.redact(log.toString()));
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

    private static String safePath(String healthPath) {
        if (healthPath == null || healthPath.isBlank()) {
            return "/";
        }
        return healthPath.startsWith("/") ? healthPath : "/" + healthPath;
    }
}
