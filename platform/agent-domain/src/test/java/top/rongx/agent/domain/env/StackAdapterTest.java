package top.rongx.agent.domain.env;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 引用判定必须同时认「宿主机地址 / 容器内网名 / 资源名」，否则 dev 的中间件会被判成未引用。 */
class StackAdapterTest {

    private static EnvResource redis() {
        return new EnvResource("r1", "dev", "redis", "agent-dev-ruoyiwnz-redis",
                "127.0.0.1", 16379, 6379, "agent-dev-ruoyiwnz-redis",
                null, null, null, "dev_default", null, "LOCAL_DOCKER", true, 0, null);
    }

    @Test
    void referencedByInternalHost() {
        assertTrue(StackAdapter.isReferenced(redis(),
                Map.of("REDIS_HOST", "agent-dev-ruoyiwnz-redis", "REDIS_PORT", "6379")));
    }

    @Test
    void referencedByHost() {
        assertTrue(StackAdapter.isReferenced(redis(),
                Map.of("REDIS_HOST", "127.0.0.1", "REDIS_PORT", "16379")));
    }

    @Test
    void hostMatchRequiresSamePort() {
        // 同环境多个实例共用 127.0.0.1，端口不同就不是同一个
        assertFalse(StackAdapter.isReferenced(redis(),
                Map.of("REDIS_HOST", "127.0.0.1", "REDIS_PORT", "16380")));
    }

    @Test
    void referencedByName() {
        assertTrue(StackAdapter.isReferenced(redis(),
                Map.of("REDIS_HOST", "AGENT-DEV-RUOYIWNZ-REDIS")));
    }

    @Test
    void notReferencedByUnrelatedHost() {
        assertFalse(StackAdapter.isReferenced(redis(), Map.of("REDIS_HOST", "111.230.60.71")));
    }

    @Test
    void minioEndpointMatchesAnyAddress() {
        EnvResource minio = new EnvResource("m1", "dev", "minio", "minio",
                "127.0.0.1", 19000, 9000, "agent-dev-minio",
                null, "ak", "sk", "dev_default", null, "LOCAL_DOCKER", true, 0, null);
        assertTrue(StackAdapter.isReferenced(minio,
                Map.of("MINIO_ENDPOINT", "http://agent-dev-minio:9000")));
        assertFalse(StackAdapter.isReferenced(minio,
                Map.of("MINIO_ENDPOINT", "http://10.0.0.8:9000")));
    }
}
