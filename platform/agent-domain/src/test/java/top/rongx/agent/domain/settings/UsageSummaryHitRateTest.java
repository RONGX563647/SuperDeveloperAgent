package top.rongx.agent.domain.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
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
 * <p>命中率数据本来就有：每次模型调用都记了 {@code cachedTokens}。缺的是
 * 「汇总结果也带命中率」——前端三处若各写一遍 {@code cached / input}，
 * 除零、四舍五入、百分比还是小数迟早不一致，而命中率正是判断
 * 「压缩 prompt 有没有省到钱」的唯一可信指标。
 *
 * <p>实现上做成记录的派生方法而不是让接口返回自建 Map：
 * 返回类型一变，OpenAPI 里的 UsageSummary schema 会消失，
 * 前端生成的 schema.d.ts 立刻与契约不一致（CI 的 check:api-types 会红）。
 */
class UsageSummaryHitRateTest {

    private static UsageTotals totals(long input, long cached) {
        return new UsageTotals(3, input, cached, 100, input + 100, 1_700_000_000_000L);
    }

    @Test
    @DisplayName("汇总总体命中率取自 totals，口径与 UsageTotals 一致")
    void summaryExposesTotalsRate() {
        UsageSummary summary = new UsageSummary(30, totals(1000, 500), List.of(), List.of(), List.of());
        assertEquals(50, summary.cacheHitRate().intValue());
        assertEquals(totals(1000, 500).cacheHitRate(), summary.cacheHitRate().intValue());
    }

    @Test
    @DisplayName("totals 缺失时命中率是 0，不抛空指针")
    void nullTotalsIsSafe() {
        UsageSummary summary = new UsageSummary(30, null, List.of(), List.of(), List.of());
        assertEquals(0, summary.cacheHitRate().intValue());
    }

    @Test
    @DisplayName("三个维度各自能算命中率，且保留原有字段")
    void everyDimensionComputesRate() {
        DailyUsage daily = new DailyUsage("2026-11-19", 3, 1000, 500, 100, 1100);
        assertEquals(50, daily.cacheHitRate());
        assertEquals("2026-11-19", daily.day());
        assertEquals(500L, daily.cachedTokens());

        ModelUsageSummary model = new ModelUsageSummary("deepseek-chat", 3, 1000, 400, 100, 1100);
        assertEquals(40, model.cacheHitRate());
        assertEquals("deepseek-chat", model.modelName());

        ProjectUsageSummary project = new ProjectUsageSummary("RY", 3, 2000, 1500, 100, 2100);
        assertEquals(75, project.cacheHitRate());
        assertEquals("RY", project.projectId());
    }

    @Test
    @DisplayName("各维度无输入时命中率为 0，不出现除零")
    void zeroInputPerDimension() {
        assertEquals(0, new DailyUsage("d", 0, 0, 0, 0, 0).cacheHitRate());
        assertEquals(0, new ModelUsageSummary("m", 0, 0, 30, 0, 0).cacheHitRate());
        assertEquals(0, new ProjectUsageSummary("p", 0, 0, 0, 0, 0).cacheHitRate());
    }

    @Test
    @DisplayName("四舍五入到整数百分比：1/3 -> 33，2/3 -> 67")
    void roundsToIntegerPercent() {
        assertEquals(33, new DailyUsage("d", 1, 3, 1, 0, 3).cacheHitRate());
        assertEquals(67, new DailyUsage("d", 1, 3, 2, 0, 3).cacheHitRate());
        assertEquals(100, new DailyUsage("d", 1, 10, 10, 0, 10).cacheHitRate());
    }
}
