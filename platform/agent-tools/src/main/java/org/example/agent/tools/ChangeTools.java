package org.example.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.example.agent.domain.change.ChangePlan;
import org.example.agent.domain.project.ProjectContext;
import org.example.agent.domain.port.ChangeStore;
import org.springframework.stereotype.Component;

/**
 * 变更确认工具（L1：只产出计划与请求，<b>不能自己确认</b>）。
 *
 * <p>这是规约 §4.10「无 confirm 工具」的落地：本类只有 {@code request_confirmation}，
 * 没有 {@code confirm}。确认动作由人在 {@code POST /api/v1/changes/{id}/confirm} 完成。
 */
@Component
public class ChangeTools {

    private final ChangeStore registry;

    public ChangeTools(ChangeStore registry) {
        this.registry = registry;
    }

    @Tool(name = "request_confirmation",
            description = """
                    发起一次变更并请求人工确认。写操作的前置步骤，本身不改变任何环境。
                    调用后必须把 changeId 告诉用户，并提示"请在平台确认卡片上确认"。
                    不要假设用户已确认；确认结果由系统另行通知。""",
            readOnly = false, concurrencySafe = false)
    public String requestConfirmation(
            @ToolParam(name = "kind", description = "变更类型：RELEASE / ROLLBACK / RUNBOOK / MIGRATION") String kind,
            @ToolParam(name = "summary", description = "一句话说明将要做什么（不超过 120 字）") String summary,
            @ToolParam(name = "image_digest", description = "要发布的镜像 digest（sha256:...），非发布类可空") String imageDigest,
            @ToolParam(name = "high_risk", description = "是否含 SQL / 依赖升级 / 安全配置；true 时需二次密码") boolean highRisk,
            ProjectContext ctx) {
        if (kind == null || kind.isBlank()) {
            return "err: kind 不能为空";
        }
        if (summary == null || summary.isBlank() || summary.length() > 120) {
            return "err: summary 不能为空且不超过 120 字";
        }
        if ("RELEASE".equals(kind) && (imageDigest == null || !imageDigest.matches("sha256:[0-9a-f]{64}"))) {
            return "err: 发布类变更必须提供合法的 image_digest";
        }
        ChangePlan plan = registry.request(ctx.projectId(), ctx.env(), kind, summary,
                imageDigest, null, highRisk);
        return """
                changeId: %s
                planHash: %s
                env: %s
                highRisk: %s
                next: 请在平台的确认卡片上确认（确认令牌 5 分钟内有效）；确认前不会执行任何操作。
                evidence: change:%s
                """.formatted(plan.changeId(), plan.planHash(), plan.env(), highRisk, plan.changeId());
    }
}
