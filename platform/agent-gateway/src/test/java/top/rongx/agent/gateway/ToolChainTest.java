package top.rongx.agent.gateway;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        // 预置项目清单已从 application.yml 移除：本用例固定使用项目 RY，在测试内声明
        ActionUsabilityTest.addTestProject(registry);
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
    @Qualifier("agentToolkit")
    private Toolkit agentToolkit;
    @Autowired
    @Qualifier("readOnlyToolkit")
    private Toolkit readOnlyToolkit;
    @Autowired
    @Qualifier("deployToolkit")
    private Toolkit deployToolkit;

    /** 构造与 ChatService.stream 相同形态的 RuntimeContext。 */
    private static RuntimeContext runtime(ProjectContext ctx) {
        return RuntimeContext.builder()
                .userId(ctx.userId())
                .sessionId("test-session")
                .put(ProjectContext.class, ctx)
                .build();
    }

    private static ProjectContext ctx(String env) {
        return new ProjectContext("RY", env, "tester", "https://gitee.com/x/RuoYi-Vue-Plus.git", null);
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

        // 未知 runId 必须明确报错（不冒充 PENDING）；工具层以异常表达，框架统一转成错误文本
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> ciQueryTools.status("run-nonexistent"));
        assertTrue(bad.getMessage().contains("不存在"), bad.getMessage());

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
    @DisplayName("T8 ToolCatalog：非生产 Toolkit 带写工具，生产只读 Toolkit 不带")
    void toolkitsWired() {
        // 写工具必须直接可见：放进工具组会被会话状态覆盖成"未激活"，模型看不到 request_confirmation
        assertTrue(agentToolkit.getToolNames().contains("request_confirmation"));
        assertTrue(agentToolkit.getToolNames().contains("confirm"));
        assertTrue(agentToolkit.getToolNames().contains("ci_run"));
        assertTrue(agentToolkit.getToolNames().contains("run_action"));
        assertTrue(agentToolkit.getToolNames().contains("propose_patch"));
        // 多 Agent 协作只出现在对话工具面
        assertTrue(agentToolkit.getToolNames().contains("run_ops_agent"));
        // 生产只读面：写工具根本不存在；但只读环境工具必须补齐
        assertFalse(readOnlyToolkit.getToolNames().contains("request_confirmation"));
        assertFalse(readOnlyToolkit.getToolNames().contains("confirm"));
        assertFalse(readOnlyToolkit.getToolNames().contains("ci_run"));
        assertFalse(readOnlyToolkit.getToolNames().contains("propose_patch"));
        assertFalse(readOnlyToolkit.getToolNames().contains("run_ops_agent"));
        assertFalse(readOnlyToolkit.getToolNames().contains("env_ensure"));
        assertFalse(readOnlyToolkit.getToolNames().contains("env_mapping_draft"));
        assertTrue(readOnlyToolkit.getToolNames().contains("env_summary"));
        assertTrue(readOnlyToolkit.getToolNames().contains("env_var_probe"));
        assertTrue(readOnlyToolkit.getToolNames().contains("git_status"));
    }

    @Test
    @DisplayName("T11 部署 Agent 工具面：具备取证、确认与受限补丁能力")
    void deployToolkitHasRepairCapability() {
        // 修复失败运行需要：只读取证 + 发起确认 + 白名单动作 + 改仓库配置
        assertTrue(deployToolkit.getToolNames().contains("read_run_log"));
        assertTrue(deployToolkit.getToolNames().contains("grep"));
        assertTrue(deployToolkit.getToolNames().contains("request_confirmation"));
        assertTrue(deployToolkit.getToolNames().contains("confirm"));
        assertTrue(deployToolkit.getToolNames().contains("run_action"));
        assertTrue(deployToolkit.getToolNames().contains("propose_patch"));
        // 防递归：发布 Agent 不能再委派自己
        assertFalse(deployToolkit.getToolNames().contains("run_ops_agent"));
    }

    @Test
    @DisplayName("T12 confirm：只返回待人工确认，绝不签发令牌；跨环境/未知单号被拒")
    void confirmIsNotSelfApproval() {
        String created = changeTools.requestConfirmation("stack_deploy",
                "发布 test 环境", null, false, ctx("test"));
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("changeId:\\s*(chg-[A-Za-z0-9-]+)").matcher(created);
        assertTrue(matcher.find(), created);
        String changeId = matcher.group(1);

        String pending = changeTools.confirm(changeId, ctx("test"));
        assertTrue(pending.contains("PENDING_USER_CONFIRMATION"), pending);
        assertTrue(pending.contains(changeId), pending);
        // 工具不得返回任何令牌字段
        assertFalse(pending.toLowerCase().contains("confirmtoken"), pending);

        String wrongEnv = changeTools.confirm(changeId, ctx("prod"));
        assertTrue(wrongEnv.startsWith("err:"), wrongEnv);

        String unknown = changeTools.confirm("chg-not-exists", ctx("test"));
        assertTrue(unknown.startsWith("err:"), unknown);
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
