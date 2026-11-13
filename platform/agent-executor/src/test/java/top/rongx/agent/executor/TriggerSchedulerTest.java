package top.rongx.agent.executor;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 触发调度：同一 (项目, 环境) FIFO 串行，不同环境互不阻塞。 */
class TriggerSchedulerTest {

    @Test
    @DisplayName("同一项目同一环境：运行中的任务排到 FIFO 队列，complete 后依次执行")
    void sameEnvSerializes() {
        TriggerScheduler scheduler = new TriggerScheduler();
        List<String> order = new ArrayList<>();

        scheduler.submit("P", "test", () -> {
            order.add("first");
            assertTrue(scheduler.isRunning("P", "test"));
            // 运行中再提交：应排队，而不是立即执行
            scheduler.submit("P", "test", () -> order.add("second"));
            order.add("first-end");
        });
        assertEquals(List.of("first", "first-end"), order);
        assertEquals(1, scheduler.pendingCount());

        scheduler.complete("P", "test");
        assertEquals(List.of("first", "first-end", "second"), order);
        assertTrue(scheduler.isRunning("P", "test"), "第二条已开始运行");
        assertEquals(0, scheduler.pendingCount());

        scheduler.complete("P", "test");
        assertFalse(scheduler.isRunning("P", "test"));
    }

    @Test
    @DisplayName("不同环境可以同时运行：dev 运行中不阻塞 test")
    void differentEnvRunsImmediately() {
        TriggerScheduler scheduler = new TriggerScheduler();
        List<String> order = new ArrayList<>();

        scheduler.submit("P", "dev", () -> {
            order.add("dev");
            scheduler.submit("P", "test", () -> order.add("test"));
        });

        // test 未被 dev 排队，立即执行
        assertEquals(List.of("dev", "test"), order);
        assertTrue(scheduler.isRunning("P", "dev"));
        scheduler.complete("P", "dev");
        assertFalse(scheduler.isRunning("P", "dev"));
    }
}
