package top.rongx.agent.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-32 / BUG-38 / BUG-41 回归：
 * 多服务回滚错误码、终态运行移出内存热表、forceTerminate 真正中断执行线程。
 */
class PipelineEngineLifecycleTest {

    private static final String STATE_CLASS = "top.rongx.agent.executor.BuiltInPipelineEngine$RunState";

    private static BuiltInPipelineEngine engine(Path temp) {
        RunHistoryStore history = RunHistoryStore.file(temp);
        BuiltInPipelineEngine e = new BuiltInPipelineEngine(null, null, null, temp, history);
        e.close(); // 关掉后台看护，消除时序抖动
        return e;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> runs(BuiltInPipelineEngine engine) throws Exception {
        Field f = BuiltInPipelineEngine.class.getDeclaredField("runs");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(engine);
    }

    private static Object newState(String runId) throws Exception {
        Class<?> type = Class.forName(STATE_CLASS);
        Constructor<?> ctor = type.getDeclaredConstructor(String.class, String.class,
                String.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(runId, "LIFE", "test", "verify", "abcdef1234");
    }

    private static void set(Object state, String field, Object value) throws Exception {
        Field f = state.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(state, value);
    }

    private static Object get(Object state, String field) throws Exception {
        Field f = state.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(state);
    }

    private static RunHistoryStore historyStore(Path temp) {
        return RunHistoryStore.file(temp);
    }

    @SuppressWarnings("unchecked")
    private static RunHistoryStore engineHistory(BuiltInPipelineEngine engine) throws Exception {
        Field f = BuiltInPipelineEngine.class.getDeclaredField("history");
        f.setAccessible(true);
        return (RunHistoryStore) f.get(engine);
    }

    private static void invokeEvict(BuiltInPipelineEngine engine, Object state) throws Exception {
        Method m = BuiltInPipelineEngine.class.getDeclaredMethod("evictIfTerminal",
                Class.forName(STATE_CLASS));
        m.setAccessible(true);
        m.invoke(engine, state);
    }

    private static void invokeForceTerminate(BuiltInPipelineEngine engine, Object state,
                                             String reason) throws Exception {
        Method m = BuiltInPipelineEngine.class.getDeclaredMethod("forceTerminate",
                Class.forName(STATE_CLASS), String.class);
        m.setAccessible(true);
        m.invoke(engine, state, reason);
    }

    // ------------------------------------------------------------------ BUG-38

    @Test
    @DisplayName("终态收尾：evictIfTerminal 把终态条目从 runs 移出，history 仍可查")
    void terminalRunEvictedFromMemoryButReadableFromHistory(@TempDir Path temp) throws Exception {
        BuiltInPipelineEngine engine = engine(temp);
        Object state = newState("run-evict");
        set(state, "status", StageStatus.SUCCEEDED);
        engineHistory(engine).save(snapshotOf(state));
        runs(engine).put("run-evict", state);

        invokeEvict(engine, state);

        assertFalse(runs(engine).containsKey("run-evict"), "终态后内存热表不再持有该条目");
        assertTrue(engine.hasRun("run-evict"), "hasRun 走 history 兜底仍为 true");
        assertEquals(StageStatus.SUCCEEDED, engine.status("run-evict"), "status 走 history 兜底");
        assertNotNull(engine.stages("run-evict"), "stages 兜底不抛异常");
        assertTrue(RunHistoryStore.file(temp).list().stream()
                        .anyMatch(s -> s.runId().equals("run-evict")),
                "历史仍为权威");
        // 运行中的条目不被回收
        Object running = newState("run-live");
        set(running, "status", StageStatus.RUNNING);
        runs(engine).put("run-live", running);
        invokeEvict(engine, running);
        assertTrue(runs(engine).containsKey("run-live"), "运行中的查询必须仍然可用");
    }

    @Test
    @DisplayName("重启恢复：库中 10 条终态不回灌内存，runs 只留被标记取消的幽灵任务")
    void restoreDoesNotReloadTerminalRuns(@TempDir Path temp) throws Exception {
        RunHistoryStore history = RunHistoryStore.file(temp);
        for (int i = 0; i < 10; i++) {
            Object s = newState("run-old-" + i);
            set(s, "status", StageStatus.SUCCEEDED);
            history.save(snapshotOf(s));
        }
        // 幽灵任务：RUNNING
        Object ghost = newState("run-ghost");
        set(ghost, "status", StageStatus.RUNNING);
        history.save(snapshotOf(ghost));

        BuiltInPipelineEngine engine = engine(temp);
        // restoreHistory 在构造时执行；终态 10 条不应在内存
        long terminalInMemory = runs(engine).keySet().stream()
                .filter(id -> id.startsWith("run-old-")).count();
        assertEquals(0, terminalInMemory, "终态运行重启后不回灌内存");
        // 幽灵任务被标记 CANCELLED 且保留（供页头收敛）
        Object restored = runs(engine).get("run-ghost");
        assertNotNull(restored, "非终态幽灵任务仍要恢复以便收敛");
        assertEquals(StageStatus.CANCELLED, ((StageStatus) get(restored, "status")));
        engine.close();
    }

    @SuppressWarnings("unchecked")
    private static RunHistoryStore.RunSnapshot snapshotOf(Object state) throws Exception {
        Method m = state.getClass().getDeclaredMethod("snapshot");
        m.setAccessible(true);
        return (RunHistoryStore.RunSnapshot) m.invoke(state);
    }

    // ------------------------------------------------------------------ BUG-41

    @Test
    @DisplayName("forceTerminate：可中断执行线程被 cancel(true) 中断退出，条目从 runs 移除")
    void forceTerminateInterruptsThread(@TempDir Path temp) throws Exception {
        BuiltInPipelineEngine engine = engine(temp);
        Object state = newState("run-force");
        set(state, "status", StageStatus.RUNNING);
        runs(engine).put("run-force", state);

        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        // 模拟引擎的真实提交流程：保存 Future 到 RunState
        Future<?> future = pool.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            } finally {
                done.countDown();
                try {
                    ((CountDownLatch) get(state, "executionDone")).countDown();
                } catch (java.lang.Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        });
        set(state, "executionFuture", future);
        try {
            // 必须先确认线程真的在跑：FutureTask 的 cancel(true) 若落在任务启动之前，
            // 该任务会被直接置为 CANCELLED 且"永远不执行 callable"，于是 executionDone
            // 不会发信号、forceTerminate 白等 10s，测试再等 15s —— 并行 fork 下必现 25s 失败。
            assertTrue(started.await(10, TimeUnit.SECONDS), "执行线程必须已开始运行");

            long t0 = System.nanoTime();
            invokeForceTerminate(engine, state, "E-302 测试强制终态");
            long joinMs = (System.nanoTime() - t0) / 1_000_000;

            assertTrue(done.await(15, TimeUnit.SECONDS), "线程应被中断退出");
            assertTrue(interrupted.get(), "cancel(true) 必须传递中断");
            assertTrue(joinMs < 15_000, "forceTerminate 应有界等待（实际 " + joinMs + "ms）");
            assertFalse(runs(engine).containsKey("run-force"),
                    "强制终态条目按 BUG-38 回收路径移除");
            assertEquals(StageStatus.TIMEOUT, engine.status("run-force"));
            assertTrue(RunHistoryStore.file(temp).list().stream()
                            .anyMatch(s -> s.runId().equals("run-force") && s.status() == StageStatus.TIMEOUT),
                    "强制终态仍落盘");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("forceTerminate：不可中断线程超时放行但日志明说未确认退出")
    void forceTerminateLogsWhenThreadUnkillable(@TempDir Path temp) throws Exception {
        BuiltInPipelineEngine engine = engine(temp);
        Object state = newState("run-stuck");
        set(state, "status", StageStatus.RUNNING);
        runs(engine).put("run-stuck", state);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        Future<?> future = pool.submit(() -> {
            started.countDown();
            // 不检查中断标志、sleep 吞中断：模拟卡死在无取消检查点的调用；
            // 也不会发出 executionDone 信号（同 execute 的 finally 不会执行）
            while (release.getCount() > 0) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    // 吞掉中断
                }
            }
        });
        set(state, "executionFuture", future);
        try {
            // 同上：线程没开始就跑不进"吞中断"的循环，用例会"通过"但没有验证任何东西
            assertTrue(started.await(10, TimeUnit.SECONDS), "执行线程必须已开始运行");

            long t0 = System.nanoTime();
            invokeForceTerminate(engine, state, "E-302 卡死强制终态");
            long joinMs = (System.nanoTime() - t0) / 1_000_000;

            assertTrue(joinMs >= 9_500 && joinMs < 30_000,
                    "卡死线程要在 10s 有界窗口超时放行（实际 " + joinMs + "ms）");
            StringBuffer log = (StringBuffer) get(state, "log");
            assertTrue(log.toString().contains("未确认退出"), "日志必须明说线程未退出");
            assertEquals(StageStatus.TIMEOUT, engine.status("run-stuck"));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ BUG-32

    @Test
    @DisplayName("多服务回滚：错误码为 CAPABILITY_UNSUPPORTED（非版本缺失），文案含可回退镜像")
    void multiServiceRollbackUsesCapabilityCode(@TempDir Path temp) throws Exception {
        BuiltInPipelineEngine engine = engine(temp);
        Method m = BuiltInPipelineEngine.class.getDeclaredMethod("deployServiceSet",
                Class.forName(STATE_CLASS), Path.class, long.class, String.class,
                Class.forName("top.rongx.agent.executor.PipelineDefinition$DeploySpec"),
                Map.class, String.class,
                Class.forName("top.rongx.agent.domain.env.EnvTemplate"),
                boolean.class, String.class);
        m.setAccessible(true);
        m.trySetAccessible();

        Object state = newState("run-ms-rollback");
        set(state, "status", StageStatus.RUNNING);
        runs(engine).put("run-ms-rollback", state);

        // rollback 分支在网络检查之前 return：用最小 DeploySpec 直接触发 rollback 分支
        // （不会走到 docker），不依赖 Docker 守护进程。
        PipelineDefinition.DeploySpec spec =
                new PipelineDefinition.DeploySpec("docker", 8080, 8080, "/healthz",
                        null, null, List.of(), List.of(), null);

        Object result = m.invoke(engine, state, temp, 0L, "rollback",
                spec, Map.of(), null, null, true, null);

        assertEquals(false, result, "rollback 必须失败返回");
        List<?> stages = (List<?>) get(state, "stages");
        assertEquals(1, stages.size());
        StageResult failed = (StageResult) stages.get(0);
        assertEquals(ErrorCode.CAPABILITY_UNSUPPORTED.code(), failed.errorCode(),
                "错误码不能是 ROLLBACK_VERSION_MISSING（版本缺失语义）");
        assertNotNull(failed.errorSummary());
        // message 给出可执行的回退指引
        assertTrue(failed.errorSummary().contains("docker pull"), failed.errorSummary());
        assertTrue(failed.errorSummary().contains("回退镜像清单"), failed.errorSummary());
        engine.close();
    }
}
