package top.rongx.agent.executor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 镜像自愈：只拉管理员配置的镜像，且必须由平台 inspect 复核，不采信 pull 输出。 */
class ImagePullRepairTest {

    private static final String CONFIGURED = "maven:3.9-eclipse-temurin-21";
    private static final List<String> PULL = List.of("docker", "pull", CONFIGURED);
    private static final List<String> INSPECT = List.of("docker", "image", "inspect", CONFIGURED);

    private static final class Recorder implements FailureRepair.CommandRunner {
        private final List<List<String>> commands = new ArrayList<>();
        private final Deque<ProcessExecutor.StepOutcome> outcomes = new ArrayDeque<>();

        @Override
        public ProcessExecutor.StepOutcome run(List<String> argv, long timeoutMs,
                                               Consumer<String> live) {
            commands.add(argv);
            if (live != null) {
                live.accept("Pulling fs layer\n");
            }
            return outcomes.isEmpty() ? ProcessExecutor.StepOutcome.ok() : outcomes.poll();
        }
    }

    private static FailureRepair.Diagnosis diagnosis(String image) {
        return new FailureRepair.Diagnosis("run-1", "build", "E-209",
                "构建镜像未就绪（" + image + "）", "[build] No such image: " + image,
                image, 1, true);
    }

    private static FailureRepair.Context context(Recorder recorder, boolean cancelled,
                                                 List<String> pull, List<String> inspect) {
        return new FailureRepair.Context(() -> cancelled, line -> {
        }, recorder, 60_000L, pull, inspect, CONFIGURED, null,
                "P", "test", "verify", "abcdef1", "ui:admin", "");
    }

    @Test
    @DisplayName("能从中文错误与 docker 原文里解析缺失镜像名")
    void parsesMissingImage() {
        assertEquals("maven:test", FailureRepair.Diagnosis.missingImage(
                "构建镜像未就绪（maven:test）", ""));
        assertEquals("maven:test", FailureRepair.Diagnosis.missingImage(
                "E-101：命令失败", "[build] Error response from daemon: No such image: maven:test"));
        assertEquals(null, FailureRepair.Diagnosis.missingImage("编译错误", "nothing here"));
    }

    @Test
    @DisplayName("日志里的镜像名与平台配置不一致 → 拒绝执行（防提示注入）")
    void refusesUntrustedImageFromLog() {
        Recorder recorder = new Recorder();
        FailureRepair.Outcome outcome = new ImagePullRepair()
                .repair(diagnosis("evil:latest"), context(recorder, false, PULL, INSPECT));

        assertFalse(outcome.verified());
        assertTrue(recorder.commands.isEmpty(), "不得把日志里的镜像名拼进命令");
        assertTrue(outcome.summary().contains("防提示注入"), outcome.summary());
    }

    @Test
    @DisplayName("pull + inspect 都成功 → 复核通过，且命令全部来自配置")
    void pullThenInspectVerified() {
        Recorder recorder = new Recorder();
        FailureRepair.Outcome outcome = new ImagePullRepair()
                .repair(diagnosis(CONFIGURED), context(recorder, false, PULL, INSPECT));

        assertTrue(outcome.verified(), outcome.summary());
        assertEquals(List.of(PULL, INSPECT), recorder.commands);
        assertTrue(outcome.lines().stream().anyMatch(l -> l.contains("docker image inspect 复核")));
    }

    @Test
    @DisplayName("pull 自称成功但 inspect 失败 → 仍判定未修复（不采信子进程输出）")
    void pullSuccessIsNotEnough() {
        Recorder recorder = new Recorder();
        recorder.outcomes.add(ProcessExecutor.StepOutcome.ok());
        recorder.outcomes.add(ProcessExecutor.StepOutcome.failed(1, "E-101", "no such image"));
        FailureRepair.Outcome outcome = new ImagePullRepair()
                .repair(diagnosis(CONFIGURED), context(recorder, false, PULL, INSPECT));

        assertFalse(outcome.verified());
        assertEquals(2, recorder.commands.size());
        assertTrue(outcome.summary().contains("仍不可用"), outcome.summary());
    }

    @Test
    @DisplayName("动作前预检已自动拉取过且失败 → 不重复拉取同一个镜像")
    void skipsWhenPrecheckAlreadyTriedPull() {
        FailureRepair.Diagnosis tried = new FailureRepair.Diagnosis("run-1", "build", "E-209",
                "构建镜像未就绪（" + CONFIGURED + "）：自动拉取失败（E-101）",
                "[sandbox] 自动拉取失败", CONFIGURED, 1, true, true);
        Recorder recorder = new Recorder();
        ImagePullRepair repair = new ImagePullRepair();

        assertFalse(repair.matches(tried));
        assertFalse(repair.repair(tried, context(recorder, false, PULL, INSPECT)).verified());
        assertTrue(recorder.commands.isEmpty(), "不得重复执行 docker pull");
    }

    @Test
    @DisplayName("pull 失败 / 已取消 / 非容器模式 → 都不续跑")
    void failureCancelAndNonDockerAreSafe() {
        Recorder failed = new Recorder();
        failed.outcomes.add(ProcessExecutor.StepOutcome.failed(1, "E-302", "pull timeout"));
        assertFalse(new ImagePullRepair()
                .repair(diagnosis(CONFIGURED), context(failed, false, PULL, INSPECT)).verified());
        assertEquals(1, failed.commands.size(), "pull 失败后不应再跑 inspect");

        Recorder cancelled = new Recorder();
        assertFalse(new ImagePullRepair()
                .repair(diagnosis(CONFIGURED), context(cancelled, true, PULL, INSPECT)).verified());
        assertTrue(cancelled.commands.isEmpty());

        Recorder noSandbox = new Recorder();
        assertFalse(new ImagePullRepair()
                .repair(diagnosis(CONFIGURED), context(noSandbox, false, null, null)).verified());
        assertTrue(noSandbox.commands.isEmpty());
    }
}
