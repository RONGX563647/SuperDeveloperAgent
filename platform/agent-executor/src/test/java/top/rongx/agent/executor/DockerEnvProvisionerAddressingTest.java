package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.env.EnvResource;

/** dev 是本机模式，端点必须写宿主机可达的 127.0.0.1 + 发布端口；test/prod 才用容器名。 */
class DockerEnvProvisionerAddressingTest {

    private static EnvResource redis() {
        return new EnvResource("r1", "dev", "redis", "agent-dev-ruoyiwnz-redis",
                "127.0.0.1", 16379, 6379, "agent-dev-ruoyiwnz-redis",
                null, null, null, "dev_default", null, "LOCAL_DOCKER", true, 0, null);
    }

    @Test
    void devUsesPublishedHostPort() {
        Map<String, String> values = DockerEnvProvisioner.derive(redis(), false);
        assertEquals("127.0.0.1", values.get("REDIS_HOST"));
        assertEquals("16379", values.get("REDIS_PORT"));
    }

    @Test
    void testUsesContainerNameAndPort() {
        Map<String, String> values = DockerEnvProvisioner.derive(redis(), true);
        assertEquals("agent-dev-ruoyiwnz-redis", values.get("REDIS_HOST"));
        assertEquals("6379", values.get("REDIS_PORT"));
    }
}
