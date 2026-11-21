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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        final List<Long> idles = new ArrayList<>();
        final List<Boolean> cancelledFlags = new ArrayList<>();
        Function<List<String>, ProcessExecutor.StepOutcome> handler;
        /** 假命令的实时输出：默认回显命令行，测试可改成"下载完成/卡住"之类的真实文本。 */
        Function<List<String>, String> emitter = cmd -> "[fake] " + String.join(" ", cmd) + "\n";

        Recorder(Function<List<String>, ProcessExecutor.StepOutcome> handler) {
            this.handler = handler;
        }

        @Override
        public ProcessExecutor.StepOutcome run(List<String> cmd, long timeoutMs,
                                               BooleanSupplier cancelled, Consumer<String> live) {
            return record(cmd, 0L, timeoutMs, cancelled, live);
        }

        @Override
        public ProcessExecutor.StepOutcome runUntilIdle(List<String> cmd, long idleMs, long totalMs,
                                                        BooleanSupplier cancelled,
                                                        Consumer<String> live) {
            return record(cmd, idleMs, totalMs, cancelled, live);
        }

        private ProcessExecutor.StepOutcome record(List<String> cmd, long idleMs, long totalMs,
                                                   BooleanSupplier cancelled, Consumer<String> live) {
            commands.add(cmd);
            timeouts.add(totalMs);
            idles.add(idleMs);
            cancelledFlags.add(cancelled != null && cancelled.getAsBoolean());
            if (live != null) {
                String text = emitter.apply(cmd);
                if (text != null && !text.isEmpty()) {
                    live.accept(text);
                }
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
        // 卷名 = 可读前缀 + 路径哈希：可读部分便于 docker volume ls 排障，
        // 哈希部分是唯一性来源（原实现只看目录名，同名工程会撞卷）。
        assertTrue(cmd.stream().anyMatch(a -> a.matches("agent-cache-p-[0-9a-f]{16}:/cache")),
                "缓存卷挂载仍要正确生成且带路径哈希：" + cmd);
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

        // 探测 + mkdir + chmod + chown + 标记，第二次应跳过
        // （chmod 是 Windows 卷的放权兜底：chown 在 WSL2/9p 卷上不生效，
        //   没有 777 时构建用户 1000 写 /cache/m2 直接 AccessDenied）
        assertEquals(5, recorder.commands.size(), "探测 + mkdir + chmod + chown + 标记，第二次应跳过");
        assertEquals(List.of(CACHE_INIT_TIMEOUT, CACHE_INIT_TIMEOUT, CACHE_INIT_TIMEOUT,
                        CACHE_INIT_TIMEOUT, CACHE_INIT_TIMEOUT),
                recorder.timeouts);
        assertTrue(recorder.commands.get(0).contains("test"));
        assertTrue(recorder.commands.get(1).contains("mkdir"));
        assertTrue(recorder.commands.get(1).contains("/cache/corepack"),
                "corepack 下载缓存必须是可写卷目录：" + recorder.commands.get(1));
        assertTrue(recorder.commands.get(1).contains("/cache/pnpm"),
                "pnpm store 必须在缓存卷里，否则每次全量重下：" + recorder.commands.get(1));
        assertTrue(recorder.commands.get(2).contains("chmod"),
                "chmod 777 是 Windows 卷的可用放权手段：" + recorder.commands.get(2));
        assertTrue(recorder.commands.get(3).contains("chown"));
        assertTrue(recorder.commands.get(4).contains("touch"));
        assertTrue(live.toString().contains("[sandbox] $ "), "准备命令要写进实时日志");
    }

    @Test
    @DisplayName("初始化容器带生命周期标签与固定名字：否则被杀后 --rm 不生效、清扫也看不见")
    void initContainersAreLabeledAndNamed() {
        Recorder recorder = new Recorder(freshVolume());

        sandbox(recorder).prepareCacheVolume(Path.of("E:/work/P"), () -> false, null);

        // 每一步初始化命令（探测 + mkdir + chmod + chown + touch）都必须带标签和名字：
        // 只有标签没有名字 → CLI 被 kill 时容器残留；只有名字没有标签 → 清扫按
        // MANAGED_LABEL 过滤，永远看不到它。两者缺一都会在 `docker ps -a` 里长期留垃圾。
        assertFalse(recorder.commands.isEmpty(), "初始化必须真的执行了命令");
        for (List<String> cmd : recorder.commands) {
            String joined = String.join(" ", cmd);
            assertTrue(joined.contains("agent.sandbox.managed=true"),
                    "初始化容器必须带生命周期标签：" + joined);
            assertTrue(joined.contains("agent.sandbox.owner="),
                    "初始化容器必须带归属标签（清扫据此判断进程是否已退出）：" + joined);
            assertTrue(cmd.contains("--name"),
                    "初始化容器必须有名字，否则 --rm 失效后无法精确回收：" + joined);
            String name = cmd.get(cmd.indexOf("--name") + 1);
            assertTrue(name.startsWith("agent-act-init-"),
                    "初始化容器名必须在清扫前缀内：" + name);
            assertTrue(name.length() <= 63, "docker 容器名上限 63：" + name);
        }
        // 同一卷的多次初始化不能复用同一个名字（docker run 遇重名会直接失败）
        List<String> names = recorder.commands.stream()
                .filter(c -> c.contains("--name"))
                .map(c -> c.get(c.indexOf("--name") + 1))
                .toList();
        assertEquals(names.size(), names.stream().distinct().count(),
                "同一次准备里的初始化容器名必须互不相同：" + names);
    }

    @Test
    @DisplayName("镜像 inspect 的完整 JSON 不写进运行日志")
    void imageInspectOutputIsNotStreamed() {
        Recorder recorder = new Recorder(cmd -> ok());
        StringBuilder live = new StringBuilder();

        sandbox(recorder).ensureImageReady(() -> false, live::append);

        assertEquals(1, recorder.commands.size());
        assertTrue(recorder.commands.get(0).contains("image"));
        // 就绪结论要写进日志（可观测），但 inspect 的 70+ 行 JSON 不能挤进去
        assertTrue(live.toString().contains("镜像已就绪"), live.toString());
        assertTrue(!live.toString().contains("RepoTags") && !live.toString().contains("\"Id\""),
                "inspect 的完整 JSON 不能进日志：" + live);
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
    @DisplayName("不同项目的缓存卷并行初始化：一个项目的冷启动不再阻塞另一个项目")
    void differentProjectsPrepareInParallel() throws Exception {
        java.util.concurrent.CountDownLatch aInitializing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseA = new java.util.concurrent.CountDownLatch(1);
        Recorder recorder = new Recorder(cmd -> {
            if (isReadyProbe(cmd)) {
                return ProcessExecutor.StepOutcome.failed(1, null, "not found");
            }
            // 项目 A 的初始化（mkdir/chown/touch）一直卡着，模拟大缓存的 chown -R
            if (cmd.stream().anyMatch(a -> a.contains(":/cache"))
                    && String.join(" ", cmd).contains("agent-cache-a-")) {
                aInitializing.countDown();
                try {
                    releaseA.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return ok();
        });
        DockerSandbox sandbox = sandbox(recorder);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> a = pool.submit(
                    () -> sandbox.prepareCacheVolume(Path.of("E:/work/A"), () -> false, null));
            assertTrue(aInitializing.await(5, java.util.concurrent.TimeUnit.SECONDS), "项目 A 应开始初始化");
            java.util.concurrent.Future<?> b = pool.submit(
                    () -> sandbox.prepareCacheVolume(Path.of("E:/work/B"), () -> false, null));

            b.get(5, java.util.concurrent.TimeUnit.SECONDS); // 全局锁时这里会超时
            assertTrue(sandbox.wrap(Path.of("E:/work/B"), List.of("mvn", "-v")).stream()
                    .anyMatch(arg -> arg.startsWith("agent-cache-b-")), "项目 B 的缓存卷独立");

            releaseA.countDown();
            a.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("缓存卷根目录属主不是构建用户：1000 被拒后自动回退 root 再初始化")
    void initFallsBackToRootWhenUserIsForbidden() {
        List<String> attemptedUsers = new ArrayList<>();
        Recorder recorder = new Recorder(cmd -> {
            if (isReadyProbe(cmd)) {
                return ProcessExecutor.StepOutcome.failed(1, null, "not found");
            }
            if (cmd.contains("mkdir")) {
                String user = cmd.get(cmd.indexOf("--user") + 1);
                attemptedUsers.add(user);
                if ("1000:1000".equals(user)) {
                    // 全新卷的 /cache 属主是 root，1000 用户写不了。
                    // 错误码必须是真实的 E-104（busybox mkdir 属 SCRIPT 类）——
                    // 早先实现只对 E-101 回退，于是这条回退分支在生产里从未生效。
                    return ProcessExecutor.StepOutcome.failed(1, "E-104", "mkdir: Permission denied");
                }
            }
            return ok();
        });

        sandbox(recorder).prepareCacheVolume(Path.of("E:/work/P"), () -> false, null);

        assertEquals(List.of("1000:1000", "0:0"), attemptedUsers,
                "被拒后必须换 root 再试一次，而不是直接失败");
        assertTrue(recorder.commands.get(recorder.commands.size() - 1).contains("touch"),
                "回退成功后仍要写完成标记");
    }

    @Test
    @DisplayName("回退判定按「命令失败」而非特定错误码：E-101 与 E-104 都要回退，超时/取消不回退")
    void rootFallbackCoversAllCommandFailures() {
        // 回归背景：原实现写死只认 E-101，而初始化容器的失败是 E-104，
        // 结果冷卷（属主 root）永远初始化失败。这里把三种真实错误码都钉住。
        for (String code : List.of("E-101", "E-104", "E-209")) {
            List<String> users = new ArrayList<>();
            Recorder recorder = new Recorder(cmd -> {
                if (isReadyProbe(cmd)) {
                    return ProcessExecutor.StepOutcome.failed(1, null, "not found");
                }
                if (cmd.contains("mkdir")) {
                    String user = cmd.get(cmd.indexOf("--user") + 1);
                    users.add(user);
                    return "1000:1000".equals(user)
                            ? ProcessExecutor.StepOutcome.failed(1, code, "denied")
                            : ok();
                }
                return ok();
            });

            sandbox(recorder).prepareCacheVolume(Path.of("E:/work/" + code.replace("-", "")),
                    () -> false, null);

            assertEquals(List.of("1000:1000", "0:0"), users, code + " 必须触发 root 回退");
        }

        // 超时换用户也没用：不应把等待时间翻倍
        List<String> onTimeout = new ArrayList<>();
        Recorder recorder = new Recorder(cmd -> {
            if (isReadyProbe(cmd)) {
                return ProcessExecutor.StepOutcome.failed(1, null, "not found");
            }
            onTimeout.add(cmd.get(cmd.indexOf("--user") + 1));
            return ProcessExecutor.StepOutcome.failed(124, "E-302", "执行超时");
        });
        assertThrows(PlatformException.class,
                () -> sandbox(recorder).prepareCacheVolume(Path.of("E:/work/TO"), () -> false, null));
        assertTrue(onTimeout.stream().noneMatch("0:0"::equals), "超时不应触发 root 重试");
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
        // 3（失败轮：探测 + mkdir as1000 超时即抛）
        // + 5（成功轮：探测 + mkdir + chmod + chown + 标记）= 8
        assertEquals(8, recorder.commands.size(),
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

        assertEquals(3, recorder.commands.size(), "inspect 探测 → pull → inspect 复核（未接状态存储时不取 digest）");
        assertEquals(List.of("docker", "image", "inspect", "maven:3.9-eclipse-temurin-21"),
                recorder.commands.get(0));
        assertEquals(5_000L, recorder.timeouts.get(0));
        assertEquals(List.of("docker", "pull", "maven:3.9-eclipse-temurin-21"),
                recorder.commands.get(1));
        assertEquals(List.of("docker", "image", "inspect", "maven:3.9-eclipse-temurin-21"),
                recorder.commands.get(2), "pull 的退出码不算数，必须 inspect 复核");
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
        assertEquals(900_000L, recorder.timeouts.get(1), "总上限 900s 只在一直有进度时兜底");
        assertEquals(60_000L, recorder.idles.get(1), "判定卡死看静默阈值，不是固定总预算");
    }

    @Test
    @DisplayName("预检可关闭（离线 / 自建 registry 自行负责镜像）")
    void imagePrecheckCanBeDisabled() {
        Recorder recorder = new Recorder(cmd -> ok());
        sandbox(recorder).setImagePrecheck(false).ensureImageReady(() -> false, null);
        assertTrue(recorder.commands.isEmpty());
    }

    @Test
    @DisplayName("层已下载完但没解压完：诊断指向解压/落盘，而不是让人去查 registry")
    void pullTimeoutAfterDownloadBlamesExtraction() {
        Recorder recorder = new Recorder(cmd -> cmd.contains("pull")
                ? ProcessExecutor.StepOutcome.failed(124, "E-302", "执行超时（900s，总时长已达上限），已终止")
                : ProcessExecutor.StepOutcome.failed(1, "E-101", "no such image"));
        // 复刻 run-66c5c523 的日志形态：全部层 Download complete，没有一个 Pull complete
        recorder.emitter = cmd -> cmd.contains("pull")
                ? "450e9cb70a64: Pulling fs layer\n450e9cb70a64: Download complete\n"
                + "69245854ad58: Download complete\n"
                : "";
        DockerSandbox sandbox = sandbox(recorder);

        PlatformException error = assertThrows(PlatformException.class,
                () -> sandbox.ensureImageReady(() -> false, null));
        assertTrue(error.getMessage().contains("卡在解压/落盘"), error.getMessage());
        assertTrue(error.getMessage().contains("会复用"), "要告诉操作者重试不会从头再下：" + error.getMessage());
        assertTrue(!error.getMessage().contains("检查网络"),
                "已下完还让人查网络就是把根因指错：" + error.getMessage());
    }

    @Test
    @DisplayName("没有任何层开始下载：诊断指向网络/镜像源")
    void pullFailureWithoutProgressBlamesNetwork() {
        Recorder recorder = new Recorder(cmd -> ProcessExecutor.StepOutcome.failed(
                124, "E-302", "执行停滞（60s 无输出），已终止"));
        recorder.emitter = cmd -> cmd.contains("pull") ? "" : "";
        DockerSandbox sandbox = sandbox(recorder);

        PlatformException error = assertThrows(PlatformException.class,
                () -> sandbox.ensureImageReady(() -> false, null));
        assertTrue(error.getMessage().contains("没有任何镜像层开始下载"), error.getMessage());
        assertTrue(error.getMessage().contains("agent.sandbox.pull-mirrors"), error.getMessage());
    }

    @Test
    @DisplayName("直连拉取失败后走配置的镜像加速源，并 tag 回原名")
    void mirrorIsUsedWhenDirectPullFails() {
        java.util.concurrent.atomic.AtomicInteger inspects =
                new java.util.concurrent.atomic.AtomicInteger();
        Recorder recorder = new Recorder(cmd -> {
            String line = String.join(" ", cmd);
            if (line.contains("inspect")) {
                // 第一次（预检）没有镜像；tag 之后再复核才通过
                return inspects.getAndIncrement() == 0
                        ? ProcessExecutor.StepOutcome.failed(1, "E-101", "no such image")
                        : ok();
            }
            if (line.contains("pull") && line.contains("docker.m.daocloud.io")) {
                return ok();
            }
            if (line.contains("pull")) {
                return ProcessExecutor.StepOutcome.failed(124, "E-302", "执行停滞（60s 无输出），已终止");
            }
            return ok();
        });
        DockerSandbox sandbox = sandbox(recorder).setPullMirrors(List.of("docker.m.daocloud.io"));

        sandbox.ensureImageReady(() -> false, null);
        assertTrue(recorder.commands.contains(
                        List.of("docker", "pull", "docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21")),
                "加速源应按 library/ 补齐官方镜像路径：" + recorder.commands);
        assertTrue(recorder.commands.contains(
                        List.of("docker", "tag", "docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21",
                                "maven:3.9-eclipse-temurin-21")),
                "拉下来的镜像必须 tag 回原名，后续阶段才无感：" + recorder.commands);
    }

    @Test
    @DisplayName("容器清理也带超时，不会用 readAllBytes 永久卡住收尾")
    void endContainerIsBounded() {
        Recorder recorder = new Recorder(cmd -> ok());
        sandbox(recorder).endContainer("agent-act-abc");
        assertEquals(List.of("docker", "rm", "-f", "agent-act-abc"), recorder.commands.get(0));
        assertEquals(30_000L, recorder.timeouts.get(0));
    }

    @Test
    @DisplayName("缓存卷初始化容器同样受限，且用可信最小镜像而非构建镜像")
    void rootInitContainerIsHardened() {
        Recorder recorder = new Recorder(freshVolume());
        DockerSandbox sandbox = sandbox(recorder);

        sandbox.prepareCacheVolume(Path.of("E:/work/P"), () -> false, null);

        // 找到真正的初始化命令（mkdir），它属于 root 容器
        List<String> init = recorder.commands.stream()
                .filter(c -> c.contains("mkdir")).findFirst().orElseThrow();
        String joined = String.join(" ", init);
        assertTrue(joined.contains("--network none"), joined);
        assertTrue(joined.contains("--cap-drop ALL"), joined);
        assertTrue(joined.contains("no-new-privileges"), joined);
        assertTrue(joined.contains("--pids-limit 64"), joined);
        // 初始化绝不能跑构建镜像（可能是不可信代码），而是固定可信最小镜像
        assertTrue(joined.contains("busybox:1.36"), joined);
        assertTrue(!joined.contains("maven"), joined);
    }
}
