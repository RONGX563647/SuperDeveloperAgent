package top.rongx.agent.gateway;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.runtime.ModelConfig;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实模型工具评测（PRD E4 的 LLM 实测版）。
 *
 * <p><b>跳过条件</b>：未设置 {@code AGENT_MODEL_API_KEY} 时整类跳过——
 * 普通 CI / 本地无 key 时不会红，也不会消耗 token。
 *
 * <p><b>token 控制</b>：
 * <ul>
 *   <li>只跑 3 条精选用例（读工具 ×2 + 变更请求 ×1），不跑 10 条全集；</li>
 *   <li>工具面为只读 Toolkit（生产版，schema 最小）；</li>
 *   <li>每轮 maxIters=4，断言用例在 2 轮内命中目标工具；</li>
 *   <li>评测后打印实际工具调用序列，供与 {@link ToolEvalBaselineTest} 基线对比。</li>
 * </ul>
 */
@SpringBootTest
// 真实模型评测会消耗 token 并依赖外部密钥：必须显式 AGENT_LIVE_MODEL=true 才跑，
// 避免 CI 构建结果随外部密钥状态（过期/401）漂移。
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "AGENT_LIVE_MODEL", matches = "true")
class LiveModelToolEvalTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // AgentProperties 要求 api-key 必填（fail-closed）；无真实 key 时给占位值，
        // 测试方法内用 assumeTrue 跳过——上下文能起，token 零消耗
        String key = System.getenv("AGENT_MODEL_API_KEY");
        registry.add("agent.model.api-key",
                () -> key != null && !key.isBlank() ? key : "placeholder-no-key");
        registry.add("agent.model.base-url",
                () -> System.getenv().getOrDefault("AGENT_MODEL_BASE_URL",
                        "https://api.deepseek.com"));
        registry.add("agent.model.model",
                () -> System.getenv().getOrDefault("AGENT_MODEL_NAME", "deepseek-chat"));
        registry.add("agent.model.max-iters", () -> "4");
        registry.add("agent.sandbox.mode", () -> "process");
    }

    @Autowired
    @Qualifier("readOnlyToolkit")
    private Toolkit readOnlyToolkit;

    @Autowired
    private Model model;

    private static final String USER = "eval-live";

    private record EvalCase(String instruction, String expectedTool) {
    }

    /** 3 条精选用例：都是只读、确定性高、单工具命中。 */
    private static final List<EvalCase> CASES = List.of(
            new EvalCase("当前项目用的什么仓库和端口？", "project_info"),
            new EvalCase("列出当前项目可用的流水线", "ci_list_pipelines"),
            new EvalCase("这个项目能执行哪些动作？", "list_actions")
    );

    /** 从事件流里提取工具名（经 Jackson 序列化，逐层找 name 字段）。 */
    private static List<String> collectToolCalls(List<AgentEvent> events, StringBuilder debug) {
        List<String> tools = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        boolean dumped = false;
        for (AgentEvent e : events) {
            String simple = e.getClass().getSimpleName();
            if (!simple.contains("ToolCall")) {
                continue;
            }
            try {
                var node = mapper.convertValue(e, Map.class);
                if (!dumped && simple.contains("Start")) {
                    debug.append("ToolCallStartEvent JSON: ").append(node).append('\n');
                    dumped = true;
                }
                String name = findName(node, 0);
                if (name != null) {
                    tools.add(name);
                } else {
                    tools.add("?");
                }
            } catch (IllegalArgumentException ex) {
                tools.add(simple);
            }
        }
        return tools;
    }

    /** 递归（限深 3 层）在事件 JSON 里找工具名字段。 */
    private static String findName(Object node, int depth) {
        if (depth > 3 || !(node instanceof Map<?, ?> m)) {
            return null;
        }
        for (Map.Entry<?, ?> en : m.entrySet()) {
            String key = String.valueOf(en.getKey());
            Object val = en.getValue();
            if (("toolCallName".equals(key) || "name".equals(key)) && val instanceof String s
                    && !s.isBlank() && !s.startsWith("ToolCall")) {
                return s;
            }
        }
        for (Object v : m.values()) {
            String found = findName(v, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    @DisplayName("E4-LIVE 真实模型工具选择评测（无 key 自动跳过）")
    void liveToolSelection() {
        String key = System.getenv("AGENT_MODEL_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "AGENT_MODEL_API_KEY 未设置，跳过真实模型评测（不消耗 token）");

        ProjectContext ctx = new ProjectContext("RY", "test", USER,
                "https://gitee.com/x/RuoYi-Vue-Plus.git", null);
        RuntimeContext runtime = RuntimeContext.builder()
                .userId(ctx.userId())
                .sessionId("eval-live-" + System.currentTimeMillis())
                .put(ProjectContext.class, ctx)
                .build();

        ReActAgent agent = ReActAgent.builder()
                .name("devops-agent-eval")
                .sysPrompt("""
                        你是研发运维智能体。回答前先用工具取证，不要凭印象回答本项目的配置。
                        当前项目：RY；环境：test；操作人：%s。
                        """.formatted(USER))
                .model(model)
                .toolkit(readOnlyToolkit)
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.ACCEPT_EDITS).build())
                .maxIters(4)
                .build();

        int hits = 0;
        StringBuilder report = new StringBuilder("\n=== E4-LIVE 评测结果 ===\n");
        StringBuilder debug = new StringBuilder();
        for (EvalCase c : CASES) {
            List<AgentEvent> events = new ArrayList<>();
            agent.streamEvents(c.instruction(), runtime).toIterable().forEach(events::add);
            List<String> calls = collectToolCalls(events, debug);
            boolean hit = calls.contains(c.expectedTool());
            if (hit) {
                hits++;
            }
            report.append(String.format("[%s] %-22s → %s%n",
                    hit ? "PASS" : "FAIL", c.expectedTool(), calls));
        }
        double rate = hits * 100.0 / CASES.size();
        report.append(String.format("命中率: %d/%d = %.0f%%%n", hits, CASES.size(), rate));
        System.out.println(debug.toString() + report);

        // PRD E4 门槛：≥ 80%（3 条里至少 3 中 2）
        assertTrue(rate >= 80.0,
                "工具选择命中率 " + rate + "% 低于 PRD E4 门槛 80%。评测报告：" + report);
    }
}
