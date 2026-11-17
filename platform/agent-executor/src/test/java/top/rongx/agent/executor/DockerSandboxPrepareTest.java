package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import top.rongx.agent.domain.error.PlatformException;

/**
 * 准备阶段回归：缓存卷初始化曾经藏在 {@code wrap()} 里无限等待 docker CLI，
 * 超时失效、异常被吞、卷还被误标记为已初始化。这里用注入的命令执行器验证契约。
 */
class DockerSandboxPrepareTest {

    private static final long CACHE_INIT_TIMEOUT = 90_000L;

    private static final class Recorder implements DockerSandbox.CommandExecutor {
        final List<List<String>> commands = new ArrayList<>();
        final List<Long> timeouts = new ArrayList<>();
        final List<Boolean> cancelledFlags = new ArrayList<>();
        Function<List<String>, ProcessExecutor.StepOutcome> handler;

        Recorder(Function<List<String>, ProcessExecutor.StepOutcome> handler) {
            this.handler = handler;
        }

        @Override
        public ProcessExecutor.StepOutcome run(List<String> cmd, long timeoutMs,
                                               BooleanSupplier cancelled, Consumer<String> live) {
            commands.add(cmd);
            timeouts.add(timeoutMs);
            cancelledFlags.add(cancelled != null && cancelled.getAsBoolean());
            if (live != null) {
                live.accept("[fake] " + String.join(" ", cmd) + "\n");
            }
            return handler.apply(cmd);
        }
    }

    private static DockerSandbox sandbox(Recorder recorder) {
        return new DockerSandbox("docker", "maven:3.9-eclipse-temurin-21", "bridge",
                "2g", "2", 512, "1000:1000", "agent-cache", recorder);
    }

    private static ProcessExecutor.StepOutcome ok() {
        return ProcessExecutor.StepOutcome.ok();
    }

    private static ProcessExecutor.StepOutcome timeout() {
        return ProcessExecutor.StepOutcome.failed(124, "E-302", "执行超时（30s），已终止");
    }

    /** 完成标记探测：空卷上 test -f 必须返回非 0，才会真正执行初始化。 */
    private static boolean isReadyProbe(List<String> cmd) {
        return cmd.contains("test");
    }

    private static Function<List<String>, ProcessExecutor.StepOutcome> freshVolume() {
        return cmd -> isReadyProbe(cmd)
                ? ProcessExecutor.StepOutcome.failed(1, null, "not found")
                : ok();
    }

    @Test
    @DisplayName("wrap 是纯函数：不再偷偷启动缓存卷初始化进程")
    void wrapDoesNotStartProcesses() {
        Recorder recorder = new Recorder(cmd -> ok());
        DockerSandbox sandbox = sandbox(recorder);
        List<String> cmd = sandbox.wrap(Path.of("E:/work/P"), List.of("mvn", "-v"));

        assertTrue(recorder.commands.isEmpty(), "命令构造不应有任何副作用");
        assertTrue(cmd.contains("agent-cache-p:/cache"), "缓存卷挂载仍要正确生成：" + cmd);
        assertTrue(cmd.contains("COREPACK_HOME=/cache/corepack"),
                "有缓存卷时 corepack 必须用卷内目录：" + cmd);
    }

    @Test
    @DisplayName("准备阶段每步带 90s 超时，且成功后幂等")
    void prepareIsBoundedAndIdempotent() {
        Recorder recorder = new Recorder(freshVolume());
        DockerSandbox sandbox = sandbox(recorder);
        StringBuilder live = new StringBuilder();

        sandbox.prepareCacheVolume(Path.of("E:/work/P"), () -> false, live::append);
        sandbox.prepareCacheVolume(Path.of("E:/work/P"), () -> false, live::append);

        assertEquals(4, recorder.commands.size(), "探测 + mkdir + chown + 标记，第二次应跳过");
        assertEquals(List.of(CACHE_INIT_TIMEOUT, CACHE_INIT_TIMEOUT, CACHE_INIT_TIMEOUT, CACHE_INIT_TIMEOUT),
                recorder.timeouts);
        assertTrue(recorder.commands.get(0).contains("test"));
        assertTrue(recorder.commands.get(1).contains("mkdir"));
        assertTrue(recorder.commands.get(1).contains("/cache/corepack"),
                "corepack 下载缓存必须是可写卷目录：" + recorder.commands.get(1));
        assertTrue(recorder.commands.get(1).contains("/cache/pnpm"),
                "pnpm store 必须在缓存卷里，否则每次全量重下：" + recorder.commands.get(1));
        assertTrue(recorder.commands.get(2).contains("chown"));
        assertTrue(recorder.commands.get(3).contains("touch"));
        assertTrue(live.toString().contains("[sandbox] $ "), "准备命令要写进实时日志");
    }

    @Test
    @DisplayName("镜像 inspect 的完整 JSON 不写进运行日志")
    void imageInspectOutputIsNotStreamed() {
        Recorder recorder = new Recorder(cmd -> ok());
        StringBuilder live = new StringBuilder();

        sandbox(recorder).ensureImageReady(() -> false, live::append);

        assertEquals(1, recorder.commands.size());
        assertTrue(recorder.commands.get(0).contains("image"));
        assertEquals("", live.toString(), "inspect 只做静默判断，70+ 行 JSON 不能挤进日志");
    }

    @Test
    @DisplayName("完成标记已存在：进程重启后不再重扫整块缓存")
    void readyMarkerSkipsChown() {
        Recorder recorder = new Recorder(cmd -> ok());
        sandbox(recorder).prepareCacheVolume(Path.of("E:/work/P"), () -> false, null);

        assertEquals(1, recorder.commands.size(), "有完成标记时只做一次探测");
    }

    @Test
    @DisplayName("缓存卷初始化瞬时超时：自动重试一次后成功")
    void transientTimeoutIsRetried() {
        java.util.concurrent.atomic.AtomicInteger mkdirCalls = new java.util.concurrent.atomic.AtomicInteger();
        Recorder recorder = new Recorder(cmd -> {
            if (isReadyProbe(cmd)) {
                return ProcessExecutor.StepOutcome.failed(1, null, "not found");
            }
            if (cmd.contains("mkdir") && mkdirCalls.getAndIncrement() == 0) {
                return timeout();
            }
            return ok();
        });

        sandbox(recorder).prepareCacheVolume(Path.of("E:/work/P"), () -> false, null);

        assertEquals(2, mkdirCalls.get(), "第一次超时后应自动重试");
        assertTrue(recorder.commands.get(recorder.commands.size() - 1).contains("touch"),
                "重试成功后仍要写完成标记");
    }

    @Test
    @DisplayName("准备失败不缓存卷：下一次动作必须重新初始化")
    void failedPrepareIsNotCached() {
        Recorder recorder = new Recorder(cmd -> timeout());
        DockerSandbox sandbox = sandbox(recorder);

        PlatformException error = assertThrows(PlatformException.class,
                () -> sandbox.prepareCacheVolume(Path.of("E:/work/P"), () -> false, null));
        assertTrue(error.getMessage().contains("缓存卷初始化失败"), error.getMessage());
        assertTrue(error.getMessage().contains("E-302"), error.getMessage());
        assertEquals(3, recorder.commands.size(), "探测 + 两次重试后放弃");

        recorder.handler = freshVolume();
        sandbox.prepareCacheVolume(Path.of("E:/work/P"), () -> false, null);
        assertEquals(7, recorder.commands.size(),
                "失败后不能把卷标记为已初始化，否则后续构建会误用 root 属主的缓存");
    }

    @Test
    @DisplayName("取消标志能传到准备阶段，不再是无法中断的盲等")
    void cancellationReachesPrepare() {
        Recorder recorder = new Recorder(freshVolume());
        sandbox(recorder).prepareCacheVolume(Path.of("E:/work/P"), () -> true, null);
        assertTrue(recorder.cancelledFlags.stream().allMatch(Boolean::booleanValue),
                "每条准备命令都要带上取消检查");
        assertTrue(recorder.cancelledFlags.size() >= 2);
    }

    @Test
    @DisplayName("镜像缺失：自动 pull 后 inspect 复核通过，不再要求人工 pull")
    void imagePrecheckPullsAndVerifies() {
        java.util.concurrent.atomic.AtomicInteger inspects = new java.util.concurrent.atomic.AtomicInteger();
        Recorder recorder = new Recorder(cmd -> {
            if (cmd.contains("inspect")) {
                return inspects.getAndIncrement() == 0
                        ? ProcessExecutor.StepOutcome.failed(1, "E-101", "no such image")
                        : ok();
            }
            return ok();
        });
        DockerSandbox sandbox = sandbox(recorder);

        sandbox.ensureImageReady(() -> false, null);

        assertEquals(3, recorder.commands.size(), "inspect → pull → inspect");
        assertEquals(List.of("docker", "image", "inspect", "maven:3.9-eclipse-temurin-21"),
                recorder.commands.get(0));
        assertEquals(5_000L, recorder.timeouts.get(0));
        assertEquals(List.of("docker", "pull", "maven:3.9-eclipse-temurin-21"),
                recorder.commands.get(1));
        assertEquals(List.of("docker", "image", "inspect", "maven:3.9-eclipse-temurin-21"),
                recorder.commands.get(2));
    }

    @Test
    @DisplayName("自动拉取失败：给出 registry / 换镜像的可操作失败")
    void imagePrecheckFailsWithPullError() {
        Recorder recorder = new Recorder(cmd -> ProcessExecutor.StepOutcome.failed(
                1, "E-101", "命令失败（exit 1）"));
        DockerSandbox sandbox = sandbox(recorder);

        PlatformException error = assertThrows(PlatformException.class,
                () -> sandbox.ensureImageReady(() -> false, null));
        assertTrue(error.getMessage().contains("构建镜像未就绪"), error.getMessage());
        assertTrue(error.getMessage().contains("自动拉取失败"), error.getMessage());
        assertTrue(error.getMessage().contains("agent.sandbox.image"), error.getMessage());
        assertEquals(List.of("docker", "pull", "maven:3.9-eclipse-temurin-21"),
                recorder.commands.get(1));
        assertEquals(CACHE_INIT_TIMEOUT * 2, recorder.timeouts.get(1), "默认拉取预算 180s");
    }

    @Test
    @DisplayName("预检可关闭（离线 / 自建 registry 自行负责镜像）")
    void imagePrecheckCanBeDisabled() {
        Recorder recorder = new Recorder(cmd -> ok());
        sandbox(recorder).setImagePrecheck(false).ensureImageReady(() -> false, null);
        assertTrue(recorder.commands.isEmpty());
    }

    @Test
    @DisplayName("容器清理也带超时，不会用 readAllBytes 永久卡住收尾")
    void endContainerIsBounded() {
        Recorder recorder = new Recorder(cmd -> ok());
        sandbox(recorder).endContainer("agent-act-abc");
        assertEquals(List.of("docker", "rm", "-f", "agent-act-abc"), recorder.commands.get(0));
        assertEquals(30_000L, recorder.timeouts.get(0));
    }
}
