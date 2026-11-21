package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.error.PlatformException;

/**
 * 沙箱默认 fail-closed：Docker 不可用时不能静默降级为宿主执行。
 *
 * <p>同时守住"取值必须被显式承认"：未知值（含历史值 {@code auto} 与拼写错误）
 * 一律启动失败，而不是走一个 default 分支悄悄当 docker 处理。
 *
 * <p>mode 的声明点只有 {@link SandboxProperties} 一处（不再有第二个 {@code @Value}），
 * 所以这里用 properties 驱动。
 */
class SandboxConfigTest {

    private final DockerSandbox unavailable = new DockerSandbox(
            "definitely-not-a-docker-binary", "image", "none", "1g", "1", 64,
            "1000:1000", "");

    private static SandboxProperties props(String mode) {
        SandboxProperties properties = new SandboxProperties();
        properties.setMode(mode);
        return properties;
    }

    @Test
    @DisplayName("process 模式必须显式开启")
    void processModeMustBeExplicit() {
        SandboxConfig config = new SandboxConfig();
        assertInstanceOf(ProcessActionRunner.class,
                config.actionRunner(unavailable, props("process")));
    }

    @Test
    @DisplayName("docker 模式下守护进程不可用：启动失败，绝不降级")
    void dockerModeFailsWhenDockerUnavailable() {
        SandboxConfig config = new SandboxConfig();
        assertThrows(PlatformException.class,
                () -> config.actionRunner(unavailable, props("docker")));
    }

    @Test
    @DisplayName("历史值 auto 不再是静默兜底：直接报错并说明已废弃")
    void legacyAutoModeIsRejectedWithExplanation() {
        SandboxConfig config = new SandboxConfig();
        PlatformException e = assertThrows(PlatformException.class,
                () -> config.actionRunner(unavailable, props("auto")));
        assertTrue(e.getMessage().contains("auto"), e.getMessage());
        assertTrue(e.getMessage().contains("docker | process"), e.getMessage());
    }

    @Test
    @DisplayName("拼写错误（dockr）必须报错，不能被当成 docker 静默接受")
    void unknownModeIsRejected() {
        SandboxConfig config = new SandboxConfig();
        PlatformException e = assertThrows(PlatformException.class,
                () -> config.actionRunner(unavailable, props("dockr")));
        assertTrue(e.getMessage().contains("dockr"), e.getMessage());
    }

    @Test
    @DisplayName("空白值按默认 docker 处理（配置项留空不等于非法）")
    void blankModeFallsBackToDocker() {
        SandboxConfig config = new SandboxConfig();
        // 空白 → 走 docker 分支 → 守护进程不可用 → 仍是 fail-closed 的报错
        assertThrows(PlatformException.class, () -> config.actionRunner(unavailable, props("  ")));
    }
}
