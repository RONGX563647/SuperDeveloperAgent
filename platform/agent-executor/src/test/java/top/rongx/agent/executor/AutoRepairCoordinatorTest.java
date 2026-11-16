package top.rongx.agent.executor;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自动修复闭环：只有平台复核通过才续跑，且有轮次上限、取消与幂等保护。 */
class AutoRepairCoordinatorTest {

    private static FailureRepair.Diagnosis diagnosis(int attempt) {
        return new FailureRepair.Diagnosis("run-1", "build", "E-209",
                "构建镜像未就绪（maven:test）", "[build] 失败", "maven:test", attempt, true);
    }

    private static FailureRepair.Context context() {
        return new FailureRepair.Context(() -> false, line -> {
        }, (argv, timeout, live) -> ProcessExecutor.StepOutcome.ok(),
                180_000L, List.of(), List.of(), "maven:test", null,
                "P", "test", "verify", "abcdef1", "ui:admin", "", () -> false);
    }

    /** 修复器桩：固定返回给定结果，并记录被调用次数。 */
    private static final class StubRepair implements FailureRepair {
        private final Outcome outcome;
        private int calls;

        private StubRepair(Outcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public boolean matches(Diagnosis d) {
            return true;
        }

        @Override
        public Outcome repair(Diagnosis d, Context ctx) {
            calls++;
            return outcome;
        }
    }

    private static final class Host implements AutoRepairCoordinator.Host {
        private final List<String> lines = new ArrayList<>();
        private final List<String> resumed = new ArrayList<>();
        private final boolean cancel;
        private final int successesBeforeFailure;
        private int resumeCalls;

        private Host(boolean cancel, int successesBeforeFailure) {
            this.cancel = cancel;
            this.successesBeforeFailure = successesBeforeFailure;
        }

        @Override
        public FailureRepair.Diagnosis diagnosis(int attempt) {
            return AutoRepairCoordinatorTest.diagnosis(attempt);
        }

        @Override
        public FailureRepair.Context context(int attempt) {
            return AutoRepairCoordinatorTest.context();
        }

        @Override
        public void log(String line) {
            lines.add(line);
        }

        @Override
        public boolean cancelled() {
            return cancel;
        }

        @Override
        public boolean resume(String stageId) {
            resumed.add(stageId);
            return resumeCalls++ < successesBeforeFailure;
        }
    }

    @Test
    @DisplayName("修复经复核通过 → 自动续跑成功，不再进入下一轮")
    void verifiedRepairResumesAutomatically() {
        StubRepair repair = new StubRepair(FailureRepair.Outcome.fixed("镜像已就绪", List.of("[autofix] ok")));
        Host host = new Host(false, 1);
        AutoRepairCoordinator.Result result = new AutoRepairCoordinator(
                AutoRepairPolicy.defaults(), List.of(repair)).run(host);

        assertTrue(result.succeeded());
        assertTrue(result.resumed());
        assertEquals(1, result.attempts());
        assertEquals(1, repair.calls);
        assertEquals(List.of("build"), host.resumed);
        assertTrue(host.lines.stream().anyMatch(l -> l.contains("无需人工点击重跑")), host.lines.toString());
    }

    @Test
    @DisplayName("修复未经复核 → 绝不续跑，运行保持失败")
    void unverifiedRepairNeverResumes() {
        StubRepair repair = new StubRepair(FailureRepair.Outcome.notFixed("pull 失败", List.of()));
        Host host = new Host(false, 1);
        AutoRepairCoordinator.Result result = new AutoRepairCoordinator(
                AutoRepairPolicy.defaults(), List.of(repair)).run(host);

        assertFalse(result.succeeded());
        assertFalse(result.resumed());
        assertEquals(0, host.resumeCalls);
        assertEquals("pull 失败", result.lastResult());
    }

    @Test
    @DisplayName("续跑后仍失败 → 最多 maxAttempts 轮，不会无限重试")
    void resumeFailureIsBounded() {
        StubRepair repair = new StubRepair(FailureRepair.Outcome.fixed("已验证", List.of()));
        Host host = new Host(false, 0);
        AutoRepairPolicy policy = new AutoRepairPolicy(true, 3, 60_000L, false, true);
        AutoRepairCoordinator.Result result = new AutoRepairCoordinator(policy, List.of(repair)).run(host);

        assertFalse(result.succeeded());
        assertEquals(3, result.attempts());
        assertEquals(3, host.resumeCalls, "每轮只续跑一次");
        assertEquals(3, repair.calls);
    }

    @Test
    @DisplayName("auto-resume 关闭时只验证不重跑；策略关闭时整段跳过")
    void switchesAreRespected() {
        StubRepair repair = new StubRepair(FailureRepair.Outcome.fixed("已验证", List.of()));
        Host noResume = new Host(false, 1);
        AutoRepairCoordinator.Result r1 = new AutoRepairCoordinator(
                new AutoRepairPolicy(true, 2, 60_000L, false, false), List.of(repair)).run(noResume);
        assertFalse(r1.resumed());
        assertEquals(0, noResume.resumeCalls);

        Host disabled = new Host(false, 1);
        AutoRepairCoordinator.Result r2 = new AutoRepairCoordinator(
                AutoRepairPolicy.disabled(), List.of(repair)).run(disabled);
        assertEquals(0, r2.attempts());
        assertEquals(1, repair.calls, "只有第一轮调用了修复器，策略关闭时不得再调用");
    }

    @Test
    @DisplayName("用户取消立即停止自动修复；修复器抛异常也不能带崩运行")
    void cancelAndExceptionAreSafe() {
        Host cancelled = new Host(true, 1);
        StubRepair never = new StubRepair(FailureRepair.Outcome.fixed("ok", List.of()));
        AutoRepairCoordinator.Result r = new AutoRepairCoordinator(
                AutoRepairPolicy.defaults(), List.of(never)).run(cancelled);
        assertEquals(0, r.attempts());
        assertEquals(0, never.calls);

        Host host = new Host(false, 1);
        FailureRepair throwing = new FailureRepair() {
            @Override
            public boolean matches(Diagnosis d) {
                return true;
            }

            @Override
            public Outcome repair(Diagnosis d, Context ctx) {
                throw new IllegalStateException("模拟修复器故障");
            }
        };
        AutoRepairCoordinator.Result r2 = new AutoRepairCoordinator(
                AutoRepairPolicy.defaults(), List.of(throwing)).run(host);
        assertFalse(r2.succeeded());
        assertTrue(host.lines.stream().anyMatch(l -> l.contains("模拟修复器故障")), host.lines.toString());
    }
}
