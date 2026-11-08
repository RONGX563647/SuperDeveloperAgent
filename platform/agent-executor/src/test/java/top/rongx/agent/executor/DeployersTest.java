package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署器契约测试（不依赖真实 Docker/SSH）：覆盖 fail-closed 与幂等命名。
 */
class DeployersTest {

    private static DeployRequest request(DeployTarget target, String image) {
        return new DeployRequest("RY", "run-x", "abcdef1234567", image, null, null,
                Path.of(".").toAbsolutePath().toString(), target, 8080, 8080, "/healthz",
                null, false, null);
    }

    /** 内存仓库：可注入一台服务器（或不注入）。 */
    private static DeployServerRepository repo(DeployServer server) {
        return new DeployServerRepository() {
            @Override
            public List<DeployServer> findAll() {
                return server == null ? List.of() : List.of(server);
            }

            @Override
            public Optional<DeployServer> find(String id) {
                return server != null && server.id().equals(id) ? Optional.of(server) : Optional.empty();
            }

            @Override
            public Optional<DeployServer> findDefault() {
                return Optional.ofNullable(server);
            }

            @Override
            public DeployServer save(DeployServerView view, String secret, String updatedBy) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void delete(String id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void markDefault(String id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void recordTest(String id, boolean ok, String message, Long latencyMs) {
                // no-op
            }

            @Override
            public Optional<String> secretOf(String id) {
                return Optional.empty();
            }
        };
    }

    private static DeployServer server(String id, String keyPath) {
        return new DeployServer(id, "测试服务器", "10.0.0.12", 22, "deploy", "KEY",
                keyPath, "/opt/agent/deploy", true, true, false, null,
                null, null, null, "t", System.currentTimeMillis());
    }

    @Test
    @DisplayName("未配置服务器 → SERVER fail-closed（E-505），提示去设置页")
    void serverFailsClosedWhenUnconfigured() {
        ServerDeployer deployer = new ServerDeployer(repo(null), new SshExec());
        DeployResult result = deployer.deploy(request(DeployTarget.SERVER, "ci-abcdef1"));
        assertFalse(result.success());
        assertEquals("E-505", result.errorCode());
        assertTrue(result.summary().contains("设置"), result.summary());
    }

    @Test
    @DisplayName("配置了服务器但缺私钥 → E-505，提示补 keyPath")
    void serverRequiresKey() {
        ServerDeployer deployer = new ServerDeployer(repo(server("srv-1", "")), new SshExec());
        DeployResult result = deployer.deploy(request(DeployTarget.SERVER, "ci-abcdef1"));
        assertFalse(result.success());
        assertEquals("E-505", result.errorCode());
        assertTrue(result.summary().contains("私钥"), result.summary());
    }

    @Test
    @DisplayName("回滚缺上一版镜像 → E-507")
    void serverRollbackNeedsPrevious() {
        ServerDeployer deployer = new ServerDeployer(
                repo(server("srv-1", "~/.ssh/id_ed25519")), new SshExec());
        DeployRequest rollback = new DeployRequest("RY", "run-x", "abcdef1234567", null,
                null, null, Path.of(".").toAbsolutePath().toString(), DeployTarget.SERVER,
                8080, 8080, "/healthz", null, true, null);
        DeployResult result = deployer.deploy(rollback);
        assertFalse(result.success());
        assertEquals("E-507", result.errorCode());
    }

    @Test
    @DisplayName("Docker 目标无镜像 → E-501（部署前就知道，而不是起容器才炸）")
    void dockerRequiresImage() {
        DockerDeployer deployer = new DockerDeployer();
        DeployResult result = deployer.deploy(request(DeployTarget.DOCKER, ""));
        assertFalse(result.success());
        assertEquals("E-501", result.errorCode());
    }

    @Test
    @DisplayName("容器名按项目归一化：一个项目一个槽位（替换式发布）")
    void containerNameIsStablePerProject() {
        assertEquals("agent-deploy-ry", DockerDeployer.containerName("RY"));
        assertEquals("agent-deploy-my-proj", DockerDeployer.containerName("My Proj"));
        assertEquals("agent-deploy-unknown", DockerDeployer.containerName(null));
    }

    @Test
    @DisplayName("注册表按目标分派；未知目标 fail-closed")
    void registryDispatchesByTarget() {
        Deployers deployers = Deployers.builtin();
        assertTrue(deployers.supports(DeployTarget.DOCKER));
        assertTrue(deployers.supports(DeployTarget.HOST));
        assertTrue(deployers.supports(DeployTarget.SERVER));
        DeployResult docker = deployers.deploy(request(DeployTarget.DOCKER, ""));
        assertEquals("E-501", docker.errorCode());
    }
}
