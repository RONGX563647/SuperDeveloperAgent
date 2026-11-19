package top.rongx.agent.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 健康检查等待窗口回归：窗口过去是写死的「次数 × 间隔」，与运行总预算无关。
 * 剩余预算不足时，看护线程会先请求取消再强制终态，健康检查自己的诊断
 * （容器退出码、容器日志、HTTP body）根本没机会写出来。
 */
class PipelineHealthWindowTest {

    private static final long PROBE = 2_000L;

    @Test
    @DisplayName("预算充足：按 docker 的 90 次 × 2s 给满约 3 分钟")
    void fullWindowWhenBudgetIsEnough() {
        long planned = 90 * (2000L + PROBE);
        assertEquals(planned, BuiltInPipelineEngine.healthWindowMillis(90, 2000L, planned + 600_000L));
    }

    @Test
    @DisplayName("剩余预算不足：窗口收敛到剩余预算，不再被看护抢先判成「超时且无法中断」")
    void windowShrinksToRemainingBudget() {
        long remaining = 60_000L;
        long window = BuiltInPipelineEngine.healthWindowMillis(90, 2000L, remaining);
        assertTrue(window <= remaining, "窗口不能超过剩余预算：" + window);
        assertTrue(window > 0, "至少要探一轮");
    }

    @Test
    @DisplayName("预算已耗尽：仍保留最小窗口，让失败摘要带上真实证据")
    void minimumWindowWhenBudgetIsSpent() {
        long window = BuiltInPipelineEngine.healthWindowMillis(60, 1000L, -5_000L);
        assertTrue(window >= 30_000L, "预算耗尽也要留出诊断时间：" + window);
        assertTrue(window < 60 * (1000L + PROBE), "但不应再按满窗口空等：" + window);
    }
}
