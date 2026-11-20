package top.rongx.agent.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
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

    private final ProcessExecutor executor = new ProcessExecutor();
    private final DeployServerRepository servers;
    private final SshAccess ssh;

    public ServerDeployer(DeployServerRepository servers, SshAccess ssh) {
        this.servers = servers;
        this.ssh = ssh;
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
        } catch (RecipeException e) {
            return DeployResult.failed(DeployTarget.SERVER, e.code(), e.getMessage(), "");
        }

        // 版本槽：打 tag 的发布按版本号独立命名（不同补丁版本/不同版本线互不覆盖），
        // 不打 tag 才落到该环境的默认槽。远端以前固定用环境名，导致每次发布都删掉上一版。
        String slot = request.tagged() && request.version() != null && !request.version().isBlank()
                ? normalizeSlot(request.version()) : null;
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

        Path workdir = Path.of(request.workspace());
        Path localTar = workdir.resolve(".agent/deploy")
                .resolve(request.projectId() + "-" + shortSha(request.gitSha()) + ".tar");
        String remoteTar = remoteDir + "/" + request.projectId() + "-"
                + shortSha(request.gitSha()) + ".tar";
        String quotedTar = JschSsh.shellQuote(remoteTar);
        boolean uploaded = false;

        try {
            Files.createDirectories(localTar.getParent());
        } catch (IOException e) {
            return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                    "无法创建暂存目录：" + e.getMessage(), "");
        }

        try {
            ProcessExecutor.StepOutcome save = executor.exec(workdir, List.of(
                    "docker", "save", "-o", localTar.toString(), image),
                    SAVE_TIMEOUT_MS, log, BooleanSupplierNever.INSTANCE);
            if (save.exitCode() != 0) {
                return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                        "docker save 失败（镜像是否存在？）：" + save.summary()
                                + "；请重新执行 image_package 生成该版本", redact(log));
            }

            SshAccess.Result transfer = ssh.upload(server, secret, localTar, remoteTar,
                    TRANSFER_TIMEOUT_MS);
            if (!transfer.ok()) {
                return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_START_FAILED.code(),
                        "镜像传输失败：" + transfer.error()
                                + "（已尝试 SFTP 与 exec 上传；请检查远端磁盘空间与目录权限）",
                        redact(log));
            }
            uploaded = true;

            // 大镜像 load 比普通命令慢得多，单独预算；沿用 90s 会在生产大包上误判超时。
            SshAccess.Result load = ssh.run(server, secret,
                    docker + " load -i " + quotedTar, LOAD_TIMEOUT_MS);
            if (!load.ok()) {
                return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_START_FAILED.code(),
                        "远端 docker load 失败：" + load.error()
                                + "（请确认远端磁盘空间与 docker 权限）", redact(log));
            }

            // 只替换「同名同版本」的容器：打 tag 时容器名带版本号，
            // 其它版本（不同补丁/不同版本线）及默认槽的容器保持运行。
            int previousPort = slot == null
                    ? 0 : existingHostPort(server, secret, docker, container,
                    request.containerPort());
            ssh.run(server, secret, docker + " rm -f " + container, SSH_TIMEOUT_MS);
            log.append("[deploy] 已停止").append(slot == null ? "默认槽" : "版本 " + slot)
                    .append(" 的旧容器 ").append(container).append('\n');

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
                        runCommand(docker, image, request, container, hostPort), SSH_TIMEOUT_MS);
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
                return DeployResult.failed(DeployTarget.SERVER, ErrorCode.DEPLOY_START_FAILED.code(),
                        "远端容器启动失败：" + DockerDeployer.sanitize(reason, request.env()),
                        redact(log));
            }

            return DeployResult.ok(DeployTarget.SERVER,
                    "http://" + server.host() + ":" + hostPort
                            + safePath(request.healthPath()),
                    server.host() + ":" + container, redact(log));
        } finally {
            try {
                Files.deleteIfExists(localTar);
            } catch (IOException ignored) {
                // 暂存文件清理失败不影响部署结果
            }
            if (uploaded) {
                // 远端 tar 只在 load 时需要：不清理会在小磁盘服务器上越积越多，
                // 最终表现为下一次上传/load 随机失败。
                ssh.run(server, secret, "rm -f " + quotedTar, PROBE_TIMEOUT_MS);
            }
        }
    }

    /**
     * 探测远端 docker：优先普通用户，权限不足时尝试 {@code sudo -n}。
     *
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

    private static String runCommand(String docker, String image, DeployRequest request,
                                     String container, int hostPort) {
        StringBuilder command = new StringBuilder(docker)
                .append(" run -d --name ").append(container)
                .append(" --restart unless-stopped")
                .append(" --label agent.project=").append(JschSsh.shellQuote(request.projectId()))
                .append(" --label agent.env=").append(JschSsh.shellQuote(
                        request.envName() == null ? "" : request.envName()))
                .append(" --label agent.version=").append(JschSsh.shellQuote(displayVersion(request)))
                .append(" --label agent.sha=").append(JschSsh.shellQuote(request.gitSha()))
                .append(" --label agent.run=").append(JschSsh.shellQuote(
                        request.runId() == null ? "" : request.runId()))
                .append(" -p ").append(hostPort).append(':').append(request.containerPort());
        if (request.network() != null && !request.network().isBlank()) {
            command.append(" --network ").append(JschSsh.shellQuote(request.network().trim()));
        }
        command.append(remoteEnvArgs(request.env())).append(' ').append(image);
        return command.toString();
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

    /** 版本槽后缀：与 DockerDeployer 的容器命名规则保持一致。 */
    private static String normalizeSlot(String version) {
        String value = version == null ? "" : version.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "-");
        return value.isEmpty() ? null : value;
    }

    /** 展示版本：用户没填版本号时统一显示 latest。 */
    private static String displayVersion(DeployRequest request) {
        return request.version() == null || request.version().isBlank()
                ? "latest" : request.version().trim();
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "0000000";
        }
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    private static String safePath(String healthPath) {
        if (healthPath == null || healthPath.isBlank()) {
            return "/";
        }
        return healthPath.startsWith("/") ? healthPath : "/" + healthPath;
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
