package top.rongx.agent.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.domain.error.SandboxDeniedException;

/**
 * BUG-35 回归：沙箱配置自定义网络（build-net）时，prepare 阶段必须先确保网络存在；
 * 失败在跑构建前就报"网络未就绪"；策略拒绝文案不再硬编码推荐一个不存在的网络。
 */
class DockerSandboxNetworkTest {

    private static final class Recorder implements DockerSandbox.CommandExecutor {
        final List<List<String>> commands = new ArrayList<>();
        Function<List<String>, ProcessExecutor.StepOutcome> handler;

        @Override
        public ProcessExecutor.StepOutcome run(List<String> cmd, long timeoutMs,
                                               BooleanSupplier cancelled, Consumer<String> live) {
            commands.add(cmd);
            return handler.apply(cmd);
        }
    }

    private static DockerSandbox sandbox(Recorder recorder, String network) {
        return new DockerSandbox("docker", "maven:3.9-eclipse-temurin-21", network,
                "2g", "2", 512, "1000:1000", null, recorder);
    }

    @Test
    @DisplayName("network=build-net 且不存在：prepareNetwork 自动 inspect -> create，成功就绪")
    void prepareNetworkCreatesMissingNetwork() {
        Recorder recorder = new Recorder();
        recorder.handler = cmd -> cmd.contains("inspect")
                ? ProcessExecutor.StepOutcome.failed(1, "E-101", "not found")
                : ProcessExecutor.StepOutcome.ok();
        DockerSandbox sandbox = sandbox(recorder, "build-net");

        StringBuilder live = new StringBuilder();
        sandbox.prepareNetwork(() -> false, live::append);

        assertEquals(2, recorder.commands.size(), "inspect -> create");
        assertEquals(List.of("docker", "network", "inspect", "build-net"), recorder.commands.get(0));
        assertEquals(List.of("docker", "network", "create", "build-net"), recorder.commands.get(1));
        assertTrue(live.toString().contains("网络已就绪"), live.toString());
    }

    @Test
    @DisplayName("network=build-net 且创建失败：抛 EnvironmentException 且文案含 docker network create，不进入构建")
    void prepareNetworkFailsWithActionableMessage() {
        Recorder recorder = new Recorder();
        recorder.handler = cmd -> ProcessExecutor.StepOutcome.failed(1, "E-101", "denied");
        DockerSandbox sandbox = sandbox(recorder, "build-net");

        EnvironmentException error = assertThrows(EnvironmentException.class,
                () -> sandbox.prepareNetwork(() -> false, null));
        assertTrue(error.getMessage().contains("网络未就绪"), error.getMessage());
        assertTrue(error.getMessage().contains("docker network create build-net"), error.getMessage());
        assertTrue(error.getMessage().contains("agent.sandbox.network"), error.getMessage());
        assertEquals(2, recorder.commands.size(), "inspect -> create 失败即停");
    }

    @Test
    @DisplayName("网络已存在：只 inspect 一次，不重复创建")
    void prepareNetworkSkipsWhenPresent() {
        Recorder recorder = new Recorder();
        recorder.handler = cmd -> ProcessExecutor.StepOutcome.ok();
        sandbox(recorder, "build-net").prepareNetwork(() -> false, null);
        assertEquals(1, recorder.commands.size(), "已存在只 inspect");
    }

    @Test
    @DisplayName("内置网络 bridge/none 不做任何 docker 调用")
    void builtinNetworksAreSkipped() {
        for (String builtin : new String[]{"bridge", "none", null}) {
            Recorder recorder = new Recorder();
            recorder.handler = cmd -> ProcessExecutor.StepOutcome.ok();
            sandbox(recorder, builtin).prepareNetwork(() -> false, null);
            assertTrue(recorder.commands.isEmpty(), builtin + " 是内置网络");
        }
    }

    @Test
    @DisplayName("策略拒绝文案动态生成：不再硬编码推荐默认不存在的专用网络")
    void networkDenialSuggestionIsDynamic() {
        DockerSandbox sandbox = sandbox(new Recorder(), "none");
        SandboxDeniedException denied = assertThrows(SandboxDeniedException.class,
                () -> sandbox.requireNetworkFor(true, "backend-build"));
        assertFalse(denied.action().contains("（或专用 build-net）"),
                "硬编码推荐已移除：" + denied.action());
        assertTrue(denied.action().contains("bridge"), denied.action());
        assertTrue(denied.action().contains("docker network create build-net"),
                "确需专用网络时给出可复制命令：" + denied.action());
    }
}
