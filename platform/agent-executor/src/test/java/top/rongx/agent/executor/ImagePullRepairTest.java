package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.image.ImageReadiness;
import top.rongx.agent.domain.image.ImageReadinessStore;

/**
 * 镜像自愈：只拉管理员配置的镜像、必须由平台 inspect 复核，且与动作前预检共用同一套
 * 就绪策略（超时判据 / 重试 / 镜像源 / 退避）。
 */
class ImagePullRepairTest {

    private static final String CONFIGURED = "maven:3.9-eclipse-temurin-21";
    private static final List<String> PULL = List.of("docker", "pull", CONFIGURED);
    private static final List<String> INSPECT = List.of("docker", "image", "inspect", CONFIGURED);
    private static final long NOW = 1_000_000_000L;

    private static final class Recorder implements DockerSandbox.CommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();
        Function<List<String>, ProcessExecutor.StepOutcome> handler =
                cmd -> ProcessExecutor.StepOutcome.ok();
        String output = "Pulling fs layer\n";

        @Override
        public ProcessExecutor.StepOutcome run(List<String> cmd, long timeoutMs,
                                               BooleanSupplier cancelled, Consumer<String> live) {
            commands.add(cmd);
            if (live != null && output != null && !output.isEmpty()) {
                live.accept(output);
            }
            return handler.apply(cmd);
        }

        @Override
        public ProcessExecutor.StepOutcome runUntilIdle(List<String> cmd, long idleMs, long totalMs,
                                                        BooleanSupplier cancelled,
                                                        Consumer<String> live) {
            return run(cmd, totalMs, cancelled, live);
        }

        long pulls() {
            return commands.stream().filter(c -> c.contains("pull")).count();
        }
    }

    private static final class MemoryStore implements ImageReadinessStore {
        final Map<String, ImageReadiness> rows = new HashMap<>();

        @Override
        public Optional<ImageReadiness> find(String host, String image) {
            return Optional.ofNullable(rows.get(host + "|" + image));
        }

        @Override
        public void save(ImageReadiness readiness) {
            rows.put(readiness.host() + "|" + readiness.image(), readiness);
        }

        @Override
        public List<ImageReadiness> list(String host) {
            return rows.values().stream().filter(r -> r.host().equals(host)).toList();
        }

        @Override
        public List<ImageReadiness> listAll() {
            return List.copyOf(rows.values());
        }
    }

    private static FailureRepair.Diagnosis diagnosis(String image) {
        return new FailureRepair.Diagnosis("run-1", "build", "E-209",
                "构建镜像未就绪（" + image + "）", "[build] No such image: " + image,
                image, 1, true);
    }

    private static FailureRepair.Context context(Recorder recorder, boolean cancelled,
                                                 ImageReadinessStore store) {
        ImageProvisioner provisioner = recorder == null ? null
                : new ImageProvisioner("docker", CONFIGURED, "host-a", recorder, store,
                new ImageProvisioner.Policy(60_000L, 900_000L, 2, 60_000L, 900_000L, List.of()),
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        return new FailureRepair.Context(() -> cancelled, line -> {
        }, (argv, timeoutMs, live) -> recorder == null
                ? ProcessExecutor.StepOutcome.ok() : recorder.run(argv, timeoutMs, () -> false, live),
                60_000L, PULL, INSPECT, CONFIGURED, null,
                "P", "test", "verify", "abcdef1", "ui:admin", "", () -> false, provisioner);
    }

    private static ProcessExecutor.StepOutcome failed(String summary) {
        return ProcessExecutor.StepOutcome.failed(1, "E-101", summary);
    }

    private static ProcessExecutor.StepOutcome ok() {
        return ProcessExecutor.StepOutcome.ok();
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
                .repair(diagnosis("evil:latest"), context(recorder, false, new MemoryStore()));

        assertFalse(outcome.verified());
        assertTrue(recorder.commands.isEmpty(), "不得把日志里的镜像名拼进命令");
        assertTrue(outcome.summary().contains("防提示注入"), outcome.summary());
    }

    @Test
    @DisplayName("镜像已在本机 → 不误报已修复，交给后续诊断")
    void alreadyPresentHandsOff() {
        Recorder recorder = new Recorder();
        FailureRepair.Outcome outcome = new ImagePullRepair()
                .repair(diagnosis(CONFIGURED), context(recorder, false, new MemoryStore()));

        assertFalse(outcome.verified());
        assertEquals(List.of(INSPECT), recorder.commands, "已在本机时不许重复 pull");
        assertTrue(outcome.summary().contains("另有原因"), outcome.summary());
    }

    @Test
    @DisplayName("inspect 未命中 → pull + 复核成功 → 续跑，且命令全部来自配置")
    void pullThenInspectVerified() {
        Recorder recorder = new Recorder();
        java.util.concurrent.atomic.AtomicInteger inspects =
                new java.util.concurrent.atomic.AtomicInteger();
        recorder.handler = cmd -> cmd.contains("inspect") && inspects.getAndIncrement() == 0
                ? failed("no such image") : ok();
        FailureRepair.Outcome outcome = new ImagePullRepair()
                .repair(diagnosis(CONFIGURED), context(recorder, false, null));

        assertTrue(outcome.verified(), outcome.summary());
        assertEquals(List.of(INSPECT, PULL, INSPECT), recorder.commands);
        assertTrue(outcome.lines().stream().anyMatch(l -> l.contains("docker image inspect 复核")));
    }

    @Test
    @DisplayName("pull 自称成功但 inspect 失败 → 仍判定未修复（不采信子进程输出）")
    void pullSuccessIsNotEnough() {
        Recorder recorder = new Recorder();
        recorder.handler = cmd -> cmd.contains("inspect") ? failed("no such image") : ok();
        FailureRepair.Outcome outcome = new ImagePullRepair()
                .repair(diagnosis(CONFIGURED), context(recorder, false, new MemoryStore()));

        assertFalse(outcome.verified());
        assertEquals(1, recorder.pulls(), "pull 成功后必须复核，复核失败即未修复");
        assertTrue(outcome.lines().stream().anyMatch(l -> l.contains("自动拉取失败"))
                        || outcome.summary().contains("未收敛"),
                outcome.summary() + " " + outcome.lines());
    }

    @Test
    @DisplayName("退避窗口内不再重复拉取：直接复用上次结论，不让每次运行都白等")
    void backoffWindowSkipsPull() {
        MemoryStore store = new MemoryStore();
        store.save(new ImageReadiness("host-a", CONFIGURED, ImageReadiness.Status.FAILED,
                null, null, 1, "所有镜像层已下载完成，卡在解压/落盘",
                NOW + 300_000L, NOW - 1_000L));
        Recorder recorder = new Recorder();
        recorder.handler = cmd -> failed("no such image");
        ImagePullRepair repair = new ImagePullRepair();

        FailureRepair.Diagnosis tried = new FailureRepair.Diagnosis("run-1", "build", "E-209",
                "构建镜像未就绪（" + CONFIGURED + "）：自动拉取失败（E-302）",
                "[sandbox] 自动拉取失败", CONFIGURED, 1, true, true);

        assertTrue(repair.matches(tried), "预检失败后也要允许修复器出手（由退避状态决定值不值得）");
        FailureRepair.Outcome outcome = repair.repair(tried, context(recorder, false, store));
        assertFalse(outcome.verified());
        assertEquals(0, recorder.pulls(), "退避期内不得重复执行 docker pull");
        assertTrue(outcome.lines().stream().anyMatch(l -> l.contains("退避")),
                String.valueOf(outcome.lines()));
    }

    @Test
    @DisplayName("pull 失败 / 已取消 / 非容器模式 → 都不续跑")
    void failureCancelAndNonDockerAreSafe() {
        Recorder failing = new Recorder();
        failing.handler = cmd -> failed("no such image");
        assertFalse(new ImagePullRepair()
                .repair(diagnosis(CONFIGURED), context(failing, false, new MemoryStore())).verified());
        assertTrue(failing.commands.get(failing.commands.size() - 1).contains("pull"),
                "拉取失败后不必再跑复核：" + failing.commands);

        Recorder cancelled = new Recorder();
        assertFalse(new ImagePullRepair()
                .repair(diagnosis(CONFIGURED), context(cancelled, true, new MemoryStore())).verified());
        assertTrue(cancelled.commands.isEmpty());

        Recorder noSandbox = new Recorder();
        assertFalse(new ImagePullRepair()
                .repair(diagnosis(CONFIGURED), context(null, false, new MemoryStore())).verified());
        assertTrue(noSandbox.commands.isEmpty());
    }
}
