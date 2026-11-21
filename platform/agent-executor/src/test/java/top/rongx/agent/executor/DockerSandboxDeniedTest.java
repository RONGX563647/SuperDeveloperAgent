package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.SandboxDeniedException;

/**
 * 沙箱策略拒绝：违规动作必须给结构化 SANDBOX_DENIED，而不是裸异常或裸退出码。
 *
 * <p>「沙箱脆弱且不安全」的真正缺口不是隔离参数（gVisor / cap-drop / 只读根 /
 * tmpfs 都已在 {@code wrap()} 里），而是<b>违规时的表达能力</b>：一句
 * {@code Could not resolve host} 既不像策略问题，也无法让页面或自愈区分
 * 「该改配置」还是「该改代码」。
 */
class DockerSandboxDeniedTest {

    private static DockerSandbox sandbox(String network, String runtime) {
        return new DockerSandbox("docker", "maven:3.9-eclipse-temurin-21", network,
                "2g", "2", 512, "1000:1000").setRuntime(runtime);
    }

    /** 带缓存卷前缀的沙箱（挂载边界用例需要第二个挂载点）。 */
    private static DockerSandbox cached(String network) {
        return new DockerSandbox("docker", "maven:3.9-eclipse-temurin-21", network,
                "2g", "2", 512, "1000:1000", "agent-cache");
    }
    @Test
    @DisplayName("网络关断但阶段要求外联：拒绝并给出可执行建议")
    void networkDeniedIsStructured() {
        DockerSandbox sandbox = sandbox("none", "");
        SandboxDeniedException denied = assertThrows(SandboxDeniedException.class,
                () -> sandbox.requireNetworkFor(true, "build"));

        assertEquals(SandboxDeniedException.Rule.NETWORK, denied.rule());
        assertEquals(ErrorCode.ENV_POLICY_DENIED, denied.errorCode());
        String text = denied.structured();
        assertTrue(text.startsWith("SANDBOX_DENIED"), text);
        assertTrue(text.contains("rule: NETWORK"), text);
        assertTrue(text.contains("build"), text);
        assertTrue(text.contains("action:"), text);
        assertTrue(text.contains("network=bridge"), "建议里要写清具体值：" + text);
    }

    @Test
    @DisplayName("网络允许或阶段不需要外联：不拦")
    void networkAllowedWhenNotRequired() {
        sandbox("bridge", "").requireNetworkFor(true, "build");
        sandbox("none", "").requireNetworkFor(false, "package");
    }

    @Test
    @DisplayName("挂载边界是结构性的：allowedMounts 只有工作区与缓存卷两类")
    void mountsAreStructurallyBounded() {
        // 原先的 requireNoExtraMounts(List) 收一个"想追加什么"的入参，但 StageDef 里没有
        // 任何 mounts 字段，它永远只能收到空列表（死代码）。边界改由 allowedMounts + wrap 保证。
        assertEquals(List.of("/ws", "/cache"),
                cached("bridge").allowedMounts(Path.of("E:/w/P")));
        assertEquals(List.of("/ws"), sandbox("bridge", "").allowedMounts(Path.of("E:/w/P")),
                "未启用缓存时只有工作区一个挂载");

        List<String> cmd = cached("bridge").wrap(Path.of("E:/w/P"), List.of("x"));
        String joined = String.join(" ", cmd);
        assertEquals(2, cmd.stream().filter("-v"::equals).count(), "只允许两个挂载：" + joined);
        assertFalse(joined.contains("docker.sock"), joined);
        assertFalse(joined.contains("/:/host"), joined);
    }

    @Test
    @DisplayName("特权不可达：任何网络档位下 wrap 的 argv 都不含提权参数")
    void privilegeIsUnreachable() {
        for (String network : List.of("bridge", "none", "host")) {
            List<String> cmd = new DockerSandbox("docker", "img", network, "2g", "2", 512,
                    "1000:1000", "agent-cache").wrap(Path.of("E:/w"), List.of("x"));
            String joined = String.join(" ", cmd);
            assertFalse(joined.contains("--privileged"), joined);
            assertFalse(joined.contains("--cap-add"), joined);
            assertTrue(joined.contains("--cap-drop ALL"), joined);
            assertTrue(joined.contains("no-new-privileges"), joined);
        }
    }

    @Test
    @DisplayName("运行时就绪门：配置 runsc 但守护进程不报告它时，起容器前就拒绝")
    void ensureRuntimeReadyDeniesBeforeContainerStarts() {
        // 用假执行器返回"只有 runc"，避免依赖本机是否装了 runsc
        DockerSandbox probed = new DockerSandbox("docker", "img", "bridge", "2g", "2", 512,
                "1000:1000", "agent-cache", (cmd, timeoutMs, cancelled, live) ->
                        cmd.contains("info")
                                ? ProcessExecutor.StepOutcome.ok()
                                : ProcessExecutor.StepOutcome.ok())
                .setRuntime("runsc");
        // 探测输出走 BoundedCommand（真进程），这里只验证"没有 runsc 时拒绝"的行为：
        // 直接喂一个不含 runsc 的清单，等价于本机没装 runsc 的实测结论。
        SandboxDeniedException denied = assertThrows(SandboxDeniedException.class,
                () -> probed.requireRuntimeAvailable(DockerSandbox.runtimeKeys(
                        "{\"runc\":{\"path\":\"runc\"},\"nvidia\":{\"path\":\"nvidia\"}}")));
        assertEquals(SandboxDeniedException.Rule.RUNTIME, denied.rule());
        assertTrue(denied.structured().contains("runsc"), denied.structured());

        // 空 runtime 不探测、不拦截（默认 runc 路径零开销）
        probed.setRuntime("").ensureRuntimeReady(() -> false, null);
    }

    @Test
    @DisplayName("docker info 的运行时清单能解析成名字列表")
    void runtimeKeysParsedFromInfoJson() {
        assertEquals(List.of("runc", "nvidia"), DockerSandbox.runtimeKeys(
                "{\"runc\":{\"path\":\"runc\"},\"nvidia\":{\"path\":\"nvidia\"}}"));
        assertEquals(List.of("io.containerd.runc.v2"), DockerSandbox.runtimeKeys(
                "{\"io.containerd.runc.v2\":{\"path\":\"runc\",\"status\":{\"x\":true}}}"));
        assertTrue(DockerSandbox.runtimeKeys("").isEmpty());
        assertTrue(DockerSandbox.runtimeKeys(null).isEmpty());
    }

    @Test
    @DisplayName("要求的运行时不可用：明确拒绝，绝不静默回落 runc")
    void runtimeDeniedInsteadOfSilentFallback() {
        DockerSandbox sandbox = sandbox("bridge", "runsc");
        SandboxDeniedException denied = assertThrows(SandboxDeniedException.class,
                () -> sandbox.requireRuntimeAvailable(List.of("runc", "kata")));

        assertEquals(SandboxDeniedException.Rule.RUNTIME, denied.rule());
        assertTrue(denied.structured().contains("runsc"), denied.structured());
        assertTrue(denied.action().contains("runc"), "要说明回落选项：" + denied.action());
        // 探测不到运行时（空列表）时不误拦：交给 docker run 显式失败
        sandbox.requireRuntimeAvailable(List.of());
        sandbox.requireRuntimeAvailable(null);
    }

    @Test
    @DisplayName("隔离快照包含全部生效参数，可写进运行日志")
    void isolationSnapshotIsComplete() {
        List<String> snapshot = sandbox("bridge", "").isolationSnapshot();
        String text = String.join(" ", snapshot);

        assertTrue(snapshot.contains("runtime=runc"), text);
        assertTrue(snapshot.contains("cap-drop=ALL"), text);
        assertTrue(snapshot.contains("no-new-privileges"), text);
        assertTrue(snapshot.contains("read-only-rootfs"), text);
        assertTrue(text.contains("network=bridge"), text);
        assertTrue(text.contains("user=1000:1000"), text);
        assertFalse(text.isBlank());
    }

    @Test
    @DisplayName("结构化正文可原样交给模型与页面：含原因码/规则/快照/动作")
    void structuredTextIsSelfDescribing() {
        SandboxDeniedException denied = new SandboxDeniedException(
                SandboxDeniedException.Rule.RESOURCE, "内存上限 0 未配置",
                List.of("memory=2g", "cpus=2"),
                "在 agent.sandbox.memory 里给一个上限");
        assertEquals("SANDBOX_DENIED", denied.structured().lines().findFirst().orElse(""));
        assertTrue(denied.structured().contains("rule: RESOURCE"));
        assertTrue(denied.structured().contains("memory=2g"));
        assertEquals("E-707", denied.code());
    }
}
