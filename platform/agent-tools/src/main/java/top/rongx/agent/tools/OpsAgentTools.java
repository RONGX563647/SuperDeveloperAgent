package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.OpsAgentPort;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 多 Agent 协作工具：对话 Agent → 发布 Agent。
 *
 * <p>只注册进对话工具面（{@code agentToolkit}）：
 * <ul>
 *   <li>发布 Agent 的工具面没有它——否则会无限递归委派；</li>
 *   <li>生产只读面没有它——生产发布只走人工确认的部署面板。</li>
 * </ul>
 *
 * <p>委派不会降低安全门槛：子 Agent 的写操作仍要人确认，返回里的 changeId / bindingId
 * 仍需用户本人到平台确认。
 */
@Component
public class OpsAgentTools {

    private static final int MAX_TASK_CHARS = 1000;

    private final OpsAgentPort opsAgent;

    public OpsAgentTools(OpsAgentPort opsAgent) {
        this.opsAgent = opsAgent;
    }

    @Tool(name = "run_ops_agent",
            description = """
                    把「把某个环境真正跑通 / 深度排障 / 固化可复现部署脚本」委派给发布工程师 Agent
                    （多 Agent 协作）。适用于：用户要求"帮我把 test 环境跑起来""为什么部署一直失败"
                    "把跑通的步骤固化成脚本"这类需要多轮取证 + 触发 + 修复的完整任务。
                    task 用一句话写清目标（不超过 1000 字），env 必须是当前会话环境。
                    子 Agent 会自行调用 env_summary / deploy_run / read_run_log / propose_patch 等工具，
                    但不会执行未经人工确认的变更；返回的是压缩后的结论、工具轨迹与失败原因。
                    生产环境不可委派（生产发布请在部署面板人工确认）。
                    简单问答（查状态、读日志、看配置）请直接用自己的工具，不要委派。""",
            readOnly = false, concurrencySafe = false)
    public String run(@ToolParam(name = "task",
                                 description = "要完成的目标，一句话；不超过 1000 字")
                      String task,
                      @ToolParam(name = "env", required = false,
                              description = "目标环境：dev / test / prod；必须与会话环境一致")
                      String env,
                      ProjectContext ctx) {
        if (task == null || task.isBlank()) {
            return "err: task 不能为空";
        }
        if (task.length() > MAX_TASK_CHARS) {
            return "err: task 不能超过 " + MAX_TASK_CHARS + " 字（请提炼目标）";
        }
        String target = env == null || env.isBlank() ? ctx.env() : env.trim().toLowerCase();
        if (!List.of("dev", "test", "prod").contains(target)) {
            return "err: env 只能是 dev / test / prod，收到：" + env;
        }
        if (!target.equalsIgnoreCase(ctx.env())) {
            return "err: 只能委派当前会话环境 " + ctx.env() + "，收到 " + target
                    + "（切换环境请新开会话）";
        }
        if (ctx.isProduction()) {
            return "err: 生产环境不可委派子 Agent：请在部署面板人工确认发布";
        }
        OpsAgentPort.Report report = opsAgent.run(ctx, task);
        StringBuilder sb = new StringBuilder();
        sb.append("status: ").append(report.status()).append('\n')
                .append("env: ").append(target).append('\n')
                .append("tools: ").append(report.tools().isEmpty()
                        ? "（无）" : String.join(", ", report.tools())).append('\n');
        if (!report.errors().isEmpty()) {
            sb.append("errors:\n");
            report.errors().forEach(e -> sb.append("  - ").append(e).append('\n'));
        }
        sb.append("summary:\n---\n").append(report.summary()).append("\n---\n");
        if (!report.ok()) {
            sb.append("next: 子 Agent 未完成；请把上面的失败原因转述给用户，")
                    .append("需要变更确认时把 changeId 交给用户在确认卡片上确认。\n");
        } else {
            sb.append("next: 把结论转述给用户；若返回包含 changeId，提示用户在确认卡片确认。\n");
        }
        sb.append("evidence: project:").append(ctx.projectId()).append("/env:").append(target);
        return sb.toString();
    }
}
