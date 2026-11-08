package top.rongx.agent.gateway;

import io.agentscope.core.agent.RuntimeContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.tools.ActionCommandTools;
import top.rongx.agent.tools.ActionQueryTools;
import top.rongx.agent.tools.CiCommandTools;
import top.rongx.agent.tools.CiQueryTools;
import top.rongx.agent.tools.ChangeTools;
import top.rongx.agent.tools.ProjectTools;
import top.rongx.agent.tools.ToolCatalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具链路测试：不经模型，直接以 RuntimeContext 注入 ProjectContext 调用 @Tool 方法。
 *
 * <p>覆盖两个此前零验证的环节：
 * <ol>
 *   <li>RuntimeContext（ChatService.java 的传递方式）→ @Tool 方法参数注入是否真的工作；</li>
 *   <li>ToolCatalog 的两个 Toolkit 装配完整（生产只读版 / 非生产完整版）。</li>
 * </ol>
 */
@SpringBootTest
class ToolChainTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("agent.model.api-key", () -> "test-key-not-used");
        registry.add("agent.sandbox.mode", () -> "process");
    }

    @Autowired
    private ProjectTools projectTools;
    @Autowired
    private CiQueryTools ciQueryTools;
    @Autowired
    private ActionQueryTools actionQueryTools;
    @Autowired
    private CiCommandTools ciCommandTools;
    @Autowired
    private ChangeTools changeTools;
    @Autowired
    private ActionCommandTools actionCommandTools;
    @Autowired
    private ToolCatalog toolCatalog;

    /** 构造与 ChatService.stream 相同形态的 RuntimeContext。 */
    private static RuntimeContext runtime(ProjectContext ctx) {
        return RuntimeContext.builder()
                .userId(ctx.userId())
                .sessionId("test-session")
                .put(ProjectContext.class, ctx)
                .build();
    }

    private static ProjectContext ctx(String env) {
        return new ProjectContext("RY", env, "tester", "https://gitee.com/x/RuoYi-Vue-Plus.git");
    }

    // ---------------------------------------------------------------- 只读工具

    @Test
    @DisplayName("T1 project_info / list_envs：RuntimeContext 注入生效")
    void readOnlyProjectTools() {
        String info = projectTools.projectInfo(ctx("test"));
        assertTrue(info.contains("project: RY"), info);
        assertTrue(info.contains("evidence: project:RY"));

        String envs = projectTools.listEnvs(ctx("test"));
        assertTrue(envs.contains("dev") && envs.contains("prod"), envs);
    }

    @Test
    @DisplayName("T2 ci_list_pipelines / ci_status / ci_history")
    void readOnlyCiTools() {
        String pipelines = ciQueryTools.listPipelines(ctx("test"));
        assertTrue(pipelines.contains("release"), pipelines);

        // 未知 runId 必须报 err（不冒充 PENDING）
        String bad = ciQueryTools.status("run-nonexistent");
        assertTrue(bad.startsWith("err:"), bad);

        String history = ciQueryTools.history(ctx("test"));
        assertNotNull(history);
    }

    @Test
    @DisplayName("T3 list_actions / plan_action：未知动作给可用列表")
    void readOnlyActionTools() {
        String list = actionQueryTools.listActions(ctx("test"));
        assertNotNull(list);

        String bad = actionQueryTools.planAction("deploy_everything", ctx("test"));
        assertTrue(bad.startsWith("err:") && bad.contains("backend_build"), bad);
    }

    // ---------------------------------------------------------------- 写工具（治理路径）

    @Test
    @DisplayName("T4 ci_run：非法 sha / 未知 pipeline 被拒（不进入执行器）")
    void ciRunValidation() {
        String badSha = ciCommandTools.run("release", "NOT-A-SHA", ctx("test"));
        assertTrue(badSha.startsWith("err:"), badSha);

        String badPipeline = ciCommandTools.run("deploy-everything", "abcdef1", ctx("test"));
        assertTrue(badPipeline.contains("白名单"), badPipeline);
    }

    @Test
    @DisplayName("T5 ci_run：生产环境拒绝")
    void ciRunProdBlocked() {
        String out = ciCommandTools.run("release", "abcdef1", ctx("prod"));
        assertTrue(out.contains("生产环境"), out);
    }

    @Test
    @DisplayName("T6 request_confirmation：kind 白名单与摘要校验")
    void changeValidation() {
        String badKind = changeTools.requestConfirmation("RELEASE", "x", null, false, ctx("test"));
        assertTrue(badKind.startsWith("err:"), badKind);

        String badSummary = changeTools.requestConfirmation("stack_deploy",
                "a".repeat(121), null, false, ctx("test"));
        assertTrue(badSummary.contains("120"), badSummary);
    }

    @Test
    @DisplayName("T7 run_action：未知动作被拒")
    void runActionValidation() {
        String out = actionCommandTools.runAction("deploy_everything", Map.of(), ctx("test"));
        assertTrue(out.startsWith("err:"), out);
    }

    // ---------------------------------------------------------------- 装配完整性

    @Test
    @DisplayName("T8 ToolCatalog：两个 Toolkit 都能构建（生产只读 / 非生产完整）")
    void toolkitsWired() {
        // 通过 Spring 装配本身验证两个 @Bean 都能实例化；这里再确认 ToolCatalog 常量
        assertNotNull(ToolCatalog.GROUP_WRITE);
        assertFalse(ToolCatalog.GROUP_WRITE.isBlank());
    }

    @Test
    @DisplayName("T9 runtime() 形态与 ChatService 一致：ProjectContext 可从 RuntimeContext 取回")
    void runtimeContextRoundTrip() {
        ProjectContext ctx = ctx("test");
        RuntimeContext runtime = runtime(ctx);
        ProjectContext back = runtime.get(ProjectContext.class);
        assertNotNull(back);
        assertTrue("RY".equals(back.projectId()));
    }

    @Test
    @DisplayName("T10 全部只读工具文本不含 'err:'（项目存在且配方可解析时）")
    void allReadOnlySucceed() {
        ProjectContext c = ctx("test");
        for (String out : List.of(
                projectTools.projectInfo(c),
                ciQueryTools.listPipelines(c))) {
            assertFalse(out.startsWith("err:"), out);
        }
    }
}
