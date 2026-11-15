package top.rongx.agent.executor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.run.StageStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行看护回归：过去一次外部命令静默阻塞会让运行永远停在 RUNNING，
 * 历史里只留一条 PENDING 空日志，且阶段列表看不到正在跑的阶段。
 *
 * <p>这里手动驱动 {@code heartbeat}（先关掉后台 tick 以消除时序抖动），
 * 验证心跳、超预算取消、强制终态、进度与取消反馈。
 */
class PipelineWatchdogTest {

    private static final String STATE_CLASS = "top.rongx.agent.executor.BuiltInPipelineEngine$RunState";

    private static BuiltInPipelineEngine engine(Path temp) {
        RunHistoryStore history = RunHistoryStore.file(temp);
        BuiltInPipelineEngine engine = new BuiltInPipelineEngine(null, null, null, temp, history);
        // 关掉后台 tick，测试自己驱动看护，避免 5s 周期带来的竞态
        engine.close();
        return engine;
    }

    private static Object newState(String runId) throws Exception {
        Class<?> type = Class.forName(STATE_CLASS);
        Constructor<?> ctor = type.getDeclaredConstructor(String.class, String.class,
                String.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(runId, "WATCH", "test", "verify", "abcdef1234");
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

    private static String logOf(Object state) throws Exception {
        return ((StringBuffer) get(state, "log")).toString();
    }

    @SuppressWarnings("unchecked")
    private static void putRun(BuiltInPipelineEngine engine, String runId, Object state) throws Exception {
        Field f = BuiltInPipelineEngine.class.getDeclaredField("runs");
        f.setAccessible(true);
        ((Map<String, Object>) f.get(engine)).put(runId, state);
    }

    private static void tick(BuiltInPipelineEngine engine, Object state, long now) throws Exception {
        Method m = BuiltInPipelineEngine.class.getDeclaredMethod("heartbeat",
                Class.forName(STATE_CLASS), long.class);
        m.setAccessible(true);
        m.invoke(engine, state, now);
    }

    @Test
    @DisplayName("静默运行会写心跳；超预算先取消，仍不收敛则强制终态并落盘")
    void watchdogKeepsRunObservable(@TempDir Path temp) throws Exception {
        BuiltInPipelineEngine engine = engine(temp);
        Object state = newState("run-watch");
        set(state, "status", StageStatus.RUNNING);
        set(state, "currentStageId", "build");
        set(state, "stageTotal", 3);
        set(state, "maxRunMillis", -1L);
        long now = System.currentTimeMillis();
        set(state, "lastLogChangeAt", now - 31_000L);
        putRun(engine, "run-watch", state);

        PipelineEngine.RunProgress progress = engine.progress("run-watch");
        assertEquals("build", progress.currentStageId());
        assertEquals(3, progress.stageTotal());
        assertFalse(progress.cancelRequested());

        tick(engine, state, now);
        assertTrue(logOf(state).contains("[heartbeat]"), "静默命令也要让日志动起来");
        assertTrue(logOf(state).contains("[watchdog] 运行超过总预算"));
        assertTrue((boolean) get(state, "cancelled"));

        set(state, "cancelRequestedAt", now - 61_000L);
        tick(engine, state, now + 1_000L);
        assertEquals(StageStatus.TIMEOUT, engine.status("run-watch"));
        assertTrue(logOf(state).contains("E-302 运行超时且无法中断"));
        assertTrue(engine.progress("run-watch").forceTerminated());
        assertTrue(RunHistoryStore.file(temp).list().stream()
                        .anyMatch(s -> s.runId().equals("run-watch")
                                && s.status() == StageStatus.TIMEOUT),
                "强制终态必须落盘，重启后不再是一条 PENDING 幽灵任务");
    }

    @Test
    @DisplayName("取消立即写可见反馈并落盘，不再点了没反应")
    void cancelGivesImmediateFeedback(@TempDir Path temp) throws Exception {
        BuiltInPipelineEngine engine = engine(temp);
        Object state = newState("run-cancel");
        set(state, "status", StageStatus.RUNNING);
        putRun(engine, "run-cancel", state);

        engine.cancel("run-cancel");

        assertTrue((boolean) get(state, "cancelled"));
        assertTrue(logOf(state).contains("[cancel] 已请求取消"), logOf(state));
        assertTrue(RunHistoryStore.file(temp).list().stream()
                .anyMatch(s -> s.runId().equals("run-cancel")));
    }
}
