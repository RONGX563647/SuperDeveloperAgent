package top.rongx.agent.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固化脚本要记录"本次实际生效的部署目标"。
 *
 * <p>回归场景：面板上选「打包成镜像」跑通后，固化的 ci/pipeline.yml 仍是 {@code target: host}，
 * 下次预览/重放又按 host 部署，用户的选择被静默丢弃。
 */
class PipelineFreezeTargetTest {

    @Test
    @DisplayName("流式 deploy 段：只改写 target，其余字段与参数原样保留")
    void rewritesFlowStyle() {
        String yaml = "name: release\n"
                + "deploy: {target: host, port: 8182, containerPort: 8090}\n"
                + "stages:\n- {id: build, type: action, action: backend_build}\n";

        String out = BuiltInPipelineEngine.withDeployTarget(yaml, "docker");

        assertTrue(out.contains("deploy: {target: docker, port: 8182, containerPort: 8090}"), out);
        assertTrue(out.contains("- {id: build, type: action, action: backend_build}"), out);
        assertEquals("name: release", out.split("\n")[0]);
    }

    @Test
    @DisplayName("块式 deploy 段：改写已有的 target 行")
    void rewritesBlockStyle() {
        String yaml = """
                name: release
                deploy:
                  target: host
                  port: 8182
                stages:
                  - id: build
                    type: action
                """;

        String out = BuiltInPipelineEngine.withDeployTarget(yaml, "DOCKER");

        assertTrue(out.contains("  target: docker"), out);
        assertTrue(out.contains("  port: 8182"), out);
    }

    @Test
    @DisplayName("块式 deploy 段没有 target：补一行，缩进与同级字段一致")
    void insertsMissingTarget() {
        String yaml = "deploy:\n  port: 8080\nstages:\n- {id: build, type: action}\n";

        String out = BuiltInPipelineEngine.withDeployTarget(yaml, "server");

        assertTrue(out.contains("deploy:\n  target: server\n  port: 8080"), out);
    }

    @Test
    @DisplayName("非法目标 / 没有 deploy 段：不改写（避免把脚本固化成跑不通的目标）")
    void leavesUnknownShapesAlone() {
        assertNull(BuiltInPipelineEngine.withDeployTarget("deploy: {target: host}\n", "kubernetes"));
        assertNull(BuiltInPipelineEngine.withDeployTarget("deploy: {target: host}\n", null));
        assertNull(BuiltInPipelineEngine.withDeployTarget("name: x\nstages: []\n", "docker"));
    }
}
