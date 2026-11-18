package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("追加宿主挂载一律拒绝（docker.sock 等于交出宿主）")
    void extraMountsDenied() {
        SandboxDeniedException denied = assertThrows(SandboxDeniedException.class,
                () -> sandbox("bridge", "").requireNoExtraMounts(List.of("/var/run/docker.sock:/sock")));
        assertEquals(SandboxDeniedException.Rule.MOUNT, denied.rule());
        assertTrue(denied.structured().contains("docker.sock"), denied.structured());
    }

    @Test
    @DisplayName("特权模式与额外 capability 一律拒绝")
    void privilegeDenied() {
        SandboxDeniedException denied = assertThrows(SandboxDeniedException.class,
                () -> sandbox("bridge", "").requireUnprivileged(List.of("--privileged")));
        assertEquals(SandboxDeniedException.Rule.PRIVILEGE, denied.rule());
        assertTrue(denied.effective().contains("cap-drop=ALL"),
                "拒绝信息要带当前隔离快照：" + denied.effective());

        SandboxDeniedException caps = assertThrows(SandboxDeniedException.class,
                () -> sandbox("bridge", "").requireUnprivileged(List.of("--cap-add=SYS_ADMIN")));
        assertEquals(SandboxDeniedException.Rule.PRIVILEGE, caps.rule());
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
