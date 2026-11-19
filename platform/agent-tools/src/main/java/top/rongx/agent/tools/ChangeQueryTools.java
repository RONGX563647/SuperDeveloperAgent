package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.port.ChangeStore;

/**
 * 变更单只读查询工具。
 *
 * <p>与 {@link ChangeTools} 拆开的原因：写工具（登记/推卡）不能出现在生产只读面，
 * 但"我的单子确认了没"必须能查——确认卡是异步广播，模型无法等待，只能事后查。
 * 只读面装配见 {@code ToolCatalog#registerReadOnlyTools}。
 */
@Component
public class ChangeQueryTools {

    private final ChangeStore registry;

    public ChangeQueryTools(ChangeStore registry) {
        this.registry = registry;
    }

    /**
     * 查询变更单状态（只读）。
     *
     * <p>确认卡是异步广播，模型无法"等待"；有了这个工具就能在后续轮次里主动查
     * PENDING / CONFIRMED / APPLIED / FAILED，而不用靠猜或反复推卡。
     */
    @Tool(name = "change_status",
            description = "Get change status: PENDING..FAILED.",
            readOnly = true, concurrencySafe = true)
    public String status(
            @ToolParam(name = "change_id",
                    description = "request_confirmation 返回的 changeId（chg-...）")
            String changeId,
            ProjectContext ctx) {
        if (changeId == null || changeId.isBlank()) {
            return "err: change_id 不能为空";
        }
        ChangePlan plan = registry.find(changeId.trim()).orElse(null);
        if (plan == null) {
            return "err: 变更不存在或已过期：" + changeId;
        }
        if (!plan.projectId().equals(ctx.projectId())) {
            return "err: 该变更不属于当前项目（" + plan.projectId() + "）";
        }
        return """
                changeId: %s
                status: %s
                kind: %s
                env: %s
                executable: %s
                highRisk: %s
                createdAt: %s
                summary: %s
                """.formatted(plan.changeId(), plan.status().name(), plan.kind(), plan.env(),
                plan.executable(), plan.highRisk(), plan.createdAt(), plan.summary());
    }
}
