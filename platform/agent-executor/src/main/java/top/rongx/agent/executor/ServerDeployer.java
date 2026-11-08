package top.rongx.agent.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.Deployer;

/**
 * 远程服务器部署器（SERVER）：镜像经 ssh/scp 推到远端 docker 运行。
 *
 * <p>配置（application.yml）：
 * <pre>
 * agent:
 *   deploy:
 *     server:
 *       host: 10.0.0.12        # 必填；为空则 SERVER 目标 fail-closed（E-505）
 *       port: 22
 *       user: deploy
 *       key-path: ~/.ssh/id_ed25519
 *       remote-dir: /opt/agent/deploy
 * </pre>
 *
 * <p>发布流程（全 argv 直执行，不经本地 shell）：
 * <ol>
 *   <li>{@code docker save -o <local>.tar <image>}</li>
 *   <li>{@code scp -P <port> -i <key> <local>.tar <user>@<host>:<remote-dir>/<project>.tar}</li>
 *   <li>{@code ssh … "docker load -i <remote-dir>/<project>.tar"}</li>
 *   <li>{@code ssh … "docker rm -f agent-deploy-<project>"}（失败忽略）</li>
 *   <li>{@code ssh … "docker run -d --name … -p host:container <image>"}</li>
 * </ol>
 *
 * <p>回滚：使用 {@link DeployRequest#previousImageRef()}（远端仍需留有该镜像；
 * 远端不自动清理镜像，避免 prune 掉回滚锚点）。
 */
@Component
public class ServerDeployer implements Deployer {

    private static final long SAVE_TIMEOUT_MS = 180_000;
    private static final long TRANSFER_TIMEOUT_MS = 180_000;
    private static final long SSH_TIMEOUT_MS = 90_000;

    private final ProcessExecutor executor = new ProcessExecutor();
    private final String serverHost;
    private final int serverPort;
    private final String serverUser;
    private final String keyPath;
    private final String remoteDir;

    public ServerDeployer(
            @Value("${agent.deploy.server.host:}") String serverHost,
            @Value("${agent.deploy.server.port:22}") int serverPort,
            @Value("${agent.deploy.server.user:deploy}") String serverUser,
            @Value("${agent.deploy.server.key-path:}") String keyPath,
            @Value("${agent.deploy.server.remote-dir:/opt/agent/deploy}") String remoteDir) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.serverUser = serverUser;
        this.keyPath = keyPath;
        this.remoteDir = remoteDir;
    }

    @Override
    public boolean supports(DeployTarget target) {
        return target == DeployTarget.SERVER;
    }

    @Override
    public DeployResult deploy(DeployRequest request) {
        if (serverHost == null || serverHost.isBlank()) {
            return DeployResult.failed(DeployTarget.SERVER, "E-505",
                    "未配置远程服务器：请设置 agent.deploy.server.host（见《发布编排与平台部署器》§4）",
                    "");
        }
        if (keyPath == null || keyPath.isBlank()) {
            return DeployResult.failed(DeployTarget.SERVER, "E-505",
                    "未配置 SSH 私钥：请设置 agent.deploy.server.key-path（免密部署必需）",
                    "");
        }
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
        String remoteTar = remoteDir + "/" + request.projectId() + ".tar";
        String container = DockerDeployer.containerName(request.projectId());
        StringBuilder log = new StringBuilder();
        BooleanSupplierNever never = new BooleanSupplierNever();

        try {
            Files.createDirectories(localTar.getParent());
        } catch (IOException e) {
            return DeployResult.failed(DeployTarget.SERVER, "E-501",
                    "无法创建暂存目录：" + e.getMessage(), "");
        }

        try {
            // 1. 本地导出镜像
            ProcessExecutor.StepOutcome save = executor.exec(workdir, List.of(
                    "docker", "save", "-o", localTar.toString(), image),
                    SAVE_TIMEOUT_MS, log, never);
            if (save.exitCode() != 0) {
                return DeployResult.failed(DeployTarget.SERVER, "E-501",
                        "docker save 失败（镜像是否存在？）：" + save.summary(), redact(log));
            }

            // 2. 传到远端
            ProcessExecutor.StepOutcome scp = executor.exec(workdir, List.of(
                    "scp", "-P", String.valueOf(serverPort),
                    "-i", expandHome(keyPath),
                    localTar.toString(),
                    serverUser + "@" + serverHost + ":" + remoteTar),
                    TRANSFER_TIMEOUT_MS, log, never);
            if (scp.exitCode() != 0) {
                return DeployResult.failed(DeployTarget.SERVER, "E-502",
                        "scp 传输失败：" + scp.summary(), redact(log));
            }

            // 3. 远端 load
            ProcessExecutor.StepOutcome load = ssh(request, workdir, log,
                    "docker load -i " + remoteTar);
            if (load.exitCode() != 0) {
                return DeployResult.failed(DeployTarget.SERVER, "E-502",
                        "远端 docker load 失败：" + load.summary(), redact(log));
            }

            // 4. 停旧容器（不存在时忽略）
            executor.exec(workdir, List.of("ssh", "-p", String.valueOf(serverPort),
                    "-i", expandHome(keyPath), serverUser + "@" + serverHost,
                    "docker rm -f " + container), SSH_TIMEOUT_MS, log, never);

            // 5. 起新容器
            String runCommand = "docker run -d --name " + container
                    + " --restart unless-stopped"
                    + " --label agent.project=" + request.projectId()
                    + " --label agent.sha=" + request.gitSha()
                    + " -p " + request.hostPort() + ":" + request.containerPort()
                    + " " + image;
            ProcessExecutor.StepOutcome run = ssh(request, workdir, log, runCommand);
            if (run.exitCode() != 0) {
                return DeployResult.failed(DeployTarget.SERVER, "E-502",
                        "远端容器启动失败：" + run.summary(), redact(log));
            }

            return DeployResult.ok(DeployTarget.SERVER,
                    "http://" + serverHost + ":" + request.hostPort() + safePath(request.healthPath()),
                    serverHost + ":" + container, redact(log));
        } finally {
            try {
                Files.deleteIfExists(localTar);
            } catch (IOException ignored) {
                // 暂存文件清理失败不影响部署结果
            }
        }
    }

    private ProcessExecutor.StepOutcome ssh(DeployRequest request, Path workdir,
                                            StringBuilder log, String remoteCommand) {
        return executor.exec(workdir, List.of(
                "ssh", "-p", String.valueOf(serverPort),
                "-i", expandHome(keyPath),
                "-o", "BatchMode=yes",
                "-o", "StrictHostKeyChecking=accept-new",
                serverUser + "@" + serverHost,
                remoteCommand), SSH_TIMEOUT_MS, log, new BooleanSupplierNever());
    }

    private String redact(StringBuilder log) {
        return ProcessExecutor.redact(log.toString());
    }

    private String expandHome(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        String home = System.getProperty("user.home");
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return Path.of(home, path.substring(2)).toString();
        }
        return path;
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
}
