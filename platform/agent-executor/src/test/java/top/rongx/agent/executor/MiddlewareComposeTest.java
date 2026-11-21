package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 中间件 compose 生成器。
 *
 * <p>守住的回归：手拼 {@code docker run} 时加固参数"漏了就是漏了"（中间件容器曾长期
 * 零 {@code security-opt} / 零 {@code cap-drop}）。改成声明式文件后，加固是结构的一部分，
 * 而且 {@code up --wait} 让就绪判定交给 healthcheck，不再自造轮询。
 */
class MiddlewareComposeTest {

    private static MiddlewareCompose.Service mysql(int port) {
        return new MiddlewareCompose.Service("mysql", "agent-dev-demo-mysql", "mysql:8.0", port,
                "agent-dev-demo-mysql-data", "/var/lib/mysql",
                Map.of("MYSQL_ROOT_PASSWORD", "s3cret", "MYSQL_DATABASE", "demo"), List.of());
    }

    private static MiddlewareCompose.Service redis(int port) {
        return new MiddlewareCompose.Service("redis", "agent-dev-demo-redis", "redis:7-alpine", port,
                "agent-dev-demo-redis-data", "/data", Map.of(),
                List.of("redis-server", "--requirepass", "p@ss"));
    }

    @Test
    @DisplayName("生成的文件必须含加固字段：cap_drop ALL + no-new-privileges")
    void generatedFileIsHardened() {
        String yaml = MiddlewareCompose.render("agent-dev", "agent-dev-net", List.of(mysql(13306)));

        assertTrue(yaml.contains("cap_drop:"), yaml);
        assertTrue(yaml.contains("- ALL"), yaml);
        assertTrue(yaml.contains("no-new-privileges:true"), yaml);
        assertFalse(yaml.contains("privileged"), yaml);
    }

    @Test
    @DisplayName("端口只绑回环，容器名与平台记录一致（否则环境页找不到容器）")
    void portsAndNamesAreStable() {
        String yaml = MiddlewareCompose.render("agent-dev", "agent-dev-net",
                List.of(mysql(13306), redis(16379)));

        assertTrue(yaml.contains("container_name: agent-dev-demo-mysql"), yaml);
        assertTrue(yaml.contains("container_name: agent-dev-demo-redis"), yaml);
        assertTrue(yaml.contains("\"127.0.0.1:13306:3306\""), yaml);
        assertTrue(yaml.contains("\"127.0.0.1:16379:6379\""), yaml);
        assertFalse(yaml.contains("0.0.0.0"), yaml);
    }

    @Test
    @DisplayName("数据卷按服务独立；环境变量按 key 排序（同输入同文件）")
    void volumesAndEnvAreDeterministic() {
        String first = MiddlewareCompose.render("agent-dev", "agent-dev-net", List.of(mysql(13306)));
        String second = MiddlewareCompose.render("agent-dev", "agent-dev-net", List.of(mysql(13306)));

        assertEquals(first, second, "同样输入必须产生同样文件（否则每次 diff 都有噪声）");
        assertTrue(first.contains("agent-dev-demo-mysql-data:/var/lib/mysql"), first);
        // MYSQL_DATABASE 在 MYSQL_ROOT_PASSWORD 之前（字典序）
        assertTrue(first.indexOf("MYSQL_DATABASE") < first.indexOf("MYSQL_ROOT_PASSWORD"), first);
    }

    @Test
    @DisplayName("redis 的密码走 command 数组，不进环境变量")
    void redisPasswordGoesToCommand() {
        String yaml = MiddlewareCompose.render("agent-dev", "agent-dev-net", List.of(redis(16379)));

        assertTrue(yaml.contains("command: [\"redis-server\", \"--requirepass\", \"p@ss\"]"), yaml);
        assertFalse(yaml.contains("environment:"), "redis 不该有环境变量段：" + yaml);
    }

    @Test
    @DisplayName("网络声明为 external：由 provisioner 的 ensureNetwork 负责创建")
    void networkIsExternal() {
        String yaml = MiddlewareCompose.render("agent-dev", "agent-dev-net", List.of(mysql(13306)));

        assertTrue(yaml.contains("networks:"), yaml);
        assertTrue(yaml.contains("external: true"), yaml);
    }

    @Test
    @DisplayName("up 命令必须带 --wait 与超时：否则 unhealthy 会让部署无限挂住")
    void upWaitsWithTimeout() {
        List<String> argv = MiddlewareCompose.upArgv(DockerCli.defaults(),
                ".agent/env/dev/docker-compose.yml", "agent-dev", 120);
        String joined = String.join(" ", argv);

        assertTrue(joined.contains("up -d --wait"), joined);
        assertTrue(joined.contains("--wait-timeout 120"), joined);
        assertEquals(List.of("docker", "compose", "version", "--short"),
                MiddlewareCompose.versionArgv(DockerCli.defaults()));
    }

    @Test
    @DisplayName("容器内端口与 DockerEnvProvisioner.defaultPort 同源，不会两处漂移")
    void internalPortMatchesProvisioner() {
        for (String kind : List.of("mysql", "redis", "rabbitmq", "minio", "postgres", "mongo")) {
            assertEquals(DockerEnvProvisioner.defaultPort(kind),
                    MiddlewareCompose.defaultInternalPort(kind), kind);
        }
    }

    @Test
    @DisplayName("compose 与单容器路径共用同一份环境变量/命令构造")
    void envAndCommandSharedWithSingleContainerPath() {
        DockerEnvProvisioner.Creds creds = new DockerEnvProvisioner.Creds("demo", "root", "s3cret");

        assertEquals(Map.of("MYSQL_ROOT_PASSWORD", "s3cret", "MYSQL_DATABASE", "demo"),
                DockerEnvProvisioner.middlewareEnv("mysql", creds));
        assertTrue(DockerEnvProvisioner.middlewareCommand("mysql", creds).isEmpty());
        assertEquals(List.of("redis-server", "--requirepass", "s3cret"),
                DockerEnvProvisioner.middlewareCommand("redis", creds));
        // 无密码的 redis 不带 command
        assertTrue(DockerEnvProvisioner.middlewareCommand("redis",
                new DockerEnvProvisioner.Creds(null, "root", null)).isEmpty());
    }
}
