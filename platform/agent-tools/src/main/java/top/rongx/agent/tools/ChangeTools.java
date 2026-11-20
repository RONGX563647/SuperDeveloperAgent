package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.change.ChangeWire;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.port.ChangeStore;
import org.springframework.stereotype.Component;

/**
 * 变更确认工具（L1：只产出计划与请求，<b>不能自己确认</b>）。
 *
 * <p>本类有两个工具，但都<b>不具备签发令牌的能力</b>：
 * <ul>
 *   <li>{@code request_confirmation}：登记变更单；</li>
 *   <li>{@code confirm}：把已有变更单再次提交给<b>当前操作人</b>确认（返回
 *       {@code PENDING_USER_CONFIRMATION}），只发确认卡片，不执行、不签发令牌。</li>
 * </ul>
 *
 * <p>令牌的唯一签发点仍是已登录用户的 {@code POST /api/v1/changes/{id}/confirm}
 * （{@code ConfirmTokenService} 不在工具层可达）。这样模型可以"请求确认"，但永远无法"自我批准"。
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

    @Tool(name = "request_confirmation", description = "Register a change and request approval.",
            readOnly = false, concurrencySafe = false)
    public String requestConfirmation(
            @ToolParam(name = "kind",
                    description = "动作名：stack_deploy / stack_rollback，或 "
                            + "env_ensure / deploy_save_recipe / env_mapping_draft")
            String kind,
            @ToolParam(name = "summary", description = "一句话说明将要做什么（超过 120 字会截断）") String summary,
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
        if (summary == null || summary.isBlank()) {
            return "err: summary 不能为空（一句话说明将要做什么）";
        }
        // 变更单摘要上限 120 字：超长截断而不是报错，避免模型重试浪费一轮
        String shortSummary = summary.trim();
        if (shortSummary.length() > 120) {
            shortSummary = shortSummary.substring(0, 120);
        }
        if (imageDigest != null && !imageDigest.isBlank()
                && !imageDigest.matches("sha256:[0-9a-f]{64}")) {
            return "err: image_digest 格式非法（需 sha256:... 64 位十六进制）";
        }
        ChangePlan plan = registry.request(ctx.projectId(), ctx.env(),
                wire, shortSummary, imageDigest, null, highRisk);
        String apply = manual
                ? "不支持（该动作在 dev/test 可自动放行，登记仅用于留痕；确认卡不会执行它）"
                : "确认后在变更卡片上执行";
        // 留痕单不会被推成确认卡（推了也必然 apply 400），提示模型别等用户确认
        String next = manual
                ? "该动作仅留痕，无需人工确认，不会推送确认卡；请直接继续后续步骤。"
                : "确认卡已推到当前对话（确认令牌 5 分钟内有效）；确认前不会执行任何操作。";
        return """
                %s
                planHash: %s
                kind: %s
                env: %s
                apply: %s
                highRisk: %s
                next: %s
                evidence: change:%s
                """.formatted(ChangeWire.line(plan.changeId()), plan.planHash(), wire, plan.env(), apply,
                highRisk, next, plan.changeId());
    }

    /**
     * 向<b>当前操作人</b>发起确认（不是模型自我批准）。
     *
     * <p>安全边界：本方法只读变更单；不调用 {@code ConfirmTokenService}，不触发任何执行。
     * 真正的确认仍由用户在平台确认卡片上完成（令牌只由已登录的 HTTP 请求签发）。
     *
     * <p>之所以提供这个工具：模型在长流程里需要"明确地把单号推到用户面前"，
     * 而不是只在正文里写一句"请确认"——后者无法驱动确认卡片，用户容易漏掉。
     *
     * <p>返回的 status 一律取自 {@link ChangePlan#status()}：已经确认/执行过的单
     * 直接回报真实状态，避免模型看到硬编码的 PENDING 而反复推卡。
     */
    @Tool(name = "confirm", description = "Resend the approval card for a change.",
            readOnly = false, concurrencySafe = false)
    public String confirm(@ToolParam(name = "change_id",
                    description = "request_confirmation 返回的 changeId（chg-...）")
            String changeId,
            ProjectContext ctx) {
        if (changeId == null || changeId.isBlank()) {
            return "err: change_id 不能为空";
        }
        ChangePlan plan = registry.find(changeId.trim()).orElse(null);
        if (plan == null) {
            return "err: 变更不存在或已过期：" + changeId
                    + "（请先用 request_confirmation 登记，再提交确认）";
        }
        // 防跨项目/跨环境确认：模型即使编造单号也无法把别处的变更推给当前用户
        if (!plan.projectId().equals(ctx.projectId())) {
            return "err: 该变更不属于当前项目（" + plan.projectId() + "），拒绝确认";
        }
        if (!plan.env().equalsIgnoreCase(ctx.env())) {
            return "err: 该变更的目标环境是 " + plan.env() + "，与当前会话环境 "
                    + ctx.env() + " 不一致，拒绝确认";
        }
        boolean manual = MANUAL_KINDS.contains(plan.kind());
        if (manual) {
            // 留痕单没有 /apply 闭环：明确告知模型不需要也不能等确认，避免反复推卡
            return """
                    status: RECORDED
                    %s
                    kind: %s
                    env: %s
                    gate: NO_TOKEN
                    summary: %s
                    next: 该变更仅用于留痕，无需确认，也不会推送确认卡；请直接继续后续步骤。
                    """.formatted(ChangeWire.line(plan.changeId()), plan.kind(), plan.env(), plan.summary());
        }
        String gate = plan.highRisk() ? "SECOND_PASSWORD" : "CONFIRM_TOKEN";
        if (plan.status() == ChangePlan.Status.APPLIED) {
            return """
                    status: APPLIED
                    %s
                    planHash: %s
                    kind: %s
                    env: %s
                    summary: %s
                    next: 该变更已执行完成，无需再次确认或推卡；直接汇报结果即可。
                    """.formatted(ChangeWire.line(plan.changeId()), plan.planHash(), plan.kind(), plan.env(),
                    plan.summary());
        }
        if (plan.status() == ChangePlan.Status.CONFIRMED) {
            return """
                    status: CONFIRMED
                    %s
                    planHash: %s
                    kind: %s
                    env: %s
                    summary: %s
                    next: 操作人已确认，可直接调用 apply 执行；不要再推送确认卡片。
                    """.formatted(ChangeWire.line(plan.changeId()), plan.planHash(), plan.kind(), plan.env(),
                    plan.summary());
        }
        if (plan.status() == ChangePlan.Status.FAILED) {
            return """
                    status: FAILED
                    %s
                    planHash: %s
                    kind: %s
                    env: %s
                    summary: %s
                    next: 上次执行失败；先向操作人说明失败原因，需要重试时再登记新的变更。
                    """.formatted(ChangeWire.line(plan.changeId()), plan.planHash(), plan.kind(), plan.env(),
                    plan.summary());
        }
        return """
                status: PENDING_USER_CONFIRMATION
                %s
                planHash: %s
                kind: %s
                env: %s
                highRisk: %s
                gate: %s
                summary: %s
                next: 确认卡片已在对话中展开，请操作人本人确认（%s）。
                note: 本工具不签发令牌、不执行动作；令牌只由已登录用户调用
                      POST /api/v1/changes/%s/confirm 签发。
                """.formatted(ChangeWire.line(plan.changeId()), plan.planHash(), plan.kind(), plan.env(),
                plan.highRisk(), gate, plan.summary(),
                plan.highRisk() ? "高危变更需二次密码" : "一次性令牌 5 分钟有效",
                plan.changeId());
    }

    // change_status 不在这里声明（BUG-28）：唯一实现在 ChangeStatusTool，
    // agentToolkit / readOnlyToolkit 复用同一个 bean，避免重名冲突与越权校验口径漂移。
}
