package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 运行模式裁剪：触发与自动续跑必须得到同一份阶段计划。
 *
 * <p>回归场景：verify 运行失败后自动续跑，曾丢掉 verify 裁剪，
 * 变成完整 release（部署 + 固化配方），只读的检查构建污染了环境。
 */
class PipelineRunModeTest {

    private static PipelineDefinition definition(String target) {
        List<PipelineDefinition.StageDef> stages = List.of(
                new PipelineDefinition.StageDef("build", "action", "backend_build",
                        null, null, null, null, 0),
                new PipelineDefinition.StageDef("frontend", "action", "frontend_build",
                        null, null, null, null, 0),
                new PipelineDefinition.StageDef("image", "action", "image_package",
                        null, null, null, null, 0),
                new PipelineDefinition.StageDef("deploy", "deploy", null,
                        null, null, null, null, 0),
                new PipelineDefinition.StageDef("health", "health", null,
                        null, null, null, "http://127.0.0.1:8182/actuator/health", 200));
        return new PipelineDefinition("release", stages, List.of(),
                new PipelineDefinition.DeploySpec(target, 8182, 8090,
                        "/actuator/health", null, null, List.of()));
    }

    private static Set<String> actions(PipelineDefinition definition) {
        return definition.stages().stream()
                .map(PipelineDefinition.StageDef::action)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("verify：不部署、不打镜像")
    void verifyKeepsOnlyBuildStages() {
        PipelineDefinition planned = BuiltInPipelineEngine.planFor(
                "verify", null, false, "host", definition("host"));

        assertEquals(Set.of("backend_build", "frontend_build"), actions(planned));
        assertNull(planned.deploy(), "verify 不应留下 deploy 声明");
        assertTrue(planned.stages().stream().noneMatch(s -> "deploy".equals(s.type())));
        assertTrue(planned.stages().stream().noneMatch(s -> "health".equals(s.type())));
    }

    @Test
    @DisplayName("verify + verifyImage：显式保留镜像打包")
    void verifyKeepsImageWhenRequested() {
        PipelineDefinition planned = BuiltInPipelineEngine.planFor(
                "verify", null, true, "host", definition("host"));

        assertTrue(actions(planned).contains("image_package"));
        assertTrue(planned.stages().stream().noneMatch(s -> "deploy".equals(s.type())));
    }

    @Test
    @DisplayName("按版本部署：只跑 deploy + health，不构建")
    void deployImageKeepsDeployOnly() {
        PipelineDefinition planned = BuiltInPipelineEngine.planFor(
                "release", "ci-test-4ac8ba6", false, "host", definition("host"));

        assertEquals(List.of("deploy", "health"),
                planned.stages().stream().map(PipelineDefinition.StageDef::type).toList());
    }

    @Test
    @DisplayName("docker 目标：保留完整阶段")
    void dockerTargetKeepsImage() {
        PipelineDefinition original = definition("docker");

        PipelineDefinition planned = BuiltInPipelineEngine.planFor(
                "release", null, false, "docker", original);

        assertSame(original, planned, "无需裁剪时必须返回原定义，避免无谓复制");
        assertTrue(actions(planned).contains("image_package"));
    }

    @Test
    @DisplayName("actuator 返回 200 但 body=DOWN 不能算健康")
    void healthBodyRejectsDownStatus() {
        String url = "http://127.0.0.1:8183/actuator/health";
        assertTrue(BuiltInPipelineEngine.healthBodyOk(url,
                "{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"UP\"}"));
        assertFalse(BuiltInPipelineEngine.healthBodyOk(url,
                "{ \"status\" : \"DOWN\" }"));
        assertFalse(BuiltInPipelineEngine.healthBodyOk(url,
                "{\"status\":\"OUT_OF_SERVICE\"}"));
    }

    @Test
    @DisplayName("非 actuator 地址不做 body 约束（可能是任意首页）")
    void healthBodyIgnoresNonActuator() {
        assertTrue(BuiltInPipelineEngine.healthBodyOk("http://127.0.0.1:8183/", "DOWN"));
    }
}
