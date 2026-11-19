package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.settings.TokenUsageRepository.DailyUsage;
import top.rongx.agent.domain.settings.TokenUsageRepository.ModelUsageSummary;
import top.rongx.agent.domain.settings.TokenUsageRepository.ProjectUsageSummary;
import top.rongx.agent.domain.settings.TokenUsageRepository.UsageSummary;
import top.rongx.agent.domain.settings.TokenUsageRepository.UsageTotals;

/**
 * 用量汇总的缓存命中率（FEAT-5 第 3 项「提升 KV cache 命中率」的度量侧）。
 *
 * <p>命中率数据本来就有（{@code cachedTokens} 已入库、{@code UsageTotals.cacheHitRate()}
 * 已能算），但汇总接口只回原始 token 数，前端各自手算会出现三套口径。
 * 这里锁住三件事：
 * <ol>
 *   <li>每个维度（总体 / 按天 / 按模型 / 按项目）都带 {@code cacheHitRate}；</li>
 *   <li>原有字段名不变（{@code modelName}/{@code projectId}/{@code day}），别把契约改坏；</li>
 *   <li>输入 token 为 0 时是 0 而不是异常（新会话第一条必然如此）。</li>
 * </ol>
 */
class UsageCacheHitRateTest {

    private static UsageTotals totals(long input, long cached) {
        return new UsageTotals(3, input, cached, 100, input + 100, 1_700_000_000_000L);
    }

    private static UsageSummary summary(long input, long cached) {
        return new UsageSummary(
                30,
                totals(input, cached),
                List.of(new DailyUsage("2026-11-19", 3, input, cached, 100, input + 100)),
                List.of(new ModelUsageSummary("deepseek-chat", 3, input, cached, 100, input + 100)),
                List.of(new ProjectUsageSummary("RY", 3, input, cached, 100, input + 100)));
    }

    /** 直接调私有计算逻辑不方便，这里通过反射拿到 withHitRate 的等价行为。 */
    private static Map<String, Object> hitRateRow(Object row) throws Exception {
        var method = UsageController.class.getDeclaredMethod("withHitRate", Map.class, Object.class);
        method.setAccessible(true);
        return cast(method.invoke(null, Map.of("day", "x"), row));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    @DisplayName("总体命中率：cached/input 的整数百分比")
    void totalsHitRate() {
        assertEquals(50, totals(1000, 500).cacheHitRate());
        assertEquals(0, totals(1000, 0).cacheHitRate());
        assertEquals(100, totals(1000, 1000).cacheHitRate());
        // 四舍五入：1/3 -> 33%
        assertEquals(33, totals(300, 100).cacheHitRate());
    }

    @Test
    @DisplayName("输入为 0 时命中率是 0，不抛异常（新会话第一条必然如此）")
    void zeroInputIsSafe() {
        assertEquals(0, totals(0, 0).cacheHitRate());
        assertEquals(0, totals(0, 50).cacheHitRate(), "没有输入时不该出现 >0 的比率");
    }

    @Test
    @DisplayName("按天/按模型/按项目的每一行都带 cacheHitRate，且保留原字段名")
    void everyDimensionCarriesHitRate() throws Exception {
        Map<String, Object> daily = hitRateRow(new DailyUsage("2026-11-19", 3, 1000, 500, 100, 1100));
        assertEquals("x", daily.get("day"));
        assertEquals(50, daily.get("cacheHitRate"));
        assertEquals(500L, daily.get("cachedTokens"));

        Map<String, Object> model = hitRateRow(
                new ModelUsageSummary("deepseek-chat", 3, 1000, 400, 100, 1100));
        assertEquals(40, model.get("cacheHitRate"));
        assertEquals(1000L, model.get("inputTokens"));

        Map<String, Object> project = hitRateRow(
                new ProjectUsageSummary("RY", 3, 2000, 1500, 100, 2100));
        assertEquals(75, project.get("cacheHitRate"));
        assertEquals(1500L, project.get("cachedTokens"));
    }

    @Test
    @DisplayName("行内字段齐全：calls / input / cached / output / total 都在")
    void rowKeepsAllTokenFields() throws Exception {
        Map<String, Object> row = hitRateRow(new DailyUsage("d", 7, 100, 60, 30, 130));

        assertEquals(7L, row.get("calls"));
        assertEquals(100L, row.get("inputTokens"));
        assertEquals(60L, row.get("cachedTokens"));
        assertEquals(30L, row.get("outputTokens"));
        assertEquals(130L, row.get("totalTokens"));
        assertTrue(row.containsKey("cacheHitRate"));
    }
}
