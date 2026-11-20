package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeploySlot;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @DisplayName("回滚预检：镜像名为空时直接判定不存在，不触发 docker 调用")
    void imageExistsRejectsBlankImage() {
        DockerDeployer deployer = new DockerDeployer();
        assertFalse(deployer.imageExists(Path.of("."), ""));
        assertFalse(deployer.imageExists(Path.of("."), " "));
        assertFalse(deployer.imageExists(Path.of("."), null));
    }

    @Test
    @DisplayName("容器名按项目归一化：一个项目一个槽位（替换式发布）")
    void containerNameIsStablePerProject() {
        assertEquals("agent-deploy-ry", DockerDeployer.containerName("RY"));
        assertEquals("agent-deploy-my-proj", DockerDeployer.containerName("My Proj"));
        assertEquals("agent-deploy-unknown", DockerDeployer.containerName(null));
    }

    @Test
    @DisplayName("单容器名按环境隔离：dev 与 test 互不覆盖；env 为空退化为旧命名")
    void containerNameIsScopedByEnv() {
        assertEquals("agent-deploy-ry-dev", DockerDeployer.containerName("RY", "dev"));
        assertEquals("agent-deploy-ry-test", DockerDeployer.containerName("RY", "test"));
        assertEquals("agent-deploy-ry", DockerDeployer.containerName("RY", null));
        assertEquals("agent-deploy-ry", DockerDeployer.containerName("RY", " "));
    }

    @Test
    @DisplayName("远端端口探测：/proc/net/tcp 十六进制端口与 LISTEN 状态解析")
    void parsesProcNetPorts() {
        String proc = """
                  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt
                   0: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000
                   1: 0100007F:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000
                   2: 00000000000000000000000001000000:0050 00000000000000000000000000000000:0000 0A
                   3: 00000000:1F91 00000000:0000 01 00000000:00000000 00:00000000 00000000
                """;
        assertEquals(Set.of(8080, 22, 80), ServerDeployer.parseProcNetPorts(proc));
    }

    @Test
    @DisplayName("远端端口探测：ss/netstat 输出按地址列解析（兼容 IPv6 / *）")
    void parsesSsNetstatPorts() {
        String text = """
                State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process
                LISTEN 0      128    0.0.0.0:8080        0.0.0.0:*
                LISTEN 0      128       [::]:22             [::]:*
                LISTEN 0      128          *:443              *:*
                """;
        assertEquals(Set.of(8080, 22, 443), ServerDeployer.parseListeningPorts(text));
    }

    @Test
    @DisplayName("端口选择：跳过已占用，探测不可用时信任期望端口")
    void picksFreePort() {
        assertEquals(8082, ServerDeployer.firstFreePort(8080, Set.of(8080, 8081)));
        assertEquals(8080, ServerDeployer.firstFreePort(8080, null));
        assertEquals(8081, ServerDeployer.nextFreePort(8080, Set.of(8080)));
        assertEquals(-1, ServerDeployer.nextFreePort(65535, Set.of(65535)));
    }

    @Test
    @DisplayName("docker 端口冲突识别：覆盖常见措辞，普通启动失败不误判")
    void recognizesPortConflicts() {
        assertTrue(ServerDeployer.portConflict(
                "Bind for 0.0.0.0:8080 failed: port is already allocated"));
        assertTrue(ServerDeployer.portConflict(
                "docker: Error response from daemon: address already in use."));
        assertFalse(ServerDeployer.portConflict("No such image: ci-abcdef1"));
    }

    @Test
    @DisplayName("远端 shell 转义：空格/引号不会把命令拆开")
    void quotesRemoteShellArguments() {
        assertEquals("'/opt/agent/my app'", JschSsh.shellQuote("/opt/agent/my app"));
        assertEquals("'a'\\''b'", JschSsh.shellQuote("a'b"));
        assertEquals("''", JschSsh.shellQuote(""));
    }

    @Test
    @DisplayName("版本槽容器名：打 tag 的不同版本互不覆盖，默认槽仍按环境隔离")
    void versionSlotContainerNamesDoNotCollide() {
        String prod = DockerDeployer.containerName("RY", "prod");
        String v1 = DockerDeployer.containerName("RY", "prod", "0.1.0");
        String v2 = DockerDeployer.containerName("RY", "prod", "0.1.1");
        assertEquals(prod + "-0.1.0", v1);
        assertEquals(prod + "-0.1.1", v2);
        assertFalse(v1.equals(v2), "两个补丁版本必须落在不同容器名（不互相删除）");
        assertEquals(prod, DockerDeployer.containerName("RY", "prod", null));
    }

    @Test
    @DisplayName("版本槽规则：打 tag 用版本号，空版本不伪造共享槽，默认槽为 null")
    void deploySlotRules() {
        assertEquals("0.1.0", DeploySlot.of(true, "0.1.0"));
        assertEquals("0.1.0", DeploySlot.of(true, " 0.1.0 "));
        assertEquals("1-2-0", DeploySlot.of(true, "1/2/0"));
        assertNull(DeploySlot.of(true, " "));
        assertNull(DeploySlot.of(true, null));
        assertNull(DeploySlot.of(false, "0.1.0"));
    }

    @Test
    @DisplayName("远端 docker port 解析：IPv4/IPv6 映射都能取到宿主端口")
    void parseMappedPortHandlesIpv4AndIpv6() {
        assertEquals(18080, ServerDeployer.parseMappedPort("0.0.0.0:18080"));
        assertEquals(18080, ServerDeployer.parseMappedPort("[::]:18080"));
        assertEquals(18081, ServerDeployer.parseMappedPort("0.0.0.0:18081\n[::]:18081"));
        assertEquals(0, ServerDeployer.parseMappedPort(""));
        assertEquals(0, ServerDeployer.parseMappedPort(null));
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
