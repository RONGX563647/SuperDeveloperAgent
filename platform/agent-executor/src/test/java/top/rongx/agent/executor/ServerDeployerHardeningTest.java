package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.settings.DeployServer;

/**
 * 远端容器的加固与端口绑定。
 *
 * <p>守住的回归（G5）：中间件与远端容器曾是整条链路里唯一"零安全参数"的容器，
 * 而且 {@code -p hostPort:containerPort} 绑 0.0.0.0、界面却回报
 * {@code http://host:port} —— 暴露面与呈现相反。
 */
class ServerDeployerHardeningTest {

    private static DeployRequest request(Map<String, String> env) {
        return new DeployRequest("p1", "run-1", "sha1234567", "ci-abcdef1", null, null,
                "E:/ws", DeployTarget.SERVER, 8081, 8080, "/healthz", null, false,
                "srv-1", env, null, "prod", "0.1.0", false, null);
    }

    @Test
    @DisplayName("远端 docker run 与本地应用容器同一套加固：no-new-privileges + cap-drop ALL")
    void remoteRunIsHardened() {
        List<String> flags = ServerDeployer.hardeningFlags(8081, 8080);
        String joined = String.join(" ", flags);

        assertTrue(joined.contains("--security-opt no-new-privileges"), joined);
        assertTrue(joined.contains("--cap-drop ALL"), joined);
        assertFalse(joined.contains("--privileged"), joined);
        assertFalse(joined.contains("--cap-add"), joined);
    }

    @Test
    @DisplayName("宿主端口只绑回环，与回报给用户的地址语义一致")
    void publishedOnLoopbackOnly() {
        List<String> flags = ServerDeployer.hardeningFlags(8081, 8080);
        String publish = flags.get(flags.indexOf("-p") + 1);

        assertEquals("127.0.0.1:8081:8080", publish, "不能绑 0.0.0.0：" + publish);
        assertEquals("127.0.0.1", ServerDeployer.bindHost());
    }

    @Test
    @DisplayName("--context 模式的 argv：全局选项在子命令之前，且加固参数完全一致")
    void contextArgvPutsGlobalFlagFirst() {
        DeployRequest req = request(Map.of("SPRING_PROFILES_ACTIVE", "prod", "DB_URL", "jdbc:x"));
        List<String> argv = ServerDeployer.contextArgv("docker", "agent-srv-1", "ci-abcdef1",
                req, "agent-deploy-p1-prod", 8081, "0.1.0", null);
        String joined = String.join(" ", argv);

        assertEquals("docker", argv.get(0));
        assertEquals("--context", argv.get(1), "--context 必须在子命令前：" + argv);
        assertEquals("agent-srv-1", argv.get(2));
        assertEquals("run", argv.get(3));
        assertTrue(joined.contains("--security-opt no-new-privileges"), joined);
        assertTrue(joined.contains("--cap-drop ALL"), joined);
        // 环境变量全部经 -e 传入，且按 key 排序（同输入产生同命令）
        assertTrue(argv.contains("DB_URL=jdbc:x"), joined);
        assertTrue(argv.contains("SPRING_PROFILES_ACTIVE=prod"), joined);
        assertTrue(argv.indexOf("DB_URL=jdbc:x")
                < argv.indexOf("SPRING_PROFILES_ACTIVE=prod"), "环境变量要排序：" + argv);
        assertEquals("ci-abcdef1", argv.get(argv.size() - 1), "镜像必须是最后一个参数：" + argv);
    }

    @Test
    @DisplayName("env-file 模式不把值写进 argv（密钥不进命令行）")
    void envFileKeepsSecretsOutOfArgv() {
        DeployRequest req = request(Map.of("DB_PASSWORD", "s3cr3t-value"));
        List<String> argv = ServerDeployer.contextArgv("docker", "ctx", "img:1.0", req,
                "c1", 8081, "0.1.0", "/remote/x.env");
        String joined = String.join(" ", argv);

        assertTrue(joined.contains("--env-file /remote/x.env"), joined);
        assertFalse(joined.contains("s3cr3t-value"), "密钥不能出现在命令行：" + joined);
    }

    @Test
    @DisplayName("context 名按 server.id 生成：同一主机两条记录不会互相覆盖")
    void contextNameIsPerServerRecord() {
        assertEquals("agent-srv-1", ServerDeployer.contextName(server("srv-1")));
        assertEquals("agent-srv-2", ServerDeployer.contextName(server("srv-2")));
        // 非法字符被规范化，避免 docker context 名非法
        assertEquals("agent-my-srv", ServerDeployer.contextName(server("My Srv")));
        assertEquals("agent-default", ServerDeployer.contextName(server("")));
        assertEquals("agent-default", ServerDeployer.contextName(null));
    }

    private static DeployServer server(String id) {
        return new DeployServer(id, "n", "10.0.0.1", 22, "root", "KEY", "/k", "/opt/agent",
                true, true, false, null, null, null, null, "u", 0L);
    }
}
