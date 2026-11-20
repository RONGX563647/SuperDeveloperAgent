package top.rongx.agent.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 触发调度（并行构建语义）：
 * submit 立即执行（构建可并行）；同一 (项目, 环境) 的默认部署槽公平互斥、阻塞等待。
 */
class TriggerSchedulerTest {

    /** 虚拟线程工厂：与生产执行池一致，验证阻塞等待在虚拟线程上可用。 */
    private static final ThreadFactory VIRTUAL = Thread.ofVirtual().factory();

    @Test
    @DisplayName("同一项目同一环境：submit 立即执行（构建并行），部署槽空闲时直接持有")
    void submitRunsImmediately() {
        TriggerScheduler scheduler = new TriggerScheduler();
        List<String> order = new ArrayList<>();

        scheduler.submit("P", "test", () -> order.add("first"));
        assertEquals(List.of("first"), order);

        // 部署槽空闲：acquire 立即返回非空令牌
        TriggerScheduler.DeploySlotToken token = scheduler.acquireDeploySlot("P", "test");
        assertNotNull(token);
        assertTrue(scheduler.isRunning("P", "test"), "部署槽被占用");
        assertEquals(0, scheduler.pendingCount(), "持锁者不计入等待队列");

        token.release();
        assertFalse(scheduler.isRunning("P", "test"), "释放后槽位空闲");
    }

    @Test
    @DisplayName("同一项目同一环境：部署槽被占用时阻塞等待，释放后 FIFO 接力")
    void deploySlotSerializes() throws Exception {
        TriggerScheduler scheduler = new TriggerScheduler();
        TriggerScheduler.DeploySlotToken holder = scheduler.acquireDeploySlot("P", "test");
        assertNotNull(holder);

        List<String> ran = new CopyOnWriteArrayList<>();
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch firstDone = new CountDownLatch(1);
        AtomicReference<TriggerScheduler.DeploySlotToken> t1 = new AtomicReference<>();
        // 两条排队请求（公平锁：先启动的先拿到）
        Thread a = VIRTUAL.newThread(() -> {
            t1.set(scheduler.acquireDeploySlot("P", "test"));
            if (t1.get() != null) {
                ran.add("queued-1");
                firstAcquired.countDown();
                await(firstDone);
                t1.get().release(); // 持锁线程自己释放
            }
        });
        a.start();
        // 等 a 进入等待队列后再启动 b（保证 FIFO 顺序）
        while (scheduler.pendingCount() < 1) {
            Thread.yield();
        }
        CountDownLatch secondAcquired = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        Thread b = VIRTUAL.newThread(() -> {
            var t = scheduler.acquireDeploySlot("P", "test");
            if (t != null) {
                ran.add("queued-2");
                secondAcquired.countDown();
                await(secondDone);
                t.release(); // 持锁线程自己释放
            }
        });
        b.start();
        Thread.sleep(100);
        assertEquals(List.of(), ran, "排队期间闭包不执行");
        assertEquals(2, scheduler.pendingCount(), "两条都在等待");
        assertTrue(scheduler.isRunning("P", "test"));

        // 持锁者释放 → FIFO 唤醒第一条
        holder.release();
        assertTrue(firstAcquired.await(5, TimeUnit.SECONDS),
                "第一条应接力获得部署槽");
        assertEquals(List.of("queued-1"), ran);
        assertEquals(1, scheduler.pendingCount(), "只剩第二条在等待");

        // 第一条释放 → 唤醒第二条
        firstDone.countDown();
        assertTrue(secondAcquired.await(5, TimeUnit.SECONDS),
                "第二条应接力获得部署槽");
        assertEquals(List.of("queued-1", "queued-2"), ran);

        // 第二条释放 → 槽位空闲
        secondDone.countDown();
        long deadline = System.currentTimeMillis() + 5_000;
        while (scheduler.isRunning("P", "test") && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(scheduler.isRunning("P", "test"));
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    @DisplayName("同一项目同一环境：多个提交同时触发 → 构建阶段并行（submit 不排队）")
    void buildsRunConcurrently() {
        TriggerScheduler scheduler = new TriggerScheduler();
        List<String> order = new ArrayList<>();

        scheduler.submit("P", "test", () -> order.add("build-sha1"));
        scheduler.submit("P", "test", () -> order.add("build-sha2"));

        assertEquals(List.of("build-sha1", "build-sha2"), order,
                "构建阶段不再因同环境而串行排队");
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    @DisplayName("不同环境/不同项目互不阻塞：dev 的部署槽不影响 test")
    void differentEnvAndProjectIndependent() {
        TriggerScheduler scheduler = new TriggerScheduler();

        var devToken = scheduler.acquireDeploySlot("P", "dev");
        var testToken = scheduler.acquireDeploySlot("P", "test");
        var qToken = scheduler.acquireDeploySlot("Q", "test");

        assertTrue(scheduler.isRunning("P", "dev"));
        assertTrue(scheduler.isRunning("P", "test"));
        assertTrue(scheduler.isRunning("Q", "test"));

        devToken.release();
        assertFalse(scheduler.isRunning("P", "dev"));
        assertTrue(scheduler.isRunning("P", "test"), "互不影响");

        testToken.release();
        qToken.release();
    }

    @Test
    @DisplayName("空项目：返回 NOOP 令牌，不互斥且 release 无操作")
    void nullProjectGetsNoop() {
        TriggerScheduler scheduler = new TriggerScheduler();
        var t1 = scheduler.acquireDeploySlot(null, "test");
        var t2 = scheduler.acquireDeploySlot(null, "test");
        assertNotNull(t1);
        assertNotNull(t2);
        assertFalse(scheduler.isRunning(null, "test"));
        t1.release();
        t2.release(); // NOOP release 不抛
    }

    @Test
    @DisplayName("等待中被打断：acquire 返回 null，线程中断标志被清除")
    void interruptDuringWaitReturnsNull() throws Exception {
        TriggerScheduler scheduler = new TriggerScheduler();
        var holder = scheduler.acquireDeploySlot("P", "test");
        assertNotNull(holder);

        AtomicBoolean gotNull = new AtomicBoolean(false);
        AtomicBoolean interruptFlagCleared = new AtomicBoolean(true);
        Thread waiter = VIRTUAL.newThread(() -> {
            TriggerScheduler.DeploySlotToken t = scheduler.acquireDeploySlot("P", "test");
            if (t == null) {
                gotNull.set(true);
            }
            interruptFlagCleared.set(!Thread.currentThread().isInterrupted());
        });
        waiter.start();
        while (scheduler.pendingCount() < 1) {
            Thread.yield();
        }
        waiter.interrupt();
        waiter.join(5_000);
        assertTrue(gotNull.get(), "等待被打断应返回 null");
        assertTrue(interruptFlagCleared.get(), "中断标志不应泄漏给后续调用");

        holder.release();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            // 吞掉：测试线程收尾即可
        }
    }
}
