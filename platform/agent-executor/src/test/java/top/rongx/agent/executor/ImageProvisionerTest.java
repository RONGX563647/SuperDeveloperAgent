package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.image.ImageReadiness;
import top.rongx.agent.domain.image.ImageReadinessStore;

/**
 * 镜像就绪策略：判活看进度、失败要退避、源链回退、只信复核。
 *
 * <p>全部用假命令执行器驱动，覆盖 run-66c5c523 的失败形态（层下完但被总预算砍掉）
 * 与「下次运行又白等一轮」的重复代价。
 */
class ImageProvisionerTest {

    private static final long NOW = 1_000_000_000L;

    private static final class FakeExec implements DockerSandbox.CommandExecutor {
        final List<List<String>> commands = new ArrayList<>();
        Function<List<String>, ProcessExecutor.StepOutcome> handler =
                cmd -> ProcessExecutor.StepOutcome.ok();
        String output = "";

        @Override
        public ProcessExecutor.StepOutcome run(List<String> cmd, long timeoutMs,
                                               BooleanSupplier cancelled, Consumer<String> live) {
            commands.add(cmd);
            emit(live);
            return handler.apply(cmd);
        }

        @Override
        public ProcessExecutor.StepOutcome runUntilIdle(List<String> cmd, long idleMs, long totalMs,
                                                        BooleanSupplier cancelled,
                                                        Consumer<String> live) {
            commands.add(cmd);
            emit(live);
            return handler.apply(cmd);
        }

        private void emit(Consumer<String> live) {
            if (live != null && output != null && !output.isEmpty()) {
                live.accept(output);
            }
        }

        long pulls() {
            return commands.stream().filter(c -> c.contains("pull")).count();
        }

        boolean ran(String... argv) {
            return commands.contains(List.of(argv));
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
    }

    private static ImageProvisioner provisioner(FakeExec exec, ImageReadinessStore store,
                                                List<String> sources, int attempts) {
        return new ImageProvisioner("docker", "node:22-bookworm", "host-a", exec, store,
                new ImageProvisioner.Policy(60_000L, 900_000L, attempts, 60_000L, 900_000L, sources),
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    private static ProcessExecutor.StepOutcome failed(String summary) {
        return ProcessExecutor.StepOutcome.failed(124, "E-302", summary);
    }

    private static ProcessExecutor.StepOutcome ok() {
        return ProcessExecutor.StepOutcome.ok();
    }

    @Test
    @DisplayName("本机已有镜像：不拉取，直接记 ready")
    void presentImageNeedsNoPull() {
        FakeExec exec = new FakeExec();
        MemoryStore store = new MemoryStore();

        ImageProvisioner.Result result = provisioner(exec, store, List.of(), 2)
                .provision(() -> false, null);

        assertTrue(result.ready());
        assertEquals("local", result.source());
        assertEquals(0, exec.pulls(), "已在本机就不该再 pull");
        assertEquals(ImageReadiness.Status.READY, store.find("host-a", "node:22-bookworm")
                .orElseThrow().status());
    }

    @Test
    @DisplayName("退避窗口内快返上次结论：不再重新等一轮超时")
    void backoffSkipsPull() {
        FakeExec exec = new FakeExec();
        MemoryStore store = new MemoryStore();
        store.save(new ImageReadiness("host-a", "node:22-bookworm", ImageReadiness.Status.FAILED,
                null, null, 3, "所有镜像层已下载完成，卡在解压/落盘", NOW + 300_000L, NOW - 1_000L));
        exec.handler = cmd -> cmd.contains("inspect") ? failed("no such image") : ok();

        ImageProvisioner.Result result = provisioner(exec, store, List.of(), 2)
                .provision(() -> false, null);

        assertFalse(result.ready());
        assertTrue(result.backedOff());
        assertEquals(0, exec.pulls(), "退避期内不该再拉");
        assertTrue(result.diagnosis().contains("卡在解压"), result.diagnosis());
    }

    @Test
    @DisplayName("瞬时失败（有进度/超时）会重试，第二次成功即收敛")
    void transientFailureIsRetried() {
        FakeExec exec = new FakeExec();
        MemoryStore store = new MemoryStore();
        AtomicInteger pulls = new AtomicInteger();
        AtomicInteger inspects = new AtomicInteger();
        exec.handler = cmd -> {
            if (String.join(" ", cmd).contains("inspect")) {
                // 第一次（探测）没镜像；之后 pull 成功，复核与 digest 都通过
                return inspects.getAndIncrement() == 0 ? failed("no such image") : ok();
            }
            return pulls.incrementAndGet() == 1 ? failed("执行超时") : ok();
        };
        exec.output = "450e9cb70a64: Download complete\n";

        ImageProvisioner.Result result = provisioner(exec, store, List.of(), 2)
                .provision(() -> false, null);

        assertTrue(result.ready(), "重试成功后应就绪");
        assertEquals(2, exec.pulls(), "有进度的失败值得再试一次");
    }

    @Test
    @DisplayName("鉴权/不存在类失败不重试：重试只会把失败拖得更久")
    void hopelessFailureIsNotRetried() {
        FakeExec exec = new FakeExec();
        MemoryStore store = new MemoryStore();
        exec.handler = cmd -> cmd.contains("inspect")
                ? failed("no such image")
                : ProcessExecutor.StepOutcome.failed(1, "E-104", "命令失败（exit 1）");
        exec.output = "errors: denied: requested access to the resource is denied\n";

        ImageProvisioner.Result result = provisioner(exec, store, List.of(), 3)
                .provision(() -> false, null);

        assertFalse(result.ready());
        assertEquals(1, exec.pulls(), "鉴权失败不该按次数重试");

        ImageReadiness saved = store.find("host-a", "node:22-bookworm").orElseThrow();
        assertEquals(ImageReadiness.Status.FAILED, saved.status());
        assertTrue(saved.nextRetryAt() > NOW, "失败要写入退避时间，供下次快返");
    }

    @Test
    @DisplayName("直连失败后回退到镜像源，并 tag 回原名（调用方始终用配置镜像名）")
    void fallsBackToConfiguredSource() {
        FakeExec exec = new FakeExec();
        MemoryStore store = new MemoryStore();
        AtomicInteger inspects = new AtomicInteger();
        exec.handler = cmd -> {
            String line = String.join(" ", cmd);
            if (line.contains("inspect")) {
                // 第一次（探测）没有镜像；镜像源就绪后复核与 digest 都通过
                return inspects.getAndIncrement() == 0 ? failed("no such image") : ok();
            }
            if (line.contains("pull") && line.contains("cache.internal:5000")) {
                return ok();
            }
            return line.contains("pull") ? failed("执行停滞") : ok();
        };
        exec.output = "no layer started\n";

        ImageProvisioner.Result result = provisioner(exec, store, List.of("cache.internal:5000"), 1)
                .provision(() -> false, null);

        assertTrue(result.ready(), "镜像源拉取成功即视为就绪");
        assertEquals("mirror:cache.internal:5000", result.source());
        assertTrue(exec.ran("docker", "pull", "cache.internal:5000/library/node:22-bookworm"));
        assertTrue(exec.ran("docker", "tag", "cache.internal:5000/library/node:22-bookworm",
                "node:22-bookworm"), "必须 tag 回原名，后续 docker run 才无感");
    }

    @Test
    @DisplayName("镜像自带 registry 时不乱拼前缀（缓存源只服务 Docker Hub 镜像）")
    void doesNotRewriteExplicitRegistry() {
        FakeExec exec = new FakeExec();
        exec.handler = cmd -> cmd.contains("inspect") ? failed("no such image") : failed("执行停滞");
        ImageProvisioner provisioner = new ImageProvisioner("docker",
                "quay.io/prometheus/node-exporter:1.8", "host-a", exec, new MemoryStore(),
                new ImageProvisioner.Policy(60_000L, 900_000L, 1, 60_000L, 900_000L,
                        List.of("cache.internal:5000")),
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

        provisioner.provision(() -> false, null);

        assertFalse(exec.commands.stream().map(c -> String.join(" ", c))
                        .anyMatch(c -> c.contains("cache.internal:5000")),
                "自带 registry 的镜像不该被改写：" + exec.commands);
    }

    @Test
    @DisplayName("诊断分类：卡下载 / 卡解压 / 进行中，直接指向该改网络还是该等")
    void diagnosesStallPoint() {
        assertTrue(ImageProvisioner.diagnose("a: Download complete\nb: Download complete\n")
                .contains("卡在解压"));
        assertTrue(ImageProvisioner.diagnose("").contains("没有任何镜像层开始下载"));
        assertTrue(ImageProvisioner.diagnose("a: Download complete\nb: Downloading [==>  ]\n")
                .contains("仍在下载"));
    }

    @Test
    @DisplayName("镜像名改写规则：官方镜像补 library/，带 registry 的返回 null")
    void rewritesDockerHubPathsOnly() {
        assertEquals("library/node:22-bookworm", ImageProvisioner.dockerHubPath("node:22-bookworm"));
        assertEquals("acme/api:1.0", ImageProvisioner.dockerHubPath("acme/api:1.0"));
        assertNull(ImageProvisioner.dockerHubPath("quay.io/prometheus/node-exporter:1.8"));
        assertNull(ImageProvisioner.dockerHubPath("localhost:5000/team/node:22"));
    }

    @Test
    @DisplayName("重试判据：鉴权/磁盘满不重试，超时与有进度的失败要重试")
    void decidesWhatIsWorthRetrying() {
        assertTrue(ImageProvisioner.worthRetry(failed("执行停滞"), ""));
        assertTrue(ImageProvisioner.worthRetry(failed("x"), "abc: Download complete\n"));
        assertFalse(ImageProvisioner.worthRetry(
                ProcessExecutor.StepOutcome.failed(1, "E-104", "exit 1"), "unauthorized: denied"));
        assertFalse(ImageProvisioner.worthRetry(
                ProcessExecutor.StepOutcome.failed(1, "E-104", "exit 1"), "no space left on device"));
    }
}
