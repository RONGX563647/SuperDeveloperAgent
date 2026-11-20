package top.rongx.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.ChatUsage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.settings.TokenUsageRepository;

/** token 记账：边界值夹紧，且记账失败不能影响对话。 */
class TokenUsageRecorderTest {

    @Test
    @DisplayName("cached 不能超过 input：落库前夹紧")
    void clampsCachedToInput() {
        CapturingRepository repository = new CapturingRepository();
        TokenUsageRecorder recorder = new TokenUsageRecorder(repository);

        recorder.record("s1", null, null,
                ChatUsage.builder().inputTokens(100).cachedTokens(500).outputTokens(20).build());

        assertEquals(1, repository.saved.size());
        TokenUsageRepository.ModelUsage usage = repository.saved.get(0);
        assertEquals(100, usage.inputTokens());
        assertEquals(100, usage.cachedTokens(), "cached 超过 input 时按 input 计");
        assertEquals(20, usage.outputTokens());
    }

    @Test
    @DisplayName("没有用量或会话 ID 为空时不落库")
    void ignoresMissingUsageOrSession() {
        CapturingRepository repository = new CapturingRepository();
        TokenUsageRecorder recorder = new TokenUsageRecorder(repository);

        recorder.record("s1", null, null, null);
        recorder.record("  ", null, null,
                ChatUsage.builder().inputTokens(10).outputTokens(5).build());

        assertTrue(repository.saved.isEmpty());
    }

    @Test
    @DisplayName("仓储异常只记录日志，不向对话抛错")
    void swallowsRepositoryFailure() {
        TokenUsageRecorder recorder = new TokenUsageRecorder(new ThrowingRepository());

        assertDoesNotThrow(() -> recorder.record("s1", null, null,
                ChatUsage.builder().inputTokens(10).outputTokens(5).build()));
    }

    @Test
    @DisplayName("分项归因随用量一起落库，不触发记账异常")
    void recordsAttribution() {
        CapturingRepository repository = new CapturingRepository();
        TokenUsageRecorder recorder = new TokenUsageRecorder(repository);
        ToolAttribution attribution = new ToolAttribution(
                List.of("repo", "ci"), java.util.Map.of("core", 100, "repo", 24, "ci", 50));

        recorder.record("s1", null, null,
                ChatUsage.builder().inputTokens(100).outputTokens(20).build(), attribution);

        assertEquals(1, repository.saved.size());
        TokenUsageRepository.ModelUsage usage = repository.saved.get(0);
        assertEquals("repo,ci", usage.activatedPlugins());
        assertTrue(usage.pluginSchemaChars().contains("repo:24"), "归因字符串稳定可落库: "
                + usage.pluginSchemaChars());
    }

    @Test
    @DisplayName("无归因时使用旧签名，归因字段留空不影响落库")
    void legacyRecordLeavesAttributionEmpty() {
        CapturingRepository repository = new CapturingRepository();
        TokenUsageRecorder recorder = new TokenUsageRecorder(repository);

        recorder.record("s1", null, null,
                ChatUsage.builder().inputTokens(10).outputTokens(5).build());

        assertEquals(null, repository.saved.get(0).activatedPlugins());
    }

    private static class CapturingRepository implements TokenUsageRepository {
        private final List<ModelUsage> saved = new ArrayList<>();

        @Override
        public void record(ModelUsage usage) {
            saved.add(usage);
        }

        @Override
        public List<ModelUsage> findBySession(String sessionId, int limit) {
            return List.of();
        }

        @Override
        public UsageTotals sessionTotals(String sessionId) {
            return UsageTotals.empty();
        }

        @Override
        public UsageSummary summary(int days) {
            return new UsageSummary(days, UsageTotals.empty(), List.of(), List.of(), List.of());
        }

        @Override
        public void deleteBySession(String sessionId) {
            // no-op
        }
    }

    private static final class ThrowingRepository extends CapturingRepository {
        @Override
        public void record(ModelUsage usage) {
            throw new IllegalStateException("数据库不可用");
        }
    }
}
