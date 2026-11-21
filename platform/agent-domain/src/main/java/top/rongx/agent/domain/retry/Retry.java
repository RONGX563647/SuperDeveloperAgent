package top.rongx.agent.domain.retry;

import java.util.function.BooleanSupplier;

/**
 * 截止时间 + 轮询的等待原语（唯一实现）。
 *
 * <p>「while (now &lt; deadline) { if (cancelled) return; if (probe()) return ok;
 * sleep(poll); }」这段形状在容器就绪、守护进程启动等地方各写一遍：取消判定有没有、
 * 首次是否立刻探测、睡多久、超时返回什么，各不相同，卡住时很难判断是「真的没就绪」
 * 还是「调用方忘了检查取消」。
 *
 * <p>本类只做纯 JDK 的等待控制，不包含任何业务判定。
 */
public final class Retry {

    /** 等待结果：成功 / 超时 / 被取消。 */
    public enum Result {
        SUCCESS,
        TIMEOUT,
        CANCELLED
    }

    /** 单次尝试：返回 {@code true} 表示条件已满足。 */
    @FunctionalInterface
    public interface Action {
        boolean attempt();
    }

    private Retry() {
    }

    /**
     * 在截止时间内轮询，直到 {@code action} 返回 {@code true}。
     *
     * @param timeoutMs 总时长（&lt;=0 视为 1ms）
     * @param pollMs    轮询间隔（&lt;100ms 按 100ms）
     * @param cancelled 取消信号；为 {@code null} 视为不可取消
     */
    public static Result until(long timeoutMs, long pollMs, BooleanSupplier cancelled,
                               Action action) {
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMs);
        long interval = Math.max(100L, pollMs);
        while (System.currentTimeMillis() < deadline) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return Result.CANCELLED;
            }
            if (action.attempt()) {
                return Result.SUCCESS;
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.CANCELLED;
            }
        }
        return Result.TIMEOUT;
    }
}
