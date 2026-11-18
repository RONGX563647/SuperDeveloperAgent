package top.rongx.agent.domain.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.settings.TokenUsageRepository.UsageTotals;

/** 缓存命中率口径：后端日志、接口、前端共用同一算法。 */
class UsageTotalsTest {

    @Test
    @DisplayName("cached/input 四舍五入为整数百分比")
    void computesRoundedHitRate() {
        assertEquals(50, totals(1_000, 500).cacheHitRate());
        assertEquals(33, totals(3, 1).cacheHitRate());
    }

    @Test
    @DisplayName("没有输入 token 时命中率为 0，不出现除零")
    void zeroInputHasZeroRate() {
        assertEquals(0, totals(0, 0).cacheHitRate());
        assertEquals(0, totals(0, 500).cacheHitRate());
    }

    private static UsageTotals totals(long input, long cached) {
        return new UsageTotals(1, input, cached, 10, input + 10, 0);
    }
}
