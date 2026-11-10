package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 环境容器自愈的纯逻辑：必填变量 → 中间件类型、容器命名、docker run 参数。 */
class DockerEnvProvisionerTest {

    @Test
    void infersMiddlewareKindsFromRequiredKeys() {
        assertEquals("mysql", DockerEnvProvisioner.kindOfKey("DB_HOST"));
        assertEquals("mysql", DockerEnvProvisioner.kindOfKey("MYSQL_HOST"));
        assertEquals("redis", DockerEnvProvisioner.kindOfKey("REDIS_HOST"));
        assertEquals("rabbitmq", DockerEnvProvisioner.kindOfKey("RABBITMQ_HOST"));
        assertEquals("minio", DockerEnvProvisioner.kindOfKey("MINIO_ENDPOINT"));
        assertEquals("mongo", DockerEnvProvisioner.kindOfKey("MONGO_HOST"));
        assertEquals(null, DockerEnvProvisioner.kindOfKey("TOKEN_SECRET"));
    }

    @Test
    void containerNamesAreProjectScoped() {
        assertEquals("agent-test-ruoyiwnz-mysql",
                DockerEnvProvisioner.containerName("agent-test", "RUOYIWNZ", "mysql"));
        assertEquals("agent-dev-my-app-redis",
                DockerEnvProvisioner.containerName("agent-dev", "My App", "redis"));
    }

    @Test
    void mysqlRunArgvCarriesCredentialsAndIsolatedPort() {
        List<String> argv = DockerEnvProvisioner.runArgv("mysql", "agent-test-demo-mysql", 13306,
                "demo", "root", "s3cret", "agent-test-net");
        String line = String.join(" ", argv);
        assertTrue(line.contains("--name agent-test-demo-mysql"), line);
        assertTrue(line.contains("-p 127.0.0.1:13306:3306"), line);
        assertTrue(line.contains("--network agent-test-net"), line);
        assertTrue(line.contains("MYSQL_ROOT_PASSWORD=s3cret"), line);
        assertTrue(line.contains("MYSQL_DATABASE=demo"), line);
        assertTrue(line.endsWith("mysql:8.0"), line);
    }

    @Test
    void redisNeedsNoPasswordButStillGetsVolume() {
        List<String> argv = DockerEnvProvisioner.runArgv("redis", "agent-dev-demo-redis", 16379,
                null, "root", null, "agent-dev-net");
        String line = String.join(" ", argv);
        assertTrue(line.contains("redis:7-alpine"), line);
        assertFalse(line.contains("PASSWORD"), line);
        assertTrue(line.contains("agent-dev-demo-redis-data:/data"), line);
    }
}
