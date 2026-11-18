package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.rongx.agent.domain.deploy.DeployRecipe;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.error.DeployException;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.gateway.deploy.DeployGate;

/**
 * 重放的部署目标必须原样下发到引擎。
 *
 * <p>回归背景：面板上选「打包成镜像」后点重放，target 在前端→网关这段被丢掉，
 * 引擎只好按固化脚本里的 host 执行 —— 选了 docker 却部署到本机。
 */
class DeployControllerReplayTargetTest {

    private static final String PROJECT = "RY";
    private static final String SHA = "4ac8ba6";

    private DeployController controller(PipelineEngine engine) {
        RecipeStore recipes = mock(RecipeStore.class);
        when(recipes.current(anyString(), anyString()))
                .thenReturn(Optional.of(new DeployRecipe(PROJECT, "test", 3, "name: release\n", null,
                        "run-1", SHA, System.currentTimeMillis(), "agent")));
        DeployGate gate = mock(DeployGate.class);
        CurrentUser user = mock(CurrentUser.class);
        when(user.requireUserId()).thenReturn("alice");
        return new DeployController(recipes, engine, gate, user, mock(EnvironmentStore.class));
    }

    private PipelineEngine engine() {
        PipelineEngine engine = mock(PipelineEngine.class);
        when(engine.pipelines(anyString())).thenReturn(List.of("release"));
        when(engine.trigger(anyString(), anyString(), any())).thenReturn("run-2");
        when(engine.status(anyString())).thenReturn(StageStatus.RUNNING);
        return engine;
    }

    @Test
    @DisplayName("重放带 deployTarget=docker → 引擎收到 docker")
    void replayForwardsDockerTarget() {
        PipelineEngine engine = engine();
        controller(engine).replay(PROJECT, "test", SHA, null, "docker");

        assertTriggeredWith(engine, "docker");
    }

    @Test
    @DisplayName("重放带 deployTarget=server（生产）→ 引擎收到 server")
    void replayForwardsServerTarget() {
        PipelineEngine engine = engine();
        controller(engine).replay(PROJECT, "test", SHA, null, "SERVER");

        assertTriggeredWith(engine, "server");
    }

    @Test
    @DisplayName("不传 deployTarget → 不下发该参数，由固化脚本决定")
    void replayWithoutTargetLeavesItToRecipe() {
        PipelineEngine engine = engine();
        controller(engine).replay(PROJECT, "test", SHA, null, null);

        assertTriggeredWithoutTarget(engine);
    }

    @Test
    @DisplayName("非法 deployTarget → 拒绝，不静默回落")
    void replayRejectsUnknownTarget() {
        DeployException error = assertThrows(DeployException.class,
                () -> controller(engine()).replay(PROJECT, "test", SHA, null, "kubernetes"));
        assertTrue(error.getMessage().contains("docker"));
    }

    @SuppressWarnings("unchecked")
    private void assertTriggeredWith(PipelineEngine engine, String expected) {
        var captor = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(engine).trigger(eq(PROJECT), eq("release"), captor.capture());
        Map<String, String> sent = (Map<String, String>) captor.getValue();
        assertEquals(expected, sent.get("deployTarget"));
    }

    @SuppressWarnings("unchecked")
    private void assertTriggeredWithoutTarget(PipelineEngine engine) {
        var captor = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(engine).trigger(eq(PROJECT), eq("release"), captor.capture());
        Map<String, String> sent = (Map<String, String>) captor.getValue();
        assertFalse(sent.containsKey("deployTarget"));
    }
}
