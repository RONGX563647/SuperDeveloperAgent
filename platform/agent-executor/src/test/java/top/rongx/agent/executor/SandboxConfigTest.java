package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.PlatformException;
import top.rongx.agent.domain.run.StageStatus;

/**
 * 沙箱模式的 fail-closed 语义。
 *
 * <p>守护进程可用性从「启动期」后移到「执行期」后，拆成两条独立契约：
 * <ol>
 *   <li><b>启动解耦</b>：{@link SandboxConfig#actionRunner} 只装配执行器，不探测 daemon——
 *       没有 daemon 的平台也要能启动、health 才能通（修部署 health 失败的 bug）；</li>
 *   <li><b>执行期 fail-closed</b>：{@link DockerActionRunner#run} 每次真正执行动作前探测 daemon，
 *       不可用就结构化失败，绝不降级为无隔离的宿主进程（安全属性不变）。</li>
 * </ol>
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

    private static ActionSpec spec() {
        return new ActionSpec(ActionId.BACKEND_BUILD, List.of(List.of("echo", "hi")), List.of(),
                Duration.ofMinutes(1), ActionSource.builtin("test"));
    }

    @Test
    @DisplayName("process 模式必须显式开启")
    void processModeMustBeExplicit() {
        SandboxConfig config = new SandboxConfig();
        assertInstanceOf(ProcessActionRunner.class,
                config.actionRunner(unavailable, props("process")));
    }

    @Test
    @DisplayName("docker 模式启动期不探测 daemon：返回容器执行器，平台不受 daemon 缺失影响")
    void dockerModeStartsWithoutProbingDaemon() {
        SandboxConfig config = new SandboxConfig();
        // daemon 不可用时不再抛异常；否则没有 dind sidecar 的部署里平台 health 永远不通
        assertInstanceOf(DockerActionRunner.class,
                config.actionRunner(unavailable, props("docker")));
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
        // 空白 → 走 docker 分支 → 返回容器执行器（启动期不探测 daemon）
        assertInstanceOf(DockerActionRunner.class, config.actionRunner(unavailable, props("  ")));
    }

    @Test
    @DisplayName("执行期 fail-closed：daemon 不可用时动作结构化失败，绝不降级为宿主进程")
    void dockerRunnerFailsClosedAtExecutionTime(@TempDir Path dir) {
        StringBuilder live = new StringBuilder();
        ActionResult result = new DockerActionRunner(unavailable)
                .run(dir, spec(), () -> false, live::append);

        assertEquals(StageStatus.FAILED, result.result().status());
        assertEquals(ErrorCode.ENVIRONMENT_INVALID_STATE.code(), result.result().errorCode());
        assertTrue(result.logTail().contains("守护进程不可用"), result.logTail());
        assertTrue(live.toString().contains("fail-closed"), live.toString());
    }
}
