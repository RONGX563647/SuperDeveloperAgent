package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployTarget;

/**
 * SERVER 目标的远端网络就绪保证。
 *
 * <p>回归场景：prod 按版本部署复用 ci-test-220a8d2 时，本机网络已建好但远端没有，
 * 日志停在「已停止默认槽的旧容器」，实际失败原因是
 * {@code docker: Error response from daemon: network agent-prod-net not found}。
 * 过去只有 DOCKER 目标会 ensureNetwork，这里把同一语义补到两条 SERVER 路径。
 */
class ServerDeployerNetworkTest {

    private static final String NETWORK = "agent-prod-net";

    @Test
    @DisplayName("内置/空网络直接放行：不连远端、不创建")
    void builtInNetworkSkipsRemoteProbe() {
        for (String builtIn : Arrays.asList(null, "", "  ", "bridge", "host", "none")) {
            AtomicInteger calls = new AtomicInteger();
            boolean ok = ServerDeployer.ensureRemoteNetwork(builtIn, "远端环境",
                    () -> {
                        calls.incrementAndGet();
                        return false;
                    },
                    () -> {
                        calls.incrementAndGet();
                        return true;
                    }, new StringBuilder());
            assertTrue(ok, "内置网络应直接放行：" + builtIn);
            assertEquals(0, calls.get(), "内置网络不应触发 inspect/create：" + builtIn);
        }
        assertTrue(DockerNetwork.isBuiltIn("bridge"));
        assertFalse(DockerNetwork.isBuiltIn(NETWORK));
    }

    @Test
    @DisplayName("远端已有网络：inspect 命中即返回，不重复创建")
    void existingNetworkIsReused() {
        AtomicInteger creates = new AtomicInteger();
        StringBuilder log = new StringBuilder();
        boolean ok = ServerDeployer.ensureRemoteNetwork(NETWORK, "远端环境",
                () -> true,
                () -> {
                    creates.incrementAndGet();
                    return true;
                }, log);

        assertTrue(ok);
        assertEquals(0, creates.get());
        assertEquals("", log.toString());
    }

    @Test
    @DisplayName("远端缺网络：创建成功并写明确的「已创建远端环境网络」日志")
    void missingNetworkIsCreated() {
        StringBuilder log = new StringBuilder();
        boolean ok = ServerDeployer.ensureRemoteNetwork(NETWORK, "远端环境",
                () -> false, () -> true, log);

        assertTrue(ok);
        assertTrue(log.toString().contains("已创建远端环境网络 " + NETWORK), log.toString());
    }

    @Test
    @DisplayName("网络不存在且创建失败：返回 false，日志保留失败事实供上层给出可操作报错")
    void failedCreateReportsReason() {
        StringBuilder log = new StringBuilder();
        boolean ok = ServerDeployer.ensureRemoteNetwork(NETWORK, "远端环境",
                () -> false, () -> false, log);

        assertFalse(ok);
        assertTrue(log.toString().contains("远端环境网络 " + NETWORK + " 创建失败"), log.toString());
    }

    @Test
    @DisplayName("SSH 路径命令：docker 前缀保留，网络名按 shell 规则转义")
    void sshNetworkCommandShape() {
        assertEquals("sudo docker network inspect 'agent-prod-net'",
                ServerDeployer.networkCommand("sudo docker", "inspect", NETWORK));
        assertEquals("docker network create 'agent-prod-net'",
                ServerDeployer.networkCommand("docker", "create", NETWORK));
    }

    @Test
    @DisplayName("context 路径命令：--context 在 network 子命令之前")
    void contextNetworkArgvShape() {
        List<String> inspect = ServerDeployer.contextNetworkArgv("docker", "agent-srv-1",
                "inspect", NETWORK);
        List<String> create = ServerDeployer.contextNetworkArgv("docker", "agent-srv-1",
                "create", NETWORK);

        assertEquals(List.of("docker", "--context", "agent-srv-1", "network", "inspect", NETWORK),
                inspect);
        assertEquals("create", create.get(4));
    }

    @Test
    @DisplayName("ensure 不改变 docker run 原有 --network 参数")
    void runStillPassesNetworkFlag() {
        DeployRequest req = new DeployRequest("p1", "run-1", "sha1234567", "ci-abcdef1",
                null, null, "E:/ws", DeployTarget.SERVER, 8081, 8080, "/healthz", null, false,
                "srv-1", Map.of(), NETWORK, "prod", "0.1.0", false, null);

        List<String> argv = ServerDeployer.contextArgv("docker", "agent-srv-1", "ci-abcdef1",
                req, "agent-deploy-p1-prod", 8081, "0.1.0", null);
        int at = argv.indexOf("--network");

        assertTrue(at >= 0, "docker run 必须继续带 --network：" + argv);
        assertEquals(NETWORK, argv.get(at + 1));
    }
}
