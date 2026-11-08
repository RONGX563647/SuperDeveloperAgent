package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.Deployer;

/**
 * Docker 部署器：本机容器替换式发布。
 *
 * <pre>
 * docker rm -f agent-deploy-&lt;project&gt;      ← 停旧（不存在则忽略）
 * docker run -d --name … --restart unless-stopped \
 *   -p hostPort:containerPort &lt;image&gt;
 * </pre>
 *
 * <p>回滚 = 用上一版镜像再跑一遍同一套参数（因此镜像必须保留；
 * 清理策略由 {@code docker image prune} 之外的人工/定时任务负责）。
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
        if (request.imageRef() == null || request.imageRef().isBlank()) {
            return DeployResult.failed(DeployTarget.DOCKER, "E-501",
                    "DOCKER 目标需要镜像：请先执行 image_package，或检查 Dockerfile", "");
        }
        Path dir = Path.of(request.workspace() == null || request.workspace().isBlank()
                ? "." : request.workspace());
        String name = containerName(request.projectId());
        BooleanSupplier never = () -> false;

        // 1. 停旧（幂等：不存在时 docker 返回非 0，这里不视为失败）
        executor.exec(dir, List.of("docker", "rm", "-f", name), 30_000, log, never);

        // 2. 起新
        ProcessExecutor.StepOutcome run = executor.exec(dir, List.of(
                "docker", "run", "-d",
                "--name", name,
                "--restart", "unless-stopped",
                "--label", "agent.project=" + request.projectId(),
                "--label", "agent.run=" + request.runId(),
                "--label", "agent.sha=" + request.gitSha(),
                "-p", request.hostPort() + ":" + request.containerPort(),
                request.imageRef()), DOCKER_TIMEOUT_MS, log, never);

        if (run.exitCode() != 0) {
            return DeployResult.failed(DeployTarget.DOCKER, "E-502",
                    "容器启动失败：" + run.summary(), ProcessExecutor.redact(log.toString()));
        }
        return DeployResult.ok(DeployTarget.DOCKER,
                "http://127.0.0.1:" + request.hostPort() + safePath(request.healthPath()),
                name, ProcessExecutor.redact(log.toString()));
    }

    /** 平台托管的容器名：一个项目一个槽位（替换式发布），不受 runId 影响。 */
    public static String containerName(String projectId) {
        String normalized = projectId == null ? "unknown" : projectId.toLowerCase()
                .replaceAll("[^a-z0-9_.-]", "-");
        return "agent-deploy-" + normalized;
    }

    private static String safePath(String healthPath) {
        if (healthPath == null || healthPath.isBlank()) {
            return "/";
        }
        return healthPath.startsWith("/") ? healthPath : "/" + healthPath;
    }
}
