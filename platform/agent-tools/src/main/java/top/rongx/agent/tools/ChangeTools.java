package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.port.ChangeStore;
import org.springframework.stereotype.Component;

/**
 * 变更确认工具（L1：只产出计划与请求，<b>不能自己确认</b>）。
 *
 * <p>这是规约 §4.10「无 confirm 工具」的落地：本类只有 {@code request_confirmation}，
 * 没有 {@code confirm}。确认动作由人在 {@code POST /api/v1/changes/{id}/confirm} 完成。
 */
@Component
public class ChangeTools {

    /**
     * 可自动放行的写动作：dev/test 下由平台直接执行，没有独立的 apply 闭环。
     *
     * <p>把它们也登记成 change，是为了留下"谁在什么时候做了什么"的审计痕迹；
     * 这类 change 不会被 {@code /api/v1/changes/{id}/confirm} 执行，响应里会明确标注。
     */
    private static final java.util.Set<String> MANUAL_KINDS = java.util.Set.of(
            "env_ensure", "deploy_save_recipe", "env_mapping_draft");

    private final ChangeStore registry;

    public ChangeTools(ChangeStore registry) {
        this.registry = registry;
    }

    @Tool(name = "request_confirmation",
            description = """
                    发起一次变更并请求人工确认。写操作的前置步骤，本身不改变任何环境。
                    kind 分两类：
                    1) 可执行动作（stack_deploy / stack_rollback）：与 HTTP 的
                       /api/v1/actions/{action}/request 同一词汇表，确认后可 apply；
                    2) 可自动放行动作（env_ensure / deploy_save_recipe / env_mapping_draft）：
                       dev/test 下平台允许 Agent 直接执行，这里登记仅用于留痕，确认卡不会执行它。
                    调用后必须把 changeId 告诉用户，并提示"请在平台确认卡片上确认"。
                    不要假设用户已确认；确认结果由系统另行通知。""",
            readOnly = false, concurrencySafe = false)
    public String requestConfirmation(
            @ToolParam(name = "kind",
                    description = "动作名：stack_deploy / stack_rollback，或 "
                            + "env_ensure / deploy_save_recipe / env_mapping_draft")
            String kind,
            @ToolParam(name = "summary", description = "一句话说明将要做什么（不超过 120 字）") String summary,
            @ToolParam(name = "image_digest", description = "要发布的镜像 digest（sha256:...），可空",
                    required = false) String imageDigest,
            @ToolParam(name = "high_risk", description = "是否含 SQL / 依赖升级 / 安全配置；true 时需二次密码") boolean highRisk,
            ProjectContext ctx) {
        if (kind == null || kind.isBlank()) {
            return "err: kind 不能为空";
        }
        String wire = kind.trim().toLowerCase(java.util.Locale.ROOT);
        boolean manual = MANUAL_KINDS.contains(wire);
        if (!manual) {
            // 与 ChangeController.apply 的 ActionId.fromWire 使用同一词汇表：
            // 之前允许 RELEASE/RUNBOOK 等 kind，但它们在 apply 阶段无法解析成动作（闭环断裂）
            var actionId = top.rongx.agent.domain.action.ActionId.fromWire(wire);
            if (actionId.isEmpty()) {
                return "err: kind 必须是可执行动作（"
                        + java.util.Arrays.stream(top.rongx.agent.domain.action.ActionId.values())
                        .map(top.rongx.agent.domain.action.ActionId::wireName)
                        .collect(java.util.stream.Collectors.joining(", "))
                        + "）或可自动放行动作（" + String.join(", ", MANUAL_KINDS) + "），收到：" + kind;
            }
        }
        if (summary == null || summary.isBlank() || summary.length() > 120) {
            return "err: summary 不能为空且不超过 120 字";
        }
        if (imageDigest != null && !imageDigest.isBlank()
                && !imageDigest.matches("sha256:[0-9a-f]{64}")) {
            return "err: image_digest 格式非法（需 sha256:... 64 位十六进制）";
        }
        ChangePlan plan = registry.request(ctx.projectId(), ctx.env(),
                wire, summary, imageDigest, null, highRisk);
        String apply = manual
                ? "不支持（该动作在 dev/test 可自动放行，登记仅用于留痕；确认卡不会执行它）"
                : "确认后在变更卡片上执行";
        return """
                changeId: %s
                planHash: %s
                kind: %s
                env: %s
                apply: %s
                highRisk: %s
                next: 请在平台的确认卡片上确认（确认令牌 5 分钟内有效）；确认前不会执行任何操作。
                evidence: change:%s
                """.formatted(plan.changeId(), plan.planHash(), wire, plan.env(), apply,
                highRisk, plan.changeId());
    }
}
