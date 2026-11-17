package top.rongx.agent.gateway.web;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;

/**
 * SSE 心跳：长任务（DAG 并行派发、模型长时间思考）期间没有业务事件时，
 * 反向代理/浏览器可能按空闲超时断流。定期发一条注释行既保活，也能更早发现客户端已断开。
 *
 * <p>约束：{@link SseEmitter#send} 不是线程安全的，心跳必须和业务事件用同一把锁。
 */
final class SseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(SseHeartbeat.class);

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("sse-heartbeat").factory());

    private SseHeartbeat() {
    }

    /**
     * 启动心跳。
     *
     * @param emitter      目标流
     * @param lock         与业务事件共用的发送锁
     * @param periodMillis 间隔（最小 5 秒）
     * @param onFailure    发送失败时的回调（通常用于取消上游订阅）
     * @return 定时任务句柄，结束时必须 {@link #stop(ScheduledFuture)}
     */
    static ScheduledFuture<?> start(SseEmitter emitter, Object lock, long periodMillis,
                                    Runnable onFailure) {
        long period = Math.max(5_000L, periodMillis);
        return SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                synchronized (lock) {
                    emitter.send(SseEmitter.event().comment("ping"));
                }
            } catch (IOException | IllegalStateException gone) {
                log.debug("SSE 心跳结束：{}", gone.getMessage());
                onFailure.run();
                // 抛出后 ScheduledExecutorService 不再调度该任务
                throw new RunException(ErrorCode.RUN_INVALID_STATE, "sse heartbeat stopped", gone);
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    static void stop(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }
}
