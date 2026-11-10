package top.rongx.agent.executor;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 部署失败信息不能带出环境变量值。 */
class DockerDeployerEnvTest {

    @Test
    @DisplayName("失败摘要里的 KEY=VALUE 被抹成 KEY=***")
    void sanitizesEnvValues() {
        String text = "docker run -d -e TOKEN_SECRET=super-secret -e DB_HOST=10.0.0.12 image";
        Map<String, String> env = Map.of(
                "TOKEN_SECRET", "super-secret",
                "DB_HOST", "10.0.0.12");

        String out = DockerDeployer.sanitize(text, env);

        assertFalse(out.contains("super-secret"), out);
        assertFalse(out.contains("10.0.0.12"), out);
        assertTrue(out.contains("TOKEN_SECRET=***"), out);
        assertTrue(out.contains("DB_HOST=***"), out);
    }
}
