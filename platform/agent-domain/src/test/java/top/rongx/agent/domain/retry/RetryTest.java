package top.rongx.agent.domain.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 截止时间 + 轮询的等待原语。 */
class RetryTest {

    @Test
    @DisplayName("条件满足即返回 SUCCESS")
    void succeedsWhenConditionMet() {
        AtomicInteger calls = new AtomicInteger();
        Retry.Result result = Retry.until(2_000, 100, () -> false, () -> calls.incrementAndGet() >= 2);
        assertEquals(Retry.Result.SUCCESS, result);
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("取消信号优先于条件")
    void cancelledWins() {
        Retry.Result result = Retry.until(2_000, 100, () -> true, () -> true);
        assertEquals(Retry.Result.CANCELLED, result);
    }

    @Test
    @DisplayName("到时返回 TIMEOUT")
    void timesOut() {
        long start = System.currentTimeMillis();
        Retry.Result result = Retry.until(250, 100, null, () -> false);
        assertEquals(Retry.Result.TIMEOUT, result);
        assertTrue(System.currentTimeMillis() - start >= 200);
    }
}
