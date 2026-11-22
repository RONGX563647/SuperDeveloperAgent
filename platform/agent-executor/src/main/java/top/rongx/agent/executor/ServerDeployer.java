package top.rongx.agent.executor;

import top.rongx.agent.domain.hash.Hashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;
import top.rongx.agent.domain.settings.SshAccess;

/**
 * 远程服务器部署器（SERVER）：镜像经 ssh/sftp 推到远端 docker 运行。
 *
 * <p>配置来源：设置页维护的 {@code deploy_servers}（替代 application.yml）。
 * 选择顺序：运行参数 {@code serverId} → 默认服务器 → E-505（提示去设置页添加）。
 *
 * <p>生产环境（远程 Linux）与测试环境（本机）的差异主要在 SSH 与远端工具：
 * <ul>
 *   <li>docker 可能需要 sudo（当前用户不在 docker 组）；</li>
 *   <li>{@code /opt/agent/deploy} 首次不存在或不可写，需要逐级创建并回退到 $HOME；</li>
 *   <li>精简系统没有 {@code ss}/{@code netstat}，端口探测不能依赖它们；</li>
 *   <li>sshd 可能关闭 sftp 子系统，上传需要 exec 兜底；</li>
 *   <li>{@code docker load} 大镜像可能超过 90s，需要单独预算。</li>
 * </ul>
 * 下面每一处都在失败时给出可操作的提示，而不是把远端报错原样抛给用户。
 */
public class ServerDeployer implements Deployer {

    private static final long SAVE_TIMEOUT_MS = 180_000;
    /** 跨公网/专线传镜像通常比本机慢一个数量级，给足 10 分钟，避免大包被误判超时。 */
    private static final long TRANSFER_TIMEOUT_MS = 600_000;
    private static final long LOAD_TIMEOUT_MS = 600_000;
    private static final long SSH_TIMEOUT_MS = 90_000;
    private static final long PROBE_TIMEOUT_MS = 15_000;

    /** 端口从期望值连续尝试的上限（内存计算，不再为每个端口单独建 SSH 连接）。 */
    private static final int PORT_SCAN_LIMIT = 200;
    /** docker run 因端口冲突失败后，继续换端口尝试的次数。 */
    private static final int PORT_RETRY_LIMIT = 10;

    /** 同一环境（服务器+项目+环境）串行部署：并发发布会互相删掉对方刚起的容器。 */
    private static final ConcurrentHashMap<String, ReentrantLock> DEPLOY_LOCKS =
            new ConcurrentHashMap<>();

    private final ProcessExecutor executor = new ProcessExecutor();
    private final DeployServerRepository servers;
    private final SshAccess ssh;
    /** 是否走 {@code docker --context} 路径（默认关闭：需要运维侧先建好 context）。 */
    private boolean useDockerContext;
    /** 镜像准入策略（与构建沙箱共用同一份判定）；null 视为不限制。 */
    private SandboxImagePolicy imagePolicy = SandboxImagePolicy.permissive();

    public ServerDeployer(DeployServerRepository servers, SshAccess ssh) {
        this.servers = servers;
        this.ssh = ssh;
    }

    /** 注入镜像准入策略（与 DockerDeployer / DockerSandbox 共用，保证判定一致）。 */
    public ServerDeployer setImagePolicy(SandboxImagePolicy policy) {
        this.imagePolicy = policy == null ? SandboxImagePolicy.permissive() : policy;
        return this;
    }

    /** 打开/关闭 {@code docker --context} 部署路径（{@code agent.deploy.use-docker-context}）。 */
    public ServerDeployer setUseDockerContext(boolean enabled) {
        this.useDockerContext = enabled;
        return this;
    }

    /** 没有配置存储时的占位实现（测试/默认装配用）：一律 E-505。 */
    public static ServerDeployer unconfigured() {
        return new ServerDeployer(new EmptyServerRepository(), new SshExec());
    }

    @Override
    public boolean supports(DeployTarget target) {
        return target == DeployTarget.SERVER;
    }

    @Override
    public DeployResult deploy(DeployRequest request) {
        // 同一环境串行：两条流水线同时发布会各自 rm -f 同一容器，最后谁在跑取决于时序。
        String key = (request.serverId() == null || request.serverId().isBlank()
                ? "default" : request.serverId())
                + "|" + request.projectId() + "|" + request.envName();
        ReentrantLock lock = DEPLOY_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_START_FAILED.code(),
                    "环境 " + request.envName() + " 已有部署正在进行：请等待当前部署结束后重试", "");
        }
        try {
            return deployOnce(request);
        } catch (RuntimeException e) {
            return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_START_FAILED.code(),
                    "部署异常：" + e.getMessage(), "");
        } finally {
            lock.unlock();
        }
    }

    private DeployResult deployOnce(DeployRequest request) {
        Optional<DeployServer> found = request.serverId() == null || request.serverId().isBlank()
                ? servers.findDefault()
                : servers.find(request.serverId());
        if (found.isEmpty()) {
            return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_TARGET_UNCONFIGURED.code(),
                    "还没有可用的部署服务器：请到「设置 → 服务器」添加并设为默认", "");
        }
        DeployServer server = found.get();
        if (!server.enabled()) {
            return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_TARGET_UNCONFIGURED.code(),
                    "服务器「" + server.name() + "」已被停用", "");
        }
        if ("KEY".equalsIgnoreCase(server.authType())
                && (server.keyPath() == null || server.keyPath().isBlank())) {
            return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_TARGET_UNCONFIGURED.code(),
                    "服务器「" + server.name() + "」未配置 SSH 私钥路径", "");
        }
        String secret = servers.secretOf(server.id()).orElse(null);

        String image = request.rollback() ? request.previousImageRef() : request.imageRef();
        if (image == null || image.isBlank()) {
            return DeployResult.failed(DeployTarget.SERVER,
                    request.rollback() ? ErrorCode.ROLLBACK_VERSION_MISSING.code()
                            : ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                    request.rollback()
                            ? "没有可回滚的上一版镜像" : "SERVER 目标需要镜像：请先执行 image_package",
                    "");
        }
        try {
            image = ImageRef.require(image, "deploy.image");
            imagePolicy.require(image, SandboxImagePolicy.Source.DEPLOY);
        } catch (RecipeException e) {
            return DeployResult.failed(DeployTarget.SERVER, e.code(), e.getMessage(), "");
        } catch (top.rongx.agent.domain.error.SandboxDeniedException e) {
            // 镜像不在允许列表 / 浮动 tag：策略拒绝（E-707），不是部署失败
            return DeployResult.failed(DeployTarget.SERVER, e.code(), e.getMessage(), "");
        }

        // 版本槽：打 tag 的发布按版本号独立命名（不同补丁版本/不同版本线互不覆盖），
        // 不打 tag 才落到该环境的默认槽。远端以前固定用环境名，导致每次发布都删掉上一版。
        String slot = DeploySlot.of(request);
        String container = DockerDeployer.containerName(request.projectId(), request.envName(), slot);
        StringBuilder log = new StringBuilder();

        // 远端 docker 预检：普通 docker 不可用时尝试免密 sudo，而不是等到 docker load 才失败。
        String docker = dockerPrefix(server, secret, log);
        if (docker == null) {
            return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                    "远端服务器「" + server.name() + "」的 Docker 不可用：请确认已安装 docker，"
                            + "并把 SSH 用户加入 docker 组（或配置 NOPASSWD sudo）", redact(log));
        }

        // 远端目录：配置值优先；无权限时回退 $HOME，避免首次部署直接卡在 SFTP。
        String remoteDir = ensureRemoteDir(server, secret, log);
        if (remoteDir == null) {
            return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                    "远端部署目录不可写：请检查「设置 → 服务器」里的部署目录权限"
                            + "（或让 SSH 用户对 $HOME 可写）", redact(log));
        }

        // SERVER 目标与 DOCKER 目标的网络语义对齐：本地会 ensureNetwork，
        // 远端过去却把 --network 原样塞给 docker run，prod 因此停在
        // "network agent-prod-net not found"。在传镜像之前先确保，避免白传几百 MB。
        String network = request.network();
        if (!ensureRemoteNetwork(network, "远端环境",
                () -> ssh.run(server, secret,
                        networkCommand(docker, "inspect", network), PROBE_TIMEOUT_MS).ok(),
                () -> ssh.run(server, secret,
                        networkCommand(docker, "create", network), PROBE_TIMEOUT_MS).ok(),
                log)) {
            return DeployResult.failed(DeployTarget.SERVER,
                    ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                    "远端环境网络 " + network.trim() + " 不存在且创建失败："
                            + "请在远端执行 docker network create " + network.trim()
                            + "，或确认远端 docker 用户有建网权限",
                    redact(log));
        }

        Path workdir = Path.of(request.workspace());
        Path localTar = workdir.resolve(".agent/deploy")
                .resolve(request.projectId() + "-" + Hashes.shortSha(request.gitSha()) + ".tar");
        String remoteTar = remoteDir + "/" + request.projectId() + "-"
                + Hashes.shortSha(request.gitSha()) + ".tar";
        String quotedTar = JschSsh.shellQuote(remoteTar);
        boolean uploaded = false;
        Path localEnvFile = null;
        String remoteEnvFile = null;

        try {
            Files.createDirectories(localTar.getParent());
        } catch (IOException e) {
            return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                    "无法创建暂存目录：" + e.getMessage(), "");
        }

        try {
            // docker --context 路径（D17，可选）：本机 CLI 直连远端守护进程，
            // 镜像由 docker 自己传（ssh:// 通道或 registry），不走 save/tar/load。
            // 开关默认关闭：context 需要运维一次性 `docker context create`，配错时的
            // 报错远不如现有 SSH 诊断清晰，因此不改变默认行为。
            if (useContext(server) && contextReady(server, secret, log)) {
                return deployViaContext(request, server, secret, image, container, slot);
            }
            // 远端已有完全相同的镜像（同 Image ID）时跳过 save/传输/load：
            // 重复发布同一提交不再传几百 MB，也不会因为临时磁盘不足而失败。
            String localImageId = localImageId(workdir, image);
            String remoteImageId = remoteImageId(server, secret, docker, image);
            if (localImageId != null && localImageId.equals(remoteImageId)) {
                log.append("[image] 远端已存在相同镜像 ").append(shortId(localImageId))
                        .append("，跳过 save/传输/load\n");
            } else {
                ProcessExecutor.StepOutcome save = executor.exec(workdir, List.of(
                        "docker", "save", "-o", localTar.toString(), image),
                        SAVE_TIMEOUT_MS, log, BooleanSupplierNever.INSTANCE);
                if (save.exitCode() != 0) {
                    return DeployResult.failed(DeployTarget.SERVER,
                            ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                            "docker save 失败（镜像是否存在？）：" + save.summary()
                                    + "；请重新执行 image_package 生成该版本", redact(log));
                }

                // 传大镜像前先看远端空间：否则会在传一半时失败，留下半截文件和难懂的报错。
                long tarBytes;
                try {
                    tarBytes = Files.size(localTar);
                } catch (IOException e) {
                    tarBytes = 0;
                }
                if (!ensureRemoteSpace(server, secret, remoteDir, tarBytes, log)) {
                    return DeployResult.failed(DeployTarget.SERVER,
                            ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                            "远端部署目录空间不足：镜像约 " + (tarBytes / 1024 / 1024 + 1)
                                    + "MB，请清理旧镜像/日志后重试（docker image prune -f）",
                            redact(log));
                }

                SshAccess.Result transfer = ssh.upload(server, secret, localTar, remoteTar,
                        TRANSFER_TIMEOUT_MS);
                if (!transfer.ok()) {
                    return DeployResult.failed(DeployTarget.SERVER,
                            ErrorCode.DEPLOY_START_FAILED.code(),
                            "镜像传输失败：" + DockerDeployer.sanitize(transfer.error(), request.env())
                                    + "（已尝试 SFTP 与 exec 上传；请检查远端磁盘空间与目录权限）",
                            redact(log));
                }
                uploaded = true;

                // 大镜像 load 比普通命令慢得多，单独预算；沿用 90s 会在生产大包上误判超时。
                SshAccess.Result load = ssh.run(server, secret,
                        docker + " load -i " + quotedTar, LOAD_TIMEOUT_MS);
                if (!load.ok()) {
                    return DeployResult.failed(DeployTarget.SERVER,
                            ErrorCode.DEPLOY_START_FAILED.code(),
                            "远端 docker load 失败："
                                    + DockerDeployer.sanitize(load.error(), request.env())
                                    + "（请确认远端磁盘空间与 docker 权限）", redact(log));
                }
            }

            // 只替换「同名同版本」的容器：打 tag 时容器名带版本号，
            // 其它版本（不同补丁/不同版本线）及默认槽的容器保持运行。
            // 同时记录上一版镜像与端口：新版本启动失败时用它原地恢复，避免把可用环境弄丢。
            PreviousContainer previous = previousContainer(server, secret, docker, container,
                    request.containerPort());
            int previousPort = previous == null ? 0 : previous.hostPort();
            if (previous != null && previous.running() && previousPort > 0
                    && request.runId() != null && request.runId().equals(previous.runId())
                    && localImageId != null && localImageId.equals(previous.imageId())) {
                // 幂等：同一次运行重试、镜像与端口都一致，不再重启（避免无谓停机）。
                // 只比镜像不够：改过环境变量/网络也需要重启，所以这里还要求 runId 相同。
                log.append("[deploy] 本次运行已部署且镜像一致，跳过重复重启\n");
                return DeployResult.ok(DeployTarget.SERVER,
                        // 部署地址 = 根路径（页面入口）；healthPath 只用于探活
                        "http://" + server.host() + ":" + previousPort + "/",
                        server.host() + ":" + container, redact(log));
            }
            // 环境变量很多时会超出 ssh 单条命令长度上限：改成上传 600 权限的 env-file。
            String envFileArg = null;
            if (needsEnvFile(request.env())) {
                String envName = request.projectId() + "-" + Hashes.shortSha(request.gitSha()) + ".env";
                localEnvFile = localTar.resolveSibling(envName);
                try {
                    Files.writeString(localEnvFile, envFileContent(request.env()),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return DeployResult.failed(DeployTarget.SERVER,
                            ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                            "无法写入环境变量文件：" + e.getMessage(), redact(log));
                }
                String remoteEnv = remoteDir + "/" + envName;
                SshAccess.Result envUpload = ssh.upload(server, secret, localEnvFile, remoteEnv,
                        TRANSFER_TIMEOUT_MS);
                if (!envUpload.ok()) {
                    return DeployResult.failed(DeployTarget.SERVER,
                            ErrorCode.DEPLOY_START_FAILED.code(),
                            "环境变量文件上传失败："
                                    + DockerDeployer.sanitize(envUpload.error(), request.env()),
                            redact(log));
                }
                // 文件里有密钥：仅 SSH 用户可读
                ssh.run(server, secret, "chmod 600 " + JschSsh.shellQuote(remoteEnv),
                        PROBE_TIMEOUT_MS);
                remoteEnvFile = remoteEnv;
                envFileArg = remoteEnv;
                log.append("[deploy] 环境变量较多，改用 --env-file 传递\n");
            }

            SshAccess.Result removed = ssh.run(server, secret, docker + " rm -f " + container,
                    SSH_TIMEOUT_MS);
            if (removed.ok()) {
                log.append("[deploy] 已停止").append(slot == null ? "默认槽" : "版本 " + slot)
                        .append(" 的旧容器 ").append(container).append('\n');
            } else {
                // 删除失败不直接放弃：继续尝试 docker run，由最终报错给出真实原因（如守护进程异常）
                log.append("[deploy] 旧容器停止返回非零：").append(brief(removed.error()))
                        .append('\n');
            }

            // 端口探测只建 1~2 条 SSH 连接：旧实现每个候选端口探测 2 次，
            // 端口被占时会连续建 400 条连接，最后报"端口耗尽"却和真实原因无关。
            Set<Integer> used = listeningPorts(server, secret, log);
            int hostPort = previousPort > 0 && (used == null || !used.contains(previousPort))
                    ? previousPort
                    : firstFreePort(request.hostPort(), used);
            if (hostPort < 0) {
                return DeployResult.failed(DeployTarget.SERVER, ErrorCode.SECRET_UNRESOLVED.code(),
                        "远端端口耗尽：从 " + request.hostPort() + " 起 " + PORT_SCAN_LIMIT
                                + " 个端口都被占用", redact(log));
            }
            if (hostPort != request.hostPort()) {
                log.append("[port] 远端 ").append(request.hostPort())
                        .append(" 被占用 → 分配 ").append(hostPort).append('\n');
            }

            SshAccess.Result run = null;
            for (int attempt = 0; attempt < PORT_RETRY_LIMIT && hostPort > 0; attempt++) {
                run = ssh.run(server, secret,
                        runCommand(docker, image, request, container, hostPort,
                                displayVersion(request), envFileArg), SSH_TIMEOUT_MS);
                if (run.ok()) {
                    break;
                }
                if (!portConflict(run.error())) {
                    break;
                }
                log.append("[port] 端口 ").append(hostPort).append(" 冲突，换下一个端口重试\n");
                if (used != null) {
                    used.add(hostPort);
                    hostPort = nextFreePort(hostPort + 1, used);
                } else {
                    hostPort = hostPort < 65535 ? hostPort + 1 : -1;
                }
            }
            if (run == null || !run.ok()) {
                String reason = run == null ? "未执行 docker run" : run.error();
                // 新版本没起来时把上一版拉起来：部署失败可以接受，丢掉正在服务的版本不行。
                int restorePort = hostPort > 0 ? hostPort : Math.max(previousPort, request.hostPort());
                if (previous != null && restorePort > 0) {
                    SshAccess.Result restore = ssh.run(server, secret,
                            runCommand(docker, previous.image(), request, container, restorePort,
                                    previous.version(), envFileArg), SSH_TIMEOUT_MS);
                    if (restore.ok()) {
                        log.append("[rollback] 新版本启动失败，已用上一版恢复：")
                                .append(previous.image()).append("（版本 ")
                                .append(previous.version()).append("，端口 ")
                                .append(restorePort).append("）\n");
                        return DeployResult.failed(DeployTarget.SERVER,
                                ErrorCode.DEPLOY_START_FAILED.code(),
                                "远端容器启动失败：" + DockerDeployer.sanitize(reason, request.env())
                                        + "；已自动恢复上一版 " + previous.version()
                                        + "（" + previous.image() + "）", redact(log));
                    }
                    log.append("[rollback] 上一版恢复失败：").append(brief(restore.error()))
                            .append('\n');
                }
                return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_START_FAILED.code(),
                        "远端容器启动失败：" + DockerDeployer.sanitize(reason, request.env()),
                        redact(log));
            }

            return DeployResult.ok(DeployTarget.SERVER,
                    // 部署地址 = 根路径（页面入口）；healthPath 只用于探活
                    "http://" + server.host() + ":" + hostPort + "/",
                    server.host() + ":" + container, redact(log));
        } finally {
            try {
                Files.deleteIfExists(localTar);
                if (localEnvFile != null) {
                    Files.deleteIfExists(localEnvFile);
                }
            } catch (IOException ignored) {
                // 暂存文件清理失败不影响部署结果
            }
            if (uploaded) {
                // 远端 tar 只在 load 时需要：不清理会在小磁盘服务器上越积越多，
                // 最终表现为下一次上传/load 随机失败。
                ssh.run(server, secret, "rm -f " + quotedTar, PROBE_TIMEOUT_MS);
            }
            if (remoteEnvFile != null) {
                // 环境变量文件含密钥：用完立即删除，不在远端留痕
                ssh.run(server, secret, "rm -f " + JschSsh.shellQuote(remoteEnvFile),
                        PROBE_TIMEOUT_MS);
            }
        }
    }

    /**
     * context 是否真的指向这台服务器且远端 docker 可用。
     *
     * <p>校验两件事，缺一不可：
     * <ol>
     *   <li>本机存在该 context（{@code docker context inspect}）；</li>
     *   <li>经该 context 能问到远端守护进程的版本。</li>
     * </ol>
     * 任一失败就<b>回落到 SSH 路径</b>并把原因写进日志——context 是可选优化，
     * 不能因为它把一条原本能成功的部署弄失败。
     */
    private boolean contextReady(DeployServer server, String secret, StringBuilder log) {
        String name = contextName(server);
        ProcessExecutor.StepOutcome inspect = executor.exec(Path.of("."),
                List.of("docker", "context", "inspect", name), PROBE_TIMEOUT_MS,
                new StringBuilder(), BooleanSupplierNever.INSTANCE);
        if (inspect.exitCode() != 0) {
            log.append("[deploy] 未找到 docker context ").append(name)
                    .append("，回落到 SSH + save/load 路径（如需启用请执行 "
                            + "docker context create ").append(name)
                    .append(" --docker host=ssh://").append(server.sshUser())
                    .append('@').append(server.host()).append("）\n");
            return false;
        }
        ProcessExecutor.StepOutcome version = executor.exec(Path.of("."),
                List.of("docker", "--context", name, "version", "--format", "{{.Server.Version}}"),
                PROBE_TIMEOUT_MS, new StringBuilder(), BooleanSupplierNever.INSTANCE);
        if (version.exitCode() != 0) {
            log.append("[deploy] context ").append(name)
                    .append(" 无法连到远端守护进程，回落到 SSH 路径\n");
            return false;
        }
        log.append("[deploy] 使用 docker context ").append(name).append(" 直连远端（跳过 save/tar/load）\n");
        return true;
    }

    /**
     * 经 {@code docker --context} 部署：不需要 save/ssh/load，镜像由 docker 自己传输。
     *
     * <p>失败语义与 SSH 路径一致：先删同名容器，再起新的；起不来时用上一版恢复。
     * 端口沿用"先看旧容器、再看远端监听"的策略，但读取都走 context（本机 CLI 执行）。
     */
    private DeployResult deployViaContext(DeployRequest request, DeployServer server,
                                          String secret, String image, String container,
                                          String slot) {
        StringBuilder log = new StringBuilder();
        String name = contextName(server);
        // 0. 与 SSH 路径一样先确保远端网络存在（context 直连的是同一台远端守护进程）。
        String network = request.network();
        if (!ensureRemoteNetwork(network, "远端环境",
                () -> executor.exec(Path.of("."),
                        contextNetworkArgv("docker", name, "inspect", network),
                        PROBE_TIMEOUT_MS, new StringBuilder(), BooleanSupplierNever.INSTANCE)
                        .exitCode() == 0,
                () -> executor.exec(Path.of("."),
                        contextNetworkArgv("docker", name, "create", network),
                        PROBE_TIMEOUT_MS, new StringBuilder(), BooleanSupplierNever.INSTANCE)
                        .exitCode() == 0,
                log)) {
            return DeployResult.failed(DeployTarget.SERVER,
                    ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                    "远端环境网络 " + network.trim() + " 不存在且创建失败（docker context "
                            + name + "）：请在远端执行 docker network create " + network.trim(),
                    redact(log));
        }
        // 1. 远端是否已有该镜像；没有就先 pull（镜像同名时必须能被 registry 解析，
        //    否则会明确报"镜像不存在"，而不是像 save/load 那样传几百 MB 才发现）
        ProcessExecutor.StepOutcome exists = executor.exec(Path.of("."),
                List.of("docker", "--context", name, "image", "inspect", "-f", "{{.Id}}", image),
                PROBE_TIMEOUT_MS, new StringBuilder(), BooleanSupplierNever.INSTANCE);
        if (exists.exitCode() != 0) {
            log.append("[image] 远端缺少镜像 ").append(image).append("，尝试 docker pull\n");
            ProcessExecutor.StepOutcome pull = executor.exec(Path.of("."),
                    List.of("docker", "--context", name, "pull", image),
                    TRANSFER_TIMEOUT_MS, log, BooleanSupplierNever.INSTANCE);
            if (pull.exitCode() != 0) {
                return DeployResult.failed(DeployTarget.SERVER,
                        ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                        "远端缺少镜像且 pull 失败：" + image
                                + "；请把镜像推到远端可访问的 registry，"
                                + "或关闭 agent.deploy.use-docker-context 改用 save/load 传输",
                        redact(log));
            }
        }

        // 2. 旧容器的版本/镜像/端口：新版本起不来时原地恢复
        PreviousContainer previous = previousContainerViaContext(name, container,
                request.containerPort());
        int previousPort = previous == null ? 0 : previous.hostPort();
        if (previous != null && previous.running() && previousPort > 0
                && request.runId() != null && request.runId().equals(previous.runId())) {
            log.append("[deploy] 本次运行已部署且镜像一致，跳过重复重启\n");
            return DeployResult.ok(DeployTarget.SERVER,
                    "http://" + server.host() + ":" + previousPort + "/",
                    server.host() + ":" + container, redact(log));
        }

        // 3. 删旧 + 起新（先删后起的语义与原路径一致：同名容器不能并存）
        executor.exec(Path.of("."),
                List.of("docker", "--context", name, "rm", "-f", container),
                SSH_TIMEOUT_MS, log, BooleanSupplierNever.INSTANCE);
        int hostPort = previousPort > 0 ? previousPort : request.hostPort();
        String envFile = null;   // context 模式用 -e，不落远端临时文件（少一处密钥留痕）
        java.util.List<String> argv = contextArgv("docker", name, image, request, container,
                hostPort, displayVersion(request), envFile);
        ProcessExecutor.StepOutcome run = executor.exec(Path.of("."), argv, SSH_TIMEOUT_MS,
                log, BooleanSupplierNever.INSTANCE);
        if (run.exitCode() != 0) {
            // 端口冲突：换下一个端口重试（与 SSH 路径同策略，最多 PORT_RETRY_LIMIT 次）
            for (int attempt = 0; attempt < PORT_RETRY_LIMIT && portConflict(run.summary()); attempt++) {
                hostPort = hostPort < 65535 ? hostPort + 1 : -1;
                if (hostPort < 0) {
                    break;
                }
                log.append("[port] 端口冲突，改用 ").append(hostPort).append('\n');
                run = executor.exec(Path.of("."), contextArgv("docker", name, image, request,
                        container, hostPort, displayVersion(request), envFile), SSH_TIMEOUT_MS,
                        log, BooleanSupplierNever.INSTANCE);
                if (run.exitCode() == 0) {
                    break;
                }
            }
        }
        if (run.exitCode() != 0) {
            if (previous != null && previousPort > 0) {
                ProcessExecutor.StepOutcome restore = executor.exec(Path.of("."),
                        contextArgv("docker", name, previous.image(), request, container,
                                previousPort, previous.version(), envFile),
                        SSH_TIMEOUT_MS, log, BooleanSupplierNever.INSTANCE);
                if (restore.exitCode() == 0) {
                    log.append("[rollback] 新版本启动失败，已用上一版恢复：")
                            .append(previous.image()).append("（端口 ").append(previousPort).append("）\n");
                    return DeployResult.failed(DeployTarget.SERVER,
                            ErrorCode.DEPLOY_START_FAILED.code(),
                            "远端容器启动失败：" + DockerDeployer.sanitize(run.summary(), request.env())
                                    + "；已自动恢复上一版 " + previous.version(),
                            redact(log));
                }
            }
            return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_START_FAILED.code(),
                    "远端容器启动失败：" + DockerDeployer.sanitize(run.summary(), request.env()),
                    redact(log));
        }
        return DeployResult.ok(DeployTarget.SERVER,
                "http://" + server.host() + ":" + hostPort + "/",
                server.host() + ":" + container, redact(log));
    }

    /** context 模式下读取旧容器的镜像/版本/端口（等价于 {@link #previousContainer}）。 */
    private PreviousContainer previousContainerViaContext(String contextName, String container,
                                                         int containerPort) {
        ProcessExecutor.StepOutcome inspect = executor.exec(Path.of("."),
                List.of("docker", "--context", contextName, "inspect", "-f",
                        "{{.Config.Image}}|{{.Image}}|{{.State.Running}}|"
                                + "{{index .Config.Labels \"agent.version\"}}|"
                                + "{{index .Config.Labels \"agent.run\"}}", container),
                PROBE_TIMEOUT_MS, new StringBuilder(), BooleanSupplierNever.INSTANCE);
        if (inspect.exitCode() != 0 || inspect.summary() == null || inspect.summary().isBlank()) {
            return null;
        }
        String[] parts = inspect.summary().trim().split("\\|", -1);
        String image = parts[0].trim();
        if (image.isEmpty() || "null".equals(image)) {
            return null;
        }
        int port = 0;
        if (containerPort > 0) {
            ProcessExecutor.StepOutcome mapped = executor.exec(Path.of("."),
                    List.of("docker", "--context", contextName, "port", container,
                            containerPort + "/tcp"),
                    PROBE_TIMEOUT_MS, new StringBuilder(), BooleanSupplierNever.INSTANCE);
            port = mapped.exitCode() == 0 ? parseMappedPort(mapped.summary()) : 0;
        }
        boolean running = parts.length > 2 && "true".equalsIgnoreCase(parts[2].trim());
        String version = parts.length > 3 ? parts[3].trim() : "";
        String runId = parts.length > 4 ? parts[4].trim() : "";
        return new PreviousContainer(image, parts.length > 1 ? parts[1].trim() : "", running,
                version.isEmpty() ? "previous" : version, runId, port);
    }

    /**
     * 探测远端 docker：优先普通用户，权限不足时尝试 {@code sudo -n}。     *
     * @return 可用的 docker 前缀（{@code docker} / {@code sudo -n docker}）；不可用返回 null
     */
    private String dockerPrefix(DeployServer server, String secret, StringBuilder log) {
        String version = " version --format '{{.Server.Version}}'";
        SshAccess.Result plain = ssh.run(server, secret, "docker" + version, PROBE_TIMEOUT_MS);
        if (plain.ok()) {
            return "docker";
        }
        String reason = String.valueOf(plain.error());
        String lower = reason.toLowerCase(Locale.ROOT);
        if (lower.contains("permission denied")) {
            SshAccess.Result sudo = ssh.run(server, secret, "sudo -n docker" + version,
                    PROBE_TIMEOUT_MS);
            if (sudo.ok()) {
                log.append("[remote] 当前用户无 docker 权限，已改用 sudo -n docker\n");
                return "sudo -n docker";
            }
            reason = reason + "；sudo -n 同样不可用（" + String.valueOf(sudo.error()).trim() + "）";
        }
        log.append("[remote] Docker 预检失败：").append(reason.trim()).append('\n');
        return null;
    }

    /**
     * 确保远端部署目录存在且可写；配置目录不可写时回退到 {@code $HOME/.agent/deploy}。
     *
     * @return 实际使用的目录；都不可用返回 null
     */
    private String ensureRemoteDir(DeployServer server, String secret, StringBuilder log) {
        String configured = server.remoteDir() == null || server.remoteDir().isBlank()
                ? "/opt/agent/deploy" : server.remoteDir().trim();
        // 支持 ~/deploy：单引号会阻止 ~ 展开，这里拼成 "$HOME"/'deploy' 让远端 shell 展开。
        String quotedConfigured = configured.startsWith("~/")
                ? "\"$HOME\"/" + JschSsh.shellQuote(configured.substring(2))
                : JschSsh.shellQuote(configured);
        String found = tryRemoteDir(server, secret, quotedConfigured);
        if (found != null) {
            return found;
        }
        log.append("[remote] 目录 ").append(configured)
                .append(" 不存在或不可写，回退 $HOME/.agent/deploy\n");
        return tryRemoteDir(server, secret, "\"$HOME/.agent/deploy\"");
    }

    private String tryRemoteDir(DeployServer server, String secret, String quotedDir) {
        String marker = "__AGENT_DIR__";
        SshAccess.Result result = ssh.run(server, secret,
                "mkdir -p " + quotedDir + " 2>/dev/null"
                        + " && [ -w " + quotedDir + " ]"
                        + " && echo " + marker + quotedDir,
                PROBE_TIMEOUT_MS);
        if (!result.ok()) {
            return null;
        }
        for (String line : result.output().split("\\R")) {
            if (line.startsWith(marker)) {
                String dir = line.substring(marker.length()).trim();
                return dir.isEmpty() ? null : dir;
            }
        }
        return null;
    }

    /**
     * 读取远端监听端口。
     *
     * <p>优先直接读 {@code /proc/net/tcp}（任何 Linux 都有，不依赖 iproute2/net-tools），
     * 失败再退回 {@code ss}/{@code netstat}；都不可用返回 null，由 docker run 自己报冲突。
     */
    private Set<Integer> listeningPorts(DeployServer server, String secret, StringBuilder log) {
        SshAccess.Result proc = ssh.run(server, secret,
                "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null", PROBE_TIMEOUT_MS);
        if (proc.ok() && proc.output().contains("local_address")) {
            return parseProcNetPorts(proc.output());
        }
        SshAccess.Result tools = ssh.run(server, secret,
                "ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null", PROBE_TIMEOUT_MS);
        if (tools.ok()) {
            Set<Integer> ports = parseListeningPorts(tools.output());
            if (!ports.isEmpty()) {
                return ports;
            }
        }
        log.append("[port] 远端无法读取监听端口（缺少 /proc、ss、netstat），"
                + "跳过预检，直接尝试部署端口\n");
        return null;
    }

    /** 解析 /proc/net/tcp(|6)：state=0A 为 LISTEN，端口是十六进制。 */
    static Set<Integer> parseProcNetPorts(String output) {
        Set<Integer> ports = new HashSet<>();
        if (output == null) {
            return ports;
        }
        for (String line : output.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 4 || !"0A".equalsIgnoreCase(fields[3])) {
                continue;
            }
            int colon = fields[1].lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            try {
                ports.add(Integer.parseInt(fields[1].substring(colon + 1), 16));
            } catch (NumberFormatException ignored) {
                // 表头或异常行
            }
        }
        return ports;
    }

    /** 解析 {@code ss -ltn} / {@code netstat -ltn} 的输出（列结构与版本无关地取地址列）。 */
    static Set<Integer> parseListeningPorts(String output) {
        Set<Integer> ports = new HashSet<>();
        if (output == null) {
            return ports;
        }
        for (String line : output.split("\\R")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 4) {
                continue;
            }
            String local = fields[3];
            int colon = local.lastIndexOf(':');
            if (colon < 0 || colon == local.length() - 1) {
                continue;
            }
            try {
                int port = Integer.parseInt(local.substring(colon + 1).trim());
                if (port > 0) {
                    ports.add(port);
                }
            } catch (NumberFormatException ignored) {
                // 表头（Local Address:Port）等非数据行
            }
        }
        return ports;
    }

    /** 从期望端口向后找第一个空闲端口；{@code used == null} 表示无法探测，直接信任期望端口。 */
    static int firstFreePort(int desired, Set<Integer> used) {
        int start = Math.max(1, desired);
        if (used == null) {
            return start <= 65535 ? start : -1;
        }
        for (int port = start; port <= 65535 && port <= start + PORT_SCAN_LIMIT; port++) {
            if (!used.contains(port)) {
                return port;
            }
        }
        return -1;
    }

    static int nextFreePort(int from, Set<Integer> used) {
        int start = Math.max(1, from);
        if (used == null) {
            return start <= 65535 ? start : -1;
        }
        for (int port = start; port <= 65535 && port <= start + PORT_SCAN_LIMIT; port++) {
            if (!used.contains(port)) {
                return port;
            }
        }
        return -1;
    }

    /** docker run 的端口冲突报错（不同 docker 版本措辞不同，统一识别）。 */
    static boolean portConflict(String error) {
        String text = String.valueOf(error).toLowerCase(Locale.ROOT);
        return text.contains("port is already allocated")
                || text.contains("address already in use")
                || text.contains("failed to bind")
                || text.contains("bind for");
    }

    /**
     * 远端 {@code docker run} 命令。
     *
     * <p>加固与应用容器（{@link DockerDeployer}）保持<b>同一份标准</b>：
     * {@code no-new-privileges} + {@code --cap-drop ALL}。原实现这两项全缺，
     * 远端容器是整条链路里唯一"可以容器内提权后横向移动"的容器。
     *
     * <p>{@code -p} 绑 {@code 127.0.0.1}：与 {@link DockerDeployer} 一致，
     * 并且与回报给用户的 endpoint 语义一致。原先绑 0.0.0.0 却回报
     * {@code http://host:port}（本机语义），界面与实际暴露面相反。
     */
    private static String runCommand(String docker, String image, DeployRequest request,
                                     String container, int hostPort, String labelVersion,
                                     String envFile) {
        StringBuilder command = new StringBuilder(docker).append(' ').append(
                String.join(" ", hardeningFlags(hostPort, request.containerPort())));
        command.append(" --label agent.project=").append(JschSsh.shellQuote(request.projectId()))
                .append(" --label agent.env=").append(JschSsh.shellQuote(
                        request.envName() == null ? "" : request.envName()))
                .append(" --label agent.version=").append(JschSsh.shellQuote(labelVersion))
                .append(" --label agent.sha=").append(JschSsh.shellQuote(request.gitSha()))
                .append(" --label agent.run=").append(JschSsh.shellQuote(
                        request.runId() == null ? "" : request.runId()))
                .append(" --name ").append(JschSsh.shellQuote(container));
        if (request.network() != null && !request.network().isBlank()) {
            command.append(" --network ").append(JschSsh.shellQuote(request.network().trim()));
        }
        if (envFile == null) {
            command.append(remoteEnvArgs(request.env()));
        } else {
            command.append(" --env-file ").append(JschSsh.shellQuote(envFile));
        }
        command.append(' ').append(image);
        return command.toString();
    }

    /**
     * 远端容器与本地应用容器共用的加固参数（<b>唯一来源</b>）。
     *
     * <p>两条路径（SSH 字符串 / {@code --context} argv）都从这里取，
     * 否则"远端少了 --cap-drop"这类漂移会再次出现。
     */
    static java.util.List<String> hardeningFlags(int hostPort, int containerPort) {
        return java.util.List.of(
                "run", "-d",
                "--restart", "unless-stopped",
                "--security-opt", "no-new-privileges",
                "--cap-drop", "ALL",
                // 宿主端口只绑回环：与 DockerDeployer 一致，也与回报给用户的 endpoint 语义一致。
                // 需要对外暴露时由运维在服务器侧加反向代理/防火墙。
                "-p", bindHost() + ":" + hostPort + ":" + containerPort);
    }

    /**
     * 宿主侧绑定地址。
     *
     * <p>固定 {@code 127.0.0.1}：远程部署的入口应是反向代理，而不是直接暴露 docker 端口。
     * 若某台服务器确实需要对外直连，正确做法是在部署机上加 nginx/防火墙规则，
     * 而不是让平台在所有服务器上都监听 {@code 0.0.0.0}。
     */
    static String bindHost() {
        return "127.0.0.1";
    }

    /**
     * {@code docker --context} 模式下的 argv（D17）。
     *
     * <p>为什么要有这条路：默认路径是 {@code docker save} → SSH 上传 tar → 远端
     * {@code docker load}，跨公网传几百 MB，且远端命令是拼接出来的字符串（要自己
     * {@code shellQuote}）。当运维在本机建好指向服务器的 docker context
     * （{@code docker context create x --docker host=ssh://user@host}）后，本机 CLI 可以
     * <b>直接</b>操作远端守护进程：传输改由 docker 自己走，命令是 argv 而不是字符串。
     *
     * <p>这是<b>可选</b>路径（{@code agent.deploy.use-docker-context=true}）：context 依赖
     * 运维侧一次性配置，配错时的报错远不如现有 SSH 诊断清晰，因此默认仍走 SSH 字符串模式。
     */
    static java.util.List<String> contextArgv(String dockerBinary, String contextName,
                                              String image, DeployRequest request,
                                              String container, int hostPort,
                                              String labelVersion, String envFile) {
        java.util.List<String> argv = new java.util.ArrayList<>();
        // --context 是全局选项，必须插在子命令 run 之前
        argv.add(dockerBinary);
        argv.add("--context");
        argv.add(contextName);
        argv.addAll(hardeningFlags(hostPort, request.containerPort()));
        argv.addAll(java.util.List.of(
                "--label", "agent.project=" + request.projectId(),
                "--label", "agent.env=" + (request.envName() == null ? "" : request.envName()),
                "--label", "agent.version=" + labelVersion,
                "--label", "agent.sha=" + request.gitSha(),
                "--label", "agent.run=" + (request.runId() == null ? "" : request.runId()),
                "--name", container));
        if (request.network() != null && !request.network().isBlank()) {
            argv.addAll(java.util.List.of("--network", request.network().trim()));
        }
        if (envFile == null) {
            new java.util.TreeMap<>(request.env()).forEach((key, value) ->
                    argv.addAll(java.util.List.of("-e", key + "=" + (value == null ? "" : value))));
        } else {
            argv.addAll(java.util.List.of("--env-file", envFile));
        }
        argv.add(image);
        return java.util.List.copyOf(argv);
    }

    /**
     * 远端网络就绪保证：语义与本地 {@link DockerNetwork#ensure} 完全一致（inspect → create）。
     *
     * <p>抽成纯逻辑 + 两个 {@link BooleanSupplier} 是为了让 SSH 路径与 context 路径
     * 共用同一份判定，同时能不依赖真实 SSH / Docker 做回归测试。
     *
     * @param scope 日志前缀（如「远端环境」），拼出「已创建远端环境网络 xxx」
     * @return true = 内置网络 / 已存在 / 创建成功
     */
    static boolean ensureRemoteNetwork(String network, String scope,
                                       BooleanSupplier inspect, BooleanSupplier create,
                                       StringBuilder log) {
        if (DockerNetwork.isBuiltIn(network)) {
            return true;
        }
        String name = network.trim();
        if (inspect.getAsBoolean()) {
            return true;
        }
        if (create.getAsBoolean()) {
            log.append("[deploy] 已创建").append(scope).append("网络 ").append(name).append('\n');
            return true;
        }
        log.append("[deploy] ").append(scope).append("网络 ").append(name)
                .append(" 创建失败\n");
        return false;
    }

    /** SSH 路径的远端网络命令（docker 前缀可能是 {@code sudo docker}）。 */
    static String networkCommand(String docker, String operation, String network) {
        return docker + " network " + operation + " " + JschSsh.shellQuote(network.trim());
    }

    /** context 路径的远端网络 argv（--context 必须在子命令之前）。 */
    static List<String> contextNetworkArgv(String dockerBinary, String contextName,
                                           String operation, String network) {
        return List.of(dockerBinary, "--context", contextName, "network", operation,
                network.trim());
    }

    /**
     * 服务器对应的 docker context 名。
     *
     * <p>带 {@code server.id()} 而非主机名：同一台机器可能配两条记录（不同 SSH 用户/端口），
     * 用主机名会让两条记录共用一个 context，后建的覆盖先建的。
     */
    static String contextName(DeployServer server) {
        String id = server == null || server.id() == null || server.id().isBlank()
                ? "default" : server.id();
        return "agent-" + id.replaceAll("[^A-Za-z0-9_.-]", "-").toLowerCase(java.util.Locale.ROOT);
    }

    /** 该服务器是否应走 {@code docker --context} 路径（配置开关 + context 名可用）。 */
    boolean useContext(DeployServer server) {
        return useDockerContext && server != null;
    }

    /**
     * 环境变量总长度超过单条 ssh 命令的安全阈值时改用 --env-file。
     *
     * <p>含换行的值无法写进 docker env-file 格式，继续走 {@code -e}（由 shellQuote 保证安全）。
     */
    static boolean needsEnvFile(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return false;
        }
        int total = 0;
        for (Map.Entry<String, String> e : env.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue();
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                return false;
            }
            total += e.getKey().length() + value.length() + 2;
        }
        return total > 2_000;
    }

    /** docker env-file 内容：按 key 排序，保证同样输入产生同样文件。 */
    static String envFileContent(Map<String, String> env) {
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(env).entrySet()) {
            content.append(e.getKey()).append('=')
                    .append(e.getValue() == null ? "" : e.getValue()).append('\n');
        }
        return content.toString();
    }

    private String redact(StringBuilder log) {
        return ProcessExecutor.redact(log.toString());
    }

    /** 远端 shell 里的 -e 参数（单引号包裹并转义，变量值含空格/引号也不会被 shell 拆开）。 */
    private static String remoteEnvArgs(java.util.Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : new java.util.TreeMap<>(env).entrySet()) {
            String pair = e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue());
            sb.append(" -e ").append(JschSsh.shellQuote(pair));
        }
        return sb.toString();
    }

    /**
     * 查询同名容器当前的宿主端口（仅打 tag 的版本使用）。
     *
     * <p>版本槽重复发布时沿用上次的端口：否则每次换端口，旧书签/监控失效。
     * 容器不存在或没有映射时返回 0。
     */
    private int existingHostPort(DeployServer server, String secret, String docker,
                                 String container, int containerPort) {
        if (containerPort <= 0) {
            return 0;
        }
        SshAccess.Result result = ssh.run(server, secret,
                docker + " port " + container + " " + containerPort + "/tcp", PROBE_TIMEOUT_MS);
        return result.ok() ? parseMappedPort(result.output()) : 0;
    }

    /**
     * 读取当前容器的镜像/版本/端口；容器不存在或读取失败返回 null。
     *
     * <p>新版本启动失败时用这些信息原地恢复上一版，保证「部署失败≠线上挂掉」。
     */
    private PreviousContainer previousContainer(DeployServer server, String secret, String docker,
                                                String container, int containerPort) {
        SshAccess.Result inspect = ssh.run(server, secret,
                docker + " inspect -f '{{.Config.Image}}|{{.Image}}|{{.State.Running}}|"
                        + "{{index .Config.Labels \"agent.version\"}}|"
                        + "{{index .Config.Labels \"agent.run\"}}' " + container,
                PROBE_TIMEOUT_MS);
        if (!inspect.ok() || inspect.output() == null || inspect.output().isBlank()) {
            return null;
        }
        String[] parts = inspect.output().trim().split("\\|", -1);
        String image = parts[0].trim();
        if (image.isEmpty() || "null".equals(image)) {
            return null;
        }
        String imageId = parts.length > 1 ? parts[1].trim() : "";
        boolean running = parts.length > 2 && "true".equalsIgnoreCase(parts[2].trim());
        String version = parts.length > 3 ? parts[3].trim() : "";
        String runId = parts.length > 4 ? parts[4].trim() : "";
        return new PreviousContainer(image, imageId, running,
                version.isEmpty() ? "previous" : version, runId,
                existingHostPort(server, secret, docker, container, containerPort));
    }

    /** 本机镜像 ID（{@code sha256:...}）；查不到返回 null。 */
    private String localImageId(Path workdir, String image) {
        ProcessExecutor.StepOutcome inspect = executor.exec(workdir,
                List.of("docker", "image", "inspect", "-f", "{{.Id}}", image),
                PROBE_TIMEOUT_MS, new StringBuilder(), BooleanSupplierNever.INSTANCE);
        return inspect.exitCode() == 0 ? firstLine(inspect.summary()) : null;
    }

    /** 远端镜像 ID；不存在或查询失败返回 null（失败时照常走完整传输流程）。 */
    private String remoteImageId(DeployServer server, String secret, String docker,
                                 String image) {
        SshAccess.Result inspect = ssh.run(server, secret,
                docker + " image inspect -f '{{.Id}}' " + JschSsh.shellQuote(image),
                PROBE_TIMEOUT_MS);
        return inspect.ok() ? firstLine(inspect.output()) : null;
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String line = text.trim().split("\\R")[0].trim();
        return line.isEmpty() ? null : line;
    }

    private static String shortId(String imageId) {
        String value = imageId.startsWith("sha256:") ? imageId.substring(7) : imageId;
        return value.isBlank() ? value : Hashes.shortSha(value, 12);
    }

    /**
     * 上传前预检远端磁盘空间。
     *
     * <p>{@code df} 不可用/输出异常时放行（不做误伤），真正空间不足由上传报错兜底。
     */
    private boolean ensureRemoteSpace(DeployServer server, String secret, String remoteDir,
                                      long bytes, StringBuilder log) {
        if (bytes <= 0) {
            return true;
        }
        SshAccess.Result result = ssh.run(server, secret,
                "df -Pk " + JschSsh.shellQuote(remoteDir) + " 2>/dev/null | tail -n 1",
                PROBE_TIMEOUT_MS);
        if (!result.ok() || result.output() == null || result.output().isBlank()) {
            return true;
        }
        String[] columns = result.output().trim().split("\\s+");
        if (columns.length < 4) {
            return true;
        }
        try {
            long availableKb = Long.parseLong(columns[3]);
            long requiredKb = bytes / 1024 + 2048;
            if (availableKb < requiredKb) {
                log.append("[space] 远端可用 ").append(availableKb / 1024)
                        .append("MB，镜像需要 ").append(requiredKb / 1024).append("MB\n");
                return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** 短错误：日志里保留可读原因，不把整段远端输出塞进结果。 */
    private static String brief(String error) {
        String text = error == null ? "" : error.trim();
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }

    /** 上一版容器信息（用于启动失败后的原地恢复与同请求幂等跳过）。 */
    private record PreviousContainer(String image, String imageId, boolean running,
                                     String version, String runId, int hostPort) {
    }

    /** 解析 {@code docker port} 输出（{@code 0.0.0.0:8080} / {@code [::]:8080}）。 */
    static int parseMappedPort(String output) {
        int best = 0;
        if (output == null) {
            return best;
        }
        for (String line : output.split("\\R")) {
            int colon = line.lastIndexOf(':');
            if (colon < 0 || colon == line.length() - 1) {
                continue;
            }
            try {
                int port = Integer.parseInt(line.substring(colon + 1).trim());
                if (port > 0 && port <= 65535) {
                    best = port;
                }
            } catch (NumberFormatException ignored) {
                // 表头/异常行
            }
        }
        return best;
    }

    /** 展示版本：用户没填版本号时统一显示 latest。 */
    private static String displayVersion(DeployRequest request) {
        return request.version() == null || request.version().isBlank()
                ? "latest" : request.version().trim();
    }

    /** 部署命令不支持取消（语义上由超时兜底）。 */
    private static final class BooleanSupplierNever implements java.util.function.BooleanSupplier {
        static final BooleanSupplierNever INSTANCE = new BooleanSupplierNever();

        @Override
        public boolean getAsBoolean() {
            return false;
        }
    }

    /** 空仓库：一律返回"未配置"。 */
    private static final class EmptyServerRepository implements DeployServerRepository {
        @Override
        public List<DeployServer> findAll() {
            return List.of();
        }

        @Override
        public Optional<DeployServer> find(String id) {
            return Optional.empty();
        }

        @Override
        public Optional<DeployServer> findDefault() {
            return Optional.empty();
        }

        @Override
        public DeployServer save(DeployServerView view, String secret, String updatedBy) {
            throw new UnsupportedOperationException("未配置服务器存储");
        }

        @Override
        public void delete(String id) {
            throw new UnsupportedOperationException("未配置服务器存储");
        }

        @Override
        public void markDefault(String id) {
            throw new UnsupportedOperationException("未配置服务器存储");
        }

        @Override
        public void recordTest(String id, boolean ok, String message, Long latencyMs) {
            // no-op
        }

        @Override
        public Optional<String> secretOf(String id) {
            return Optional.empty();
        }
    }
}
