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

/** Docker 守护进程修复：先探测、再启动、最后由平台 docker version 复核。 */
class DockerDaemonRepairTest {

    private static final List<String> INSPECT = List.of("docker", "image", "inspect", "maven:cfg");
    private static final List<String> PROBE =
            List.of("docker", "version", "--format", "{{.Server.Version}}");

    private static final class Recorder implements FailureRepair.CommandRunner {
        private final List<List<String>> commands = new ArrayList<>();
        private final Deque<ProcessExecutor.StepOutcome> outcomes = new ArrayDeque<>();

        @Override
        public ProcessExecutor.StepOutcome run(List<String> argv, long timeoutMs,
                                               Consumer<String> live) {
            commands.add(argv);
            return outcomes.isEmpty() ? ProcessExecutor.StepOutcome.ok() : outcomes.poll();
        }
    }

    private static FailureRepair.Diagnosis diagnosis(String logTail) {
        return new FailureRepair.Diagnosis("run-1", "build", "E-209",
                "构建镜像未就绪（maven:cfg）", logTail, "maven:cfg", 1, true);
    }

    private static FailureRepair.Context context(Recorder recorder, boolean cancelled) {
        return new FailureRepair.Context(() -> cancelled, line -> {
        }, recorder, 60_000L, null, INSPECT, "maven:cfg", null,
                "P", "test", "verify", "abcdef1", "ui:admin", "", () -> false);
    }

    @Test
    @DisplayName("只有守护进程不可用的日志才匹配")
    void matchesDaemonDownOnly() {
        DockerDaemonRepair repair = new DockerDaemonRepair();
        assertTrue(repair.matches(diagnosis(
                "Cannot connect to the Docker daemon at unix:///var/run/docker.sock")));
        assertTrue(repair.matches(diagnosis("error during connect: open //./pipe/docker_engine")));
        assertFalse(repair.matches(diagnosis("No such image: maven:cfg")));
    }

    @Test
    @DisplayName("守护进程已经可用时不自作主张启动")
    void alreadyRunningHandsOff() {
        Recorder recorder = new Recorder(); // 默认第一条命令成功
        FailureRepair.Outcome outcome = new DockerDaemonRepair()
                .repair(diagnosis("Cannot connect to the Docker daemon"), context(recorder, false));

        assertFalse(outcome.verified());
        assertEquals(List.of(PROBE), recorder.commands);
        assertTrue(outcome.summary().contains("另有原因"), outcome.summary());
    }

    @Test
    @DisplayName("启动 Docker 后由 docker version 复核通过才允许续跑")
    void startsThenVerified() {
        Recorder recorder = new Recorder();
        recorder.outcomes.add(ProcessExecutor.StepOutcome.failed(1, "E-101", "cannot connect"));
        recorder.outcomes.add(ProcessExecutor.StepOutcome.ok());
        recorder.outcomes.add(ProcessExecutor.StepOutcome.ok());

        FailureRepair.Outcome outcome = new DockerDaemonRepair()
                .repair(diagnosis("Cannot connect to the Docker daemon"), context(recorder, false));

        assertTrue(outcome.verified(), outcome.summary());
        assertEquals(3, recorder.commands.size());
        assertEquals(PROBE, recorder.commands.get(0));
        assertEquals(PROBE, recorder.commands.get(2));
        assertFalse(recorder.commands.get(1).equals(PROBE), "中间必须是启动命令");
        assertTrue(outcome.lines().stream().anyMatch(l -> l.contains("docker version 复核")));
    }

    @Test
    @DisplayName("启动命令本身失败时立即给出下一步，不空等 100 秒")
    void startFailureFailsFast() {
        Recorder recorder = new Recorder();
        recorder.outcomes.add(ProcessExecutor.StepOutcome.failed(1, "E-101", "cannot connect"));
        recorder.outcomes.add(ProcessExecutor.StepOutcome.failed(1, "E-101",
                "docker: 'desktop' is not a docker command"));

        FailureRepair.Outcome outcome = new DockerDaemonRepair()
                .repair(diagnosis("Cannot connect to the Docker daemon"), context(recorder, false));

        assertFalse(outcome.verified());
        assertEquals(2, recorder.commands.size(), "启动失败后不再轮询 docker version");
        assertTrue(outcome.summary().contains("手动启动"), outcome.summary());
    }

    @Test
    @DisplayName("非 Windows/macOS 不猜启动命令；取消后不执行任何命令")
    void safeOnOtherPlatformsAndCancel() {
        assertTrue(DockerDaemonRepair.startCommand("Linux", "docker").isEmpty());
        assertEquals(List.of("docker", "desktop", "start"),
                DockerDaemonRepair.startCommand("Windows 11", "docker"));
        assertEquals(List.of("open", "-a", "Docker"),
                DockerDaemonRepair.startCommand("Mac OS X", "docker"));

        Recorder mine = new Recorder();
        FailureRepair.Outcome outcome = new DockerDaemonRepair()
                .repair(diagnosis("Cannot connect to the Docker daemon"), context(mine, true));
        assertFalse(outcome.verified());
        assertTrue(mine.commands.isEmpty());
    }
}
