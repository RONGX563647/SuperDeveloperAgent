package top.rongx.agent.gateway.web;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;

/**
 * SSE 心跳 + 静默看门狗。
 *
 * <p>两个职责必须同时存在，缺一个就会复现「对话流中途断掉」：
 * <ul>
 *   <li><b>心跳</b>：长任务期间没有业务事件时定期发一条<b>命名事件</b> {@code heartbeat}。
 *       只发注释行（{@code : ping}）时前端解码器会把整帧丢掉，界面无法区分
 *       「服务端在算」和「服务端已经挂了」；命名事件让前端能自己做心跳兜底。</li>
 *   <li><b>静默看门狗</b>：业务事件（不是心跳）超过 {@code stallMillis} 没有任何进展时，
 *       由服务端主动下发 {@code error} 帧（可读原因）并关流。原先挂住就一直挂着，
 *       用户只能手动点停止——这比明确报错更糟。</li>
 * </ul>
 *
 * <p>约束：{@link SseEmitter#send} 不是线程安全的，心跳与业务事件必须共用同一把锁。
 */
final class SseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(SseHeartbeat.class);

    /** 前端与测试都按这个名字识别保活帧。 */
    static final String HEARTBEAT_EVENT = "heartbeat";

    /** 静默超时后下发给前端的事件名（与各控制器的错误帧同名）。 */
    static final String FAILED_EVENT = "error";

    /** 失败文案里的稳定片段：前端与测试按它识别「静默超时」而不是别的错误。 */
    static final String STALL_MARKER = "长时间没有进展";

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("sse-heartbeat").factory());

    private SseHeartbeat() {
    }

    /**
     * 一帧保活/失败事件的描述（事件名 + 载荷）。
     *
     * <p>抽成独立 record 而不是直接在定时器里拼 {@code SseEventBuilder}：
     * builder 的字段不可观测，测试只能看到对象地址，wire 契约就变成了"改坏了也没人知道"。
     */
    record Frame(String event, Map<String, Object> data) {
    }

    /** 心跳帧：只说明服务端还活着，不携带业务语义。 */
    static Frame heartbeatFrame() {
        return new Frame(HEARTBEAT_EVENT, Map.of("alive", true));
    }

    /** 静默超时帧：带秒数与可操作建议，避免只给一个错误码。 */
    static Frame stallFrame(long stallMillis) {
        return new Frame(FAILED_EVENT, Map.of("message", "服务端" + STALL_MARKER + "（已超过 "
                + Math.max(1, stallMillis / 1000) + " 秒），本轮已中止；请重新发送"));
    }

    /** 把帧写到流上；发送失败按客户端已断处理，向上抛出让调度器停止。 */
    private static void send(SseEmitter emitter, Object lock, Frame frame)
            throws IOException {
        synchronized (lock) {
            emitter.send(SseEmitter.event()
                    .name(frame.event())
                    .data(frame.data(), MediaType.APPLICATION_JSON));
        }
    }

    /**
     * 启动心跳（无静默看门狗）。
     *
     * @param emitter      目标流
     * @param lock         与业务事件共用的发送锁
     * @param periodMillis 间隔（最小 5 秒）
     * @param onFailure    发送失败时的回调（通常用于取消上游订阅）
     * @return 定时任务句柄，结束时必须 {@link #stop(ScheduledFuture)}
     */
    static ScheduledFuture<?> start(SseEmitter emitter, Object lock, long periodMillis,
                                    Runnable onFailure) {
        return start(emitter, lock, periodMillis, 0L, new AtomicLong(System.nanoTime()), onFailure);
    }

    /**
     * 启动心跳 + 静默看门狗。
     *
     * @param emitter        目标流
     * @param lock           与业务事件共用的发送锁
     * @param periodMillis   心跳间隔（最小 5 秒）
     * @param stallMillis    业务事件静默多久判定为挂住；{@code <= 0} 表示关闭看门狗
     * @param lastEventNanos 业务事件最后活跃时间（调用方每次推送业务事件后更新）
     * @param onFailure      发送失败 / 静默超时的回调（取消上游订阅）
     */
    static ScheduledFuture<?> start(SseEmitter emitter, Object lock, long periodMillis,
                                    long stallMillis, AtomicLong lastEventNanos,
                                    Runnable onFailure) {
        long period = Math.max(5_000L, periodMillis);
        long stall = Math.max(0L, stallMillis);
        return SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                if (stall > 0 && System.nanoTime() - lastEventNanos.get() > stall * 1_000_000L) {
                    log.warn("SSE 静默超时（{}ms 无业务事件），主动下发失败终态", stall);
                    send(emitter, lock, stallFrame(stall));
                    onFailure.run();
                    emitter.complete();
                    throw new RunException(ErrorCode.RUN_INVALID_STATE,
                            "sse stall watchdog stopped the stream");
                }
                // 命名事件而不是注释行：注释帧在 SseFrameDecoder 里被直接丢弃，
                // 前端拿不到「服务端还活着」的信号。
                send(emitter, lock, heartbeatFrame());
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
