package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 沙箱默认 fail-closed：Docker 不可用时不能静默降级为宿主执行。 */
class SandboxConfigTest {

    private final DockerSandbox unavailable = new DockerSandbox(
            "definitely-not-a-docker-binary", "image", "none", "1g", "1", 64,
            "1000:1000", "");

    @Test
    void processModeMustBeExplicit() {
        SandboxConfig config = new SandboxConfig();
        assertInstanceOf(ProcessActionRunner.class,
                config.actionRunner(unavailable, "process"));
    }

    @Test
    void dockerModeFailsWhenDockerUnavailable() {
        SandboxConfig config = new SandboxConfig();
        assertThrows(IllegalStateException.class,
                () -> config.actionRunner(unavailable, "docker"));
    }

    @Test
    void legacyAutoModeAlsoFailsClosed() {
        SandboxConfig config = new SandboxConfig();
        assertThrows(IllegalStateException.class,
                () -> config.actionRunner(unavailable, "auto"));
    }
}
