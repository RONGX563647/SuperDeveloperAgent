package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.change.ChangeWire;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.port.ChangeStore;
import org.springframework.stereotype.Component;

/**
 * change_status 的<b>唯一</b>实现（BUG-28）。
 *
 * <p>此前 {@code ChangeTools} 与 {@code ChangeQueryTools} 各自声明了一份同名
 * {@code @Tool(name = "change_status")}，且被同时注册进 {@code agentToolkit}，
 * 谁生效随装配顺序漂移；弱化版只校验 projectId 不校验 env，跨环境读取变更单
 * 的越权校验可能失效。现在全平台只有这一处声明：
 *
 * <ul>
 *   <li>同时校验 projectId <b>且</b> env：跨项目/跨环境都拒绝且不回显状态；</li>
 *   <li>返回带 {@code next} 指引与 {@code planHash / kind / env / highRisk / executable}；</li>
 *   <li>支持留痕单（{@code env_ensure} 等）→ {@code RECORDED} 语义；</li>
 *   <li>{@code agentToolkit} 与 {@code readOnlyToolkit} 复用同一个 bean，两面语义一致。</li>
 * </ul>
 */
@Component
public class ChangeStatusTool {

    /**
     * 可自动放行的写动作（与 {@link ChangeTools} 的 MANUAL_KINDS 同一词汇表）：
     * 这类 change 不被 {@code /api/v1/changes/{id}/confirm} 执行，查询时标注 RECORDED。
     */
    static final java.util.Set<String> MANUAL_KINDS = java.util.Set.of(
            "env_ensure", "deploy_save_recipe", "env_mapping_draft");

    private final ChangeStore registry;

    public ChangeStatusTool(ChangeStore registry) {
        this.registry = registry;
    }

    /**
     * 只读查询变更单状态：让 Agent 能轮询「人确认了没、执行了没」。
     *
     * <p>为什么必须与 confirm 分开：confirm 会<b>推送确认卡片</b>，是有副作用的动作。
     * 本工具不推卡、不签发令牌、不执行任何动作；返回里同时给出 {@code next}。
     */
    @Tool(name = "change_status", description = "Read a change status, no side effects.",
            readOnly = true, concurrencySafe = true)
    public String changeStatus(@ToolParam(name = "change_id",
                    description = "request_confirmation 或 propose_patch 返回的 changeId")
            String changeId,
            ProjectContext ctx) {
        if (changeId == null || changeId.isBlank()) {
            return "err: change_id 不能为空";
        }
        ChangePlan plan = registry.find(changeId.trim()).orElse(null);
        if (plan == null) {
            return "err: 变更不存在或已过期：" + changeId
                    + "（只保留最近一批变更单；需要时重新登记）";
        }
        // 与 confirm 同一套越权校验：模型编造单号也读不到别的项目/环境的变更
        if (!plan.projectId().equals(ctx.projectId())) {
            return "err: 该变更不属于当前项目（" + plan.projectId() + "），拒绝查询";
        }
        if (!plan.env().equalsIgnoreCase(ctx.env())) {
            return "err: 该变更的目标环境是 " + plan.env() + "，与当前会话环境 "
                    + ctx.env() + " 不一致，拒绝查询";
        }
        boolean manual = MANUAL_KINDS.contains(plan.kind());
        String status = manual ? "RECORDED" : plan.status().name();
        String next = switch (status) {
            case "APPLIED" -> "已执行完成：直接汇报结果，不要再次推卡。";
            case "CONFIRMED" -> "操作人已确认但尚未执行：可调用 apply 执行，不要再推卡。";
            case "FAILED" -> "上次执行失败：先向操作人说明原因，需要重试时登记新的变更。";
            case "RECORDED" -> "留痕单：无需确认也不会推卡，直接继续后续步骤。";
            default -> "尚未确认：确认卡片已在对话中，请等操作人本人确认。"
                    + "可以调用 confirm 重推卡片，但不要假设已经确认。";
        };
        return """
                status: %s
                %s
                planHash: %s
                kind: %s
                env: %s
                highRisk: %s
                executable: %s
                summary: %s
                next: %s
                note: 本工具只读：不推确认卡、不签发令牌、不执行动作。
                """.formatted(status, ChangeWire.line(plan.changeId()), plan.planHash(), plan.kind(),
                plan.env(), plan.highRisk(), plan.executable(), plan.summary(), next);
    }
}
