package top.rongx.agent.gateway.web;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.domain.port.PipelineEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gitee webhook 的鉴权、去重与触发（不依赖 HTTP 容器）。 */
class GiteeWebhookControllerTest {

    private static final String BODY = """
            {"after":"abcdef1234567890abcdef1234567890abcdef12",
             "project":{"path_with_namespace":"tester/RY"}}
            """;

    private static class StubEngine implements PipelineEngine {
        int calls = 0;
        String projectId;
        String pipelineId;
        String sha;

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public List<String> pipelines(String projectId) {
            return List.of("verify");
        }

        @Override
        public String trigger(String projectId, String pipelineId, Map<String, String> params) {
            calls++;
            this.projectId = projectId;
            this.pipelineId = pipelineId;
            this.sha = params.get("gitSha");
            return "run-1";
        }

        @Override
        public top.rongx.agent.domain.run.StageStatus status(String runId) {
            return top.rongx.agent.domain.run.StageStatus.RUNNING;
        }

        @Override
        public List<top.rongx.agent.domain.run.StageResult> stages(String runId) {
            return List.of();
        }

        @Override
        public void cancel(String runId) {
        }

        @Override
        public List<top.rongx.agent.domain.artifact.ArtifactRef> artifacts(String runId) {
            return List.of();
        }
    }

    @Test
    @DisplayName("未配置密钥时拒绝，不静默放行")
    void rejectsWhenNoSecret() {
        StubEngine engine = new StubEngine();
        GiteeWebhookController controller = new GiteeWebhookController(engine, "");
        assertThrows(ResponseStatusException.class,
                () -> controller.push("anything", null, BODY));
        assertEquals(0, engine.calls);
    }

    @Test
    @DisplayName("Token 正确 → 触发 verify，取仓库名作为 projectId")
    void triggersWithToken() {
        StubEngine engine = new StubEngine();
        GiteeWebhookController controller = new GiteeWebhookController(engine, "s3cr3t");
        Map<String, Object> result = controller.push("s3cr3t", null, BODY);
        assertEquals("run-1", result.get("runId"));
        assertEquals("RY", engine.projectId);
        assertEquals("verify", engine.pipelineId);
        assertEquals("abcdef1234567890abcdef1234567890abcdef12", engine.sha);
        assertEquals(1, engine.calls);
    }

    @Test
    @DisplayName("同一 commit 重复推送 → 幂等，不重复触发")
    void deduplicates() {
        StubEngine engine = new StubEngine();
        GiteeWebhookController controller = new GiteeWebhookController(engine, "s3cr3t");
        controller.push("s3cr3t", null, BODY);
        Map<String, Object> second = controller.push("s3cr3t", null, BODY);
        assertEquals(true, second.get("duplicate"));
        assertEquals(1, engine.calls);
    }

    @Test
    @DisplayName("Token 错误 → 401")
    void rejectsWrongToken() {
        StubEngine engine = new StubEngine();
        GiteeWebhookController controller = new GiteeWebhookController(engine, "s3cr3t");
        assertThrows(ResponseStatusException.class,
                () -> controller.push("wrong", null, BODY));
        assertEquals(0, engine.calls);
    }
}
