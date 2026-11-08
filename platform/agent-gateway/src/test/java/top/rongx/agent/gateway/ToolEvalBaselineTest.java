package top.rongx.agent.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最小评测回放（PRD §E4 的落地雏形）：固定的「指令 → 预期工具调用序列」。
 *
 * <p>当前验证的是<b>路由决策</b>：给定用户指令，正确的工具选择与参数应该是什么。
 * 真正的 LLM 驱动评测需要模型；这里先固化「评测基线」，
 * 上线前用真实模型跑同一脚本，对比命中率是否达 PRD 门槛（E4 ≥ 80%）。
 *
 * <p>用法：
 * <ol>
 *   <li>往 {@link #CASES} 添加「指令 → 期望的工具调用序列」；</li>
 *   <li>接真实模型后，把 CASES 交给 Agent 跑，把实际调用序列与本表对比；</li>
 *   <li>命中率 = 命中条数 / 总条数，须 ≥ PRD 门槛。</li>
 * </ol>
 */
class ToolEvalBaselineTest {

    /** 评测用例：用户指令 → 期望按序调用的工具名列表。 */
    private record EvalCase(String instruction, List<String> expectedToolSequence) {
    }

    /** 固定评测集：每条对应一个典型任务，覆盖 L0 读、L2 写、L1 变更请求三类。 */
    private static final List<EvalCase> CASES = List.of(
            new EvalCase("当前项目用的什么仓库和端口？",
                    List.of("project_info")),
            new EvalCase("有哪些环境可以部署？",
                    List.of("list_envs")),
            new EvalCase("看看可以跑哪些流水线",
                    List.of("ci_list_pipelines")),
            new EvalCase("上次构建成没成功？",
                    List.of("ci_history")),
            new EvalCase("帮我查一下 run-abc123 这次构建的状态",
                    List.of("ci_status")),
            new EvalCase("这个项目能执行哪些动作？",
                    List.of("list_actions")),
            new EvalCase("backend_build 具体会跑什么命令？",
                    List.of("plan_action")),
            new EvalCase("触发一次 release 流水线构建 sha abc1234",
                    List.of("ci_list_pipelines", "ci_run")),
            new EvalCase("单独跑一下 backend_build 这个动作验证",
                    List.of("list_actions", "plan_action", "run_action")),
            new EvalCase("把当前版本发布到生产",
                    List.of("request_confirmation"))
    );

    @Test
    @DisplayName("评测基线：每条用例都有非空的期望序列，且工具名有效")
    void baselineSanity() {
        var knownTools = java.util.Set.of(
                "project_info", "list_envs",
                "ci_list_pipelines", "ci_status", "ci_history", "ci_run", "ci_cancel",
                "list_actions", "plan_action", "run_action",
                "request_confirmation");
        for (EvalCase c : CASES) {
            assertTrue(!c.expectedToolSequence().isEmpty(), "用例期望序列不能为空：" + c.instruction());
            for (String tool : c.expectedToolSequence()) {
                assertTrue(knownTools.contains(tool),
                        "未知工具名 " + tool + "（用例：" + c.instruction() + "）");
            }
        }
        assertEquals(10, CASES.size(), "评测集规模应与 PRD E4 的 10 条一致");
    }
}
