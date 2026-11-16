package top.rongx.agent.executor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎接线回归：失败后自动修复只处理一次、状态正确收敛、前端能拿到自愈状态。
 *
 * <p>用反射驱动私有状态（与 PipelineWatchdogTest 同法），不启动真实流水线与 Docker。
 */
class PipelineAutoRepairTest {

    private static final String STATE_CLASS = "top.rongx.agent.executor.BuiltInPipelineEngine$RunState";

    private static BuiltInPipelineEngine engine(Path temp) {
        BuiltInPipelineEngine engine = new BuiltInPipelineEngine(null, null, null, temp,
                RunHistoryStore.file(temp));
        engine.close(); // 关掉后台看护 tick，测试自己驱动
        return engine;
    }

    private static Object newState(String env, String actor) throws Exception {
        Class<?> type = Class.forName(STATE_CLASS);
        Constructor<?> ctor = type.getDeclaredConstructor(String.class, String.class,
                String.class, String.class, String.class);
        ctor.setAccessible(true);
        Object state = ctor.newInstance("run-auto", "PROJ", env, "verify", "abcdef1234");
        set(state, "status", StageStatus.FAILED);
        set(state, "actor", actor);
        @SuppressWarnings("unchecked")
        List<StageResult> stages = (List<StageResult>) get(state, "stages");
        stages.add(StageResult.failed("build", 1, "E-209",
                "构建镜像未就绪（maven:cfg）", 0));
        ((StringBuffer) get(state, "log")).append("[build] ❌ E-209 镜像缺失\n");
        return state;
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
    private static void putRun(BuiltInPipelineEngine engine, Object state) throws Exception {
        Field f = BuiltInPipelineEngine.class.getDeclaredField("runs");
        f.setAccessible(true);
        ((Map<String, Object>) f.get(engine)).put("run-auto", state);
    }

    private static void recover(BuiltInPipelineEngine engine, Object state) throws Exception {
        Method m = BuiltInPipelineEngine.class.getDeclaredMethod("recoverIfNeeded",
                Class.forName(STATE_CLASS));
        m.setAccessible(true);
        m.invoke(engine, state);
    }

    private static FailureRepair stub(boolean verified) {
        return new FailureRepair() {
            @Override
            public boolean matches(Diagnosis d) {
                return true;
            }

            @Override
            public Outcome repair(Diagnosis d, Context ctx) {
                return verified
                        ? Outcome.fixed("桩：复核通过", List.of("[autofix] 桩修复完成"))
                        : Outcome.notFixed("桩：未修复", List.of("[autofix] 桩修复失败"));
            }
        };
    }

    @Test
    @DisplayName("失败后自动修复只处理一次，日志与状态视图同步，不会重复追加 [autofix]")
    void repairIsIdempotentAndObservable(@TempDir Path temp) throws Exception {
        BuiltInPipelineEngine engine = engine(temp);
        Object state = newState("test", "ui:admin");
        putRun(engine, state);
        engine.withAutoRepair(new AutoRepairPolicy(true, 1, 60_000L, false, true),
                List.of(stub(false)));

        recover(engine, state);
        String afterFirst = logOf(state);
        assertTrue(afterFirst.contains("[autofix] 运行失败，开始自动修复"), afterFirst);
        assertTrue(afterFirst.contains("桩修复失败"), afterFirst);
        assertTrue(afterFirst.contains("自动修复未收敛"), afterFirst);
        assertEquals(StageStatus.FAILED, engine.status("run-auto"));

        PipelineEngine.AutoRepairView view = engine.autoRepair("run-auto");
        assertEquals(1, view.attempts());
        assertFalse(view.resumed());
        assertTrue(view.lastResult().contains("未修复"), view.lastResult());

        recover(engine, state); // 第二次调用必须被幂等保护拦下
        assertEquals(afterFirst, logOf(state), "同一失败周期不得重复写自愈日志");
    }

    @Test
    @DisplayName("修复复核通过 → 尝试自动续跑；没有可用现场时保持失败且不无限重试")
    void verifiedRepairAttemptsResumeWithoutLooping(@TempDir Path temp) throws Exception {
        BuiltInPipelineEngine engine = engine(temp);
        Object state = newState("test", "ui:admin");
        putRun(engine, state);
        engine.withAutoRepair(new AutoRepairPolicy(true, 2, 60_000L, false, true),
                List.of(stub(true)));

        recover(engine, state);

        String log = logOf(state);
        assertTrue(log.contains("自动从阶段 build 继续"), log);
        assertTrue(log.contains("无可用构建现场"), log);
        assertEquals(StageStatus.FAILED, engine.status("run-auto"));
        assertTrue(engine.autoRepair("run-auto").resumed());
        assertEquals(2, engine.autoRepair("run-auto").attempts(), "受 maxAttempts 约束");
    }

    @Test
    @DisplayName("诊断只读失败阶段日志：前序阶段已自愈的镜像缺失不能串到当前阶段")
    void diagnosisUsesFailedStageLog(@TempDir Path temp) throws Exception {
        BuiltInPipelineEngine engine = engine(temp);
        Object state = newState("test", "ui:admin");
        @SuppressWarnings("unchecked")
        List<StageResult> stages = (List<StageResult>) get(state, "stages");
        stages.clear();
        stages.add(StageResult.failed("frontend", 1, "E-101",
                "命令失败（exit 1）：node:20-bookworm corepack enable", 0));
        set(state, "definition", PipelineDefinition.parse("""
                name: release
                deploy:
                  target: host
                stages:
                  - id: build
                    type: action
                    action: backend_build
                    runner: container
                  - id: frontend
                    type: action
                    action: frontend_build
                    runner: container
                    image: node:20-bookworm
                """, "test"));
        StringBuffer log = (StringBuffer) get(state, "log");
        log.setLength(0);
        log.append("[build] Error response from daemon: No such image: maven:3.9-eclipse-temurin-21\n");
        log.append("[frontend] 阶段开始 · 第 2/3 阶段\n");
        log.append("[frontend] Internal Error: EACCES: permission denied\n");
        log.append("[frontend] ❌ E-101 命令失败（exit 1）\n");

        Method diagnose = BuiltInPipelineEngine.class.getDeclaredMethod("diagnoseFailure",
                Class.forName(STATE_CLASS), int.class);
        diagnose.setAccessible(true);
        FailureRepair.Diagnosis diagnosis =
                (FailureRepair.Diagnosis) diagnose.invoke(engine, state, 1);

        assertNull(diagnosis.image(), "不能把前序阶段的 maven 当成当前失败镜像");
        assertFalse(diagnosis.logTail().contains("No such image: maven"),
                diagnosis.logTail());
        assertTrue(diagnosis.logTail().contains("EACCES"), diagnosis.logTail());

        // 当前阶段真的缺镜像时仍要能识别（预检关闭场景由 ImagePullRepair 补位）
        log.append("[frontend] Error response from daemon: No such image: node:20-bookworm\n");
        FailureRepair.Diagnosis withNode =
                (FailureRepair.Diagnosis) diagnose.invoke(engine, state, 1);
        assertEquals("node:20-bookworm", withNode.image());
    }

    @Test
    @DisplayName("生产 / Agent 自触发 / 策略关闭时不自动修复，避免递归与非授权变更")
    void guardRails(@TempDir Path temp) throws Exception {
        for (String[] guarded : List.of(
                new String[]{"prod", "ui:admin"},
                new String[]{"test", "agent"})) {
            BuiltInPipelineEngine engine = engine(temp);
            Object state = newState(guarded[0], guarded[1]);
            putRun(engine, state);
            engine.withAutoRepair(AutoRepairPolicy.defaults(), List.of(stub(true)));
            recover(engine, state);
            assertFalse(logOf(state).contains("[autofix]"), logOf(state));
        }

        BuiltInPipelineEngine disabled = engine(temp);
        Object state = newState("test", "ui:admin");
        putRun(disabled, state);
        disabled.withAutoRepair(AutoRepairPolicy.disabled(), List.of(stub(true)));
        recover(disabled, state);
        assertFalse(logOf(state).contains("[autofix]"), logOf(state));
        assertEquals(0, disabled.autoRepair("run-auto").attempts());
    }
}
