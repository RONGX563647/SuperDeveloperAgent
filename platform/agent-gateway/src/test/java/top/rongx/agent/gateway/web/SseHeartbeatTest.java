package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 心跳与静默看门狗的契约。
 *
 * <p>「对话流中途断掉」的本质是：服务端既不发可观测的心跳、挂住时也不主动收尾，
 * 前端只能看到一个永远转圈的按钮。这里把两条契约钉死：
 * <ol>
 *   <li>心跳必须是<b>命名事件</b>（{@code heartbeat}）：注释帧会被前端解码器丢掉；</li>
 *   <li>业务事件静默超过阈值时必须下发失败终态并关流，而不是一直挂着。</li>
 * </ol>
 */
class SseHeartbeatTest {

    @Test
    @DisplayName("心跳帧是命名事件 heartbeat，载荷带 alive")
    void heartbeatIsNamedEvent() {
        SseHeartbeat.Frame frame = SseHeartbeat.heartbeatFrame();

        assertEquals("heartbeat", frame.event());
        assertEquals("heartbeat", SseHeartbeat.HEARTBEAT_EVENT);
        assertEquals(Boolean.TRUE, frame.data().get("alive"));
    }

    @Test
    @DisplayName("静默超时帧：事件名 error、文案说明等了多久并可操作")
    void stallFrameIsActionable() {
        SseHeartbeat.Frame frame = SseHeartbeat.stallFrame(90_000L);
        String message = String.valueOf(frame.data().get("message"));

        assertEquals("error", frame.event());
        assertEquals("error", SseHeartbeat.FAILED_EVENT);
        assertTrue(message.contains(SseHeartbeat.STALL_MARKER), message);
        assertTrue(message.contains("90 秒"), message);
        assertTrue(message.contains("重新发送"), message);
    }

    @Test
    @DisplayName("静默看门狗：无业务事件时下发失败终态、触发取消回调并关流")
    void stallWatchdogFailsTheStream() throws Exception {
        CountingEmitter emitter = new CountingEmitter();
        AtomicBoolean failed = new AtomicBoolean();
        // 活跃时间放到过去，模拟上游长时间没有进展
        AtomicLong lastEvent = new AtomicLong(System.nanoTime() - 60_000_000_000L);
        ScheduledFuture<?> future = SseHeartbeat.start(emitter, new Object(),
                5_000L, 1_000L, lastEvent, () -> failed.set(true));
        try {
            waitUntil(failed::get, 12_000);
        } finally {
            SseHeartbeat.stop(future);
        }

        assertTrue(failed.get(), "静默看门狗必须触发失败回调（取消上游订阅）");
        assertTrue(emitter.sends.get() > 0, "必须真的往流上写过失败帧");
        assertTrue(emitter.completed.get(), "静默超时后必须关流，不能一直挂着");
    }

    @Test
    @DisplayName("持续有业务事件时看门狗不误杀")
    void freshActivityKeepsStreamAlive() throws Exception {
        CountingEmitter emitter = new CountingEmitter();
        AtomicBoolean failed = new AtomicBoolean();
        AtomicLong lastEvent = new AtomicLong(System.nanoTime());
        ScheduledFuture<?> future = SseHeartbeat.start(emitter, new Object(),
                5_000L, 30_000L, lastEvent, () -> failed.set(true));
        try {
            // 心跳周期 5 秒；期间不断刷新活跃时间，模拟持续有事件的长任务
            for (int i = 0; i < 14; i++) {
                lastEvent.set(System.nanoTime());
                Thread.sleep(500L);
            }
        } finally {
            SseHeartbeat.stop(future);
        }

        assertFalse(failed.get(), "持续有业务事件时不应判为挂住");
        assertTrue(emitter.sends.get() > 0, "仍应持续发心跳");
        assertFalse(emitter.completed.get(), "不应关流");
    }

    /** 只统计发送次数与是否关流；帧内容由上面的纯函数用例覆盖。 */
    private static final class CountingEmitter extends SseEmitter {
        private final AtomicInteger sends = new AtomicInteger();
        private final AtomicBoolean completed = new AtomicBoolean();

        @Override
        public void send(SseEventBuilder builder) {
            sends.incrementAndGet();
        }

        @Override
        public void complete() {
            completed.set(true);
            super.complete();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
    }
}
