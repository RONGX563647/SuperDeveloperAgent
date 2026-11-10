package top.rongx.agent.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.Deployer;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;
import top.rongx.agent.domain.settings.SshAccess;

/**
 * 远程服务器部署器（SERVER）：镜像经 ssh/scp 推到远端 docker 运行。
 *
 * <p>配置来源：设置页维护的 {@code deploy_servers}（替代 application.yml）。
 * 选择顺序：运行参数 {@code serverId} → 默认服务器 → E-505（提示去设置页添加）。
 */
public class ServerDeployer implements Deployer {

    private static final long SAVE_TIMEOUT_MS = 180_000;
    private static final long TRANSFER_TIMEOUT_MS = 180_000;
    private static final long SSH_TIMEOUT_MS = 90_000;

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
            return DeployResult.failed(DeployTarget.SERVER, "E-505",
                    "还没有可用的部署服务器：请到「设置 → 服务器」添加并设为默认", "");
        }
        DeployServer server = found.get();
        if (!server.enabled()) {
            return DeployResult.failed(DeployTarget.SERVER, "E-505",
                    "服务器「" + server.name() + "」已被停用", "");
        }
        if ("KEY".equalsIgnoreCase(server.authType())
                && (server.keyPath() == null || server.keyPath().isBlank())) {
            return DeployResult.failed(DeployTarget.SERVER, "E-505",
                    "服务器「" + server.name() + "」未配置 SSH 私钥路径", "");
        }
        String secret = servers.secretOf(server.id()).orElse(null);

        String image = request.rollback() ? request.previousImageRef() : request.imageRef();
        if (image == null || image.isBlank()) {
            return DeployResult.failed(DeployTarget.SERVER, request.rollback() ? "E-507" : "E-501",
                    request.rollback()
                            ? "没有可回滚的上一版镜像" : "SERVER 目标需要镜像：请先执行 image_package",
                    "");
        }

        Path workdir = Path.of(request.workspace());
        Path localTar = workdir.resolve(".agent/deploy")
                .resolve(request.projectId() + "-" + shortSha(request.gitSha()) + ".tar");
        String remoteTar = server.remoteDir() + "/" + request.projectId() + ".tar";
        String container = DockerDeployer.containerName(request.projectId(), request.envName());
        StringBuilder log = new StringBuilder();
        BooleanSupplierNever never = new BooleanSupplierNever();

        try {
            Files.createDirectories(localTar.getParent());
        } catch (IOException e) {
            return DeployResult.failed(DeployTarget.SERVER, "E-501",
                    "无法创建暂存目录：" + e.getMessage(), "");
        }

        try {
            ProcessExecutor.StepOutcome save = executor.exec(workdir, List.of(
                    "docker", "save", "-o", localTar.toString(), image),
                    SAVE_TIMEOUT_MS, log, never);
            if (save.exitCode() != 0) {
                return DeployResult.failed(DeployTarget.SERVER, "E-501",
                        "docker save 失败（镜像是否存在？）：" + save.summary(), redact(log));
            }

            SshAccess.Result scp = ssh.upload(server, secret, localTar, remoteTar,
                    TRANSFER_TIMEOUT_MS);
            if (!scp.ok()) {
                return DeployResult.failed(DeployTarget.SERVER, "E-502",
                        "scp 传输失败：" + scp.error(), redact(log));
            }

            SshAccess.Result load = ssh.run(server, secret,
                    "docker load -i " + remoteTar, SSH_TIMEOUT_MS);
            if (!load.ok()) {
                return DeployResult.failed(DeployTarget.SERVER, "E-502",
                        "远端 docker load 失败：" + load.error(), redact(log));
            }

            ssh.run(server, secret, "docker rm -f " + container, SSH_TIMEOUT_MS);

            // 远端端口预检：ss + docker ps，冲突时在期望端口之后续找；不杀未知进程。
            int hostPort = allocateRemotePort(server, secret, request.hostPort());
            if (hostPort < 0) {
                return DeployResult.failed(DeployTarget.SERVER, "E-704",
                        "远端端口耗尽：从 " + request.hostPort() + " 起 200 个端口都被占用", redact(log));
            }
            if (hostPort != request.hostPort()) {
                log.append("[port] 远端 ").append(request.hostPort())
                        .append(" 被占用 → 分配 ").append(hostPort).append('\n');
            }

            String runCommand = "docker run -d --name " + container
                    + " --restart unless-stopped"
                    + " --label agent.project=" + request.projectId()
                    + " --label agent.sha=" + request.gitSha()
                    + " -p " + hostPort + ":" + request.containerPort()
                    + (request.network() == null || request.network().isBlank()
                            ? "" : " --network " + request.network().trim())
                    + remoteEnvArgs(request.env())
                    + " " + image;
            SshAccess.Result run = ssh.run(server, secret, runCommand, SSH_TIMEOUT_MS);
            if (!run.ok()) {
                return DeployResult.failed(DeployTarget.SERVER, "E-502",
                        "远端容器启动失败：" + DockerDeployer.sanitize(run.error(), request.env()),
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
        }
    }

    /**
     * 远端端口分配（只读探测）：ss 监听 + docker 端口映射任一占用即视为不可用。
     *
     * @return 可用端口；200 个都不可用返回 -1
     */
    private int allocateRemotePort(DeployServer server, String secret, int desired) {
        for (int port = Math.max(1, desired); port <= desired + 200; port++) {
            if (remotePortFree(server, secret, port)) {
                return port;
            }
        }
        return -1;
    }

    private boolean remotePortFree(DeployServer server, String secret, int port) {
        SshAccess.Result listening = ssh.run(server, secret,
                "ss -ltn 2>/dev/null | awk '{print $4}' | grep -E '[:.]" + port + "$' || true",
                SSH_TIMEOUT_MS);
        if (!listening.ok()) {
            // 探测失败时按"占用"处理（fail-closed），避免盲目抢端口
            return false;
        }
        if (!listening.output().trim().isEmpty()) {
            return false;
        }
        SshAccess.Result mapped = ssh.run(server, secret,
                "docker ps --format '{{.Ports}}' | grep -E ':" + port + "->' || true",
                SSH_TIMEOUT_MS);
        return mapped.ok() && mapped.output().trim().isEmpty();
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
            sb.append(" -e '").append(pair.replace("'", "'\\''")).append('\'');
        }
        return sb.toString();
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
