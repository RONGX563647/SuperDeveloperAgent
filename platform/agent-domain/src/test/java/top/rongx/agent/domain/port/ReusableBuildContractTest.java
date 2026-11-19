package top.rongx.agent.domain.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.port.PipelineEngine.ReusableBuild;

/**
 * 「为什么不能复用前面产物部署」的语义：
 *
 * <p>复用不是省掉校验，而是「同一份代码 + 同一个版本」的优化。
 * 这里把它的三条边界钉死：不可用要说明原因、提交必须一致、版本必须一致。
 */
class ReusableBuildContractTest {

    @Test
    @DisplayName("不可用时必须给出原因，而不是一个空对象")
    void unavailableCarriesReason() {
        ReusableBuild none = ReusableBuild.unavailable("该运行未成功（FAILED），不能复用其产物");

        assertFalse(none.available());
        assertTrue(none.reason().contains("FAILED"), none.reason());
        assertTrue(none.image().isEmpty());
        assertTrue(none.version().isEmpty());
    }

    @Test
    @DisplayName("可复用时带出镜像/版本/提交三件套")
    void availableCarriesArtifacts() {
        ReusableBuild reuse = ReusableBuild.of("registry/app:0.1.7", "0.1.7", "abcdef1234");

        assertTrue(reuse.available());
        assertEquals("registry/app:0.1.7", reuse.image());
        assertEquals("0.1.7", reuse.version());
        assertEquals("abcdef1234", reuse.gitSha());
        assertTrue(reuse.reason().isEmpty());
    }

    @Test
    @DisplayName("提交不一致判定：短 sha 也能比对，不同提交不能复用")
    void shaComparisonAcceptsShortForm() {
        ReusableBuild reuse = ReusableBuild.of("img", "0.1.7", "abcdef1234567890");
        String requested = "abcdef1";

        assertTrue(reuse.gitSha().startsWith(requested), "短 sha 应能匹配前缀");
        assertFalse(reuse.gitSha().startsWith("fffffff"), "不同提交必须被拒");
    }

    @Test
    @DisplayName("默认实现不可用，避免引擎没实现时静默复用空产物")
    void defaultIsUnavailable() {
        PipelineEngine engine = new MinimalEngine();

        ReusableBuild reuse = engine.reusableBuild("run-x");
        assertFalse(reuse.available());
        assertTrue(reuse.reason().contains("不支持"), reuse.reason());
    }

    /** 只实现必需方法的最小引擎。 */
    private static final class MinimalEngine implements PipelineEngine {
        @Override
        public String id() {
            return "minimal";
        }

        @Override
        public java.util.List<String> pipelines(String projectId) {
            return java.util.List.of();
        }

        @Override
        public String trigger(String projectId, String pipelineId,
                              java.util.Map<String, String> params) {
            return "run-1";
        }

        @Override
        public top.rongx.agent.domain.run.StageStatus status(String runId) {
            return top.rongx.agent.domain.run.StageStatus.SUCCEEDED;
        }

        @Override
        public java.util.List<top.rongx.agent.domain.run.StageResult> stages(String runId) {
            return java.util.List.of();
        }

        @Override
        public void cancel(String runId) {
        }

        @Override
        public java.util.List<top.rongx.agent.domain.artifact.ArtifactRef> artifacts(String runId) {
            return java.util.List.of();
        }
    }
}
