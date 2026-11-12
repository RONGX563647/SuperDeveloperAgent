package top.rongx.agent.gateway.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.error.DomainException;
import top.rongx.agent.domain.patch.CodePatch;
import top.rongx.agent.domain.port.CodePatcher;
import top.rongx.agent.domain.port.PatchStore;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.gateway.security.PasswordVerifier;
import top.rongx.agent.domain.port.ChangeStore;
import top.rongx.agent.governance.ConfirmToken;
import top.rongx.agent.governance.ConfirmTokenService;
import top.rongx.agent.tools.ActionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 变更确认入口。
 *
 * <p><b>这是整个平台唯一签发确认令牌的地方</b>——必须由已登录用户调用。
 * Agent 没有对应工具，因此无法自行确认（规约 §4.10 第一道守卫）。
 */
@RestController
@RequestMapping("/api/v1/changes")
public class ChangeController {

    /** 补丁类变更的 kind 前缀：{@code patch:<patchId>}。 */
    private static final String PATCH_PREFIX = "patch:";

    private final ChangeStore registry;
    private final ConfirmTokenService tokens;
    private final PasswordVerifier passwordVerifier;
    private final ActionService actions;
    private final ProjectContextResolver contextResolver;
    private final top.rongx.agent.gateway.auth.CurrentUser currentUser;
    private final CodePatcher codePatcher;
    private final PatchStore patches;

    public ChangeController(ChangeStore registry,
                            ConfirmTokenService tokens,
                            PasswordVerifier passwordVerifier,
                            ActionService actions,
                            ProjectContextResolver contextResolver,
                            top.rongx.agent.gateway.auth.CurrentUser currentUser,
                            CodePatcher codePatcher,
                            PatchStore patches) {
        this.registry = registry;
        this.tokens = tokens;
        this.passwordVerifier = passwordVerifier;
        this.actions = actions;
        this.contextResolver = contextResolver;
        this.currentUser = currentUser;
        this.codePatcher = codePatcher;
        this.patches = patches;
    }

    /**
     * 用确认令牌执行变更（L3 的落地点）。
     *
     * <p>三道校验：令牌一次性且未过期 → 绑定同一个 changeId → 绑定的 planHash 等于**当前配方**的哈希。
     * 任何一步不符都拒绝，因此"确认 A、执行 B"在架构上不可能发生。
     *
     * @param changeId          变更单
     * @param confirmToken      确认令牌（由 {@code /confirm} 签发）
     * @param userId            执行人
     */
    @PostMapping("/{changeId}/apply")
    public ActionController.RunView apply(@PathVariable String changeId,
                                          @RequestHeader(value = "X-Confirm-Token", required = false) String confirmToken) {
        // 1. 认证（Sa-Token 会话）与令牌头校验
        String userId = currentUser.requireUserId();
        if (confirmToken == null || confirmToken.isBlank()) {
            throw new IllegalArgumentException("缺少确认令牌：需要 X-Confirm-Token 头");
        }
        // 2. 取出变更单（不存在即拒绝）
        ChangePlan plan = registry.find(changeId)
                .orElseThrow(() -> DomainException.notFound("变更不存在: " + changeId));
        // 3. 消费令牌：一次性 + 未过期 + 绑定 changeId 与当前 planHash（防篡改复用）
        tokens.consume(confirmToken, changeId, plan.planHash());
        // 4. 补丁类变更：把补丁提交到新分支（不碰用户当前分支）
        if (plan.kind().startsWith(PATCH_PREFIX)) {
            return applyPatch(plan);
        }
        // 5. 解析动作并解析项目上下文
        ActionId action = ActionId.fromWire(plan.kind())
                .orElseThrow(() -> new IllegalArgumentException(
                        "该变更不是可执行动作（kind=" + plan.kind() + "）"));
        ProjectContext ctx = contextResolver.resolve(plan.projectId(), plan.env(), userId);
        // 5. 带令牌落地执行（ActionService 再次核对 planHash）
        return ActionController.RunView.of(actions.apply(ctx, action, plan.planHash()));
    }

    /** 确认卡片数据：展示字段而非自然语言摘要（规约 §4.10 第四道守卫）。 */
    @GetMapping("/{changeId}")
    public ChangeView view(@PathVariable String changeId) {
        ChangePlan plan = registry.find(changeId)
                .orElseThrow(() -> DomainException.notFound("变更不存在: " + changeId));
        return new ChangeView(plan.changeId(), plan.projectId(), plan.env(), plan.kind(),
                plan.summary(), plan.imageDigest(), plan.highRisk(), plan.planHash(),
                plan.highRisk() ? "SECOND_PASSWORD" : "CONFIRM_TOKEN");
    }

    /** 补丁类变更的 diff（供确认卡片复核）。 */
    @GetMapping("/{changeId}/patch")
    public PatchView patch(@PathVariable String changeId) {
        currentUser.requireUserId();
        ChangePlan plan = registry.find(changeId)
                .orElseThrow(() -> DomainException.notFound("变更不存在: " + changeId));
        CodePatch patch = requirePatch(plan);
        return new PatchView(patch.patchId(), patch.baseSha(), patch.branch(), patch.diff(),
                patch.edits().stream().map(CodePatch.FileEdit::path).toList(),
                patch.added(), patch.removed());
    }

    /** 带令牌应用补丁：提交到独立分支，返回新提交 sha（可直接用于重跑）。 */
    private ActionController.RunView applyPatch(ChangePlan plan) {
        CodePatch patch = requirePatch(plan);
        long start = System.currentTimeMillis();
        String commit = codePatcher.apply(patch);
        patches.remove(patch.patchId());
        long cost = System.currentTimeMillis() - start;
        String log = """
                patch: %s
                branch: %s
                commit: %s
                files:
                %s
                """.formatted(patch.patchId(), patch.branch(), commit,
                patch.edits().stream().map(e -> "- " + e.path())
                        .collect(java.util.stream.Collectors.joining("\n")));
        return ActionController.RunView.of(new ActionResult(
                StageResult.success("patch", 0, List.of(), cost), log));
    }

    private CodePatch requirePatch(ChangePlan plan) {
        if (!plan.kind().startsWith(PATCH_PREFIX)) {
            throw new IllegalArgumentException("该变更不是代码补丁：" + plan.kind());
        }
        String patchId = plan.kind().substring(PATCH_PREFIX.length());
        CodePatch patch = patches.find(patchId)
                .orElseThrow(() -> DomainException.notFound(
                        "补丁不存在或已过期: " + patchId));
        if (!patch.projectId().equals(plan.projectId())) {
            throw new IllegalArgumentException("补丁与变更单的项目不一致");
        }
        return patch;
    }

    /**
     * 人确认变更，签发一次性令牌。
     *
     * @param changeId 变更单
     * @param userId   已登录用户
     * @param request  高危变更需带密码
     */
    @PostMapping("/{changeId}/confirm")
    public ConfirmResponse confirm(@PathVariable String changeId,
                                   @RequestBody(required = false) ConfirmRequest request) {
        String userId = currentUser.requireUserId();
        ChangePlan plan = registry.find(changeId)
                .orElseThrow(() -> DomainException.notFound("变更不存在: " + changeId));

        if (plan.highRisk()) {
            String password = request == null ? null : request.password();
            if (password == null || password.isBlank()) {
                throw new IllegalArgumentException("高危变更需要二次密码确认");
            }
            if (!passwordVerifier.verify(userId, password)) {
                throw new IllegalArgumentException("密码校验未通过，高危变更无法确认");
            }
        }

        ConfirmToken token = tokens.issue(userId, plan.changeId(), plan.planHash());
        return new ConfirmResponse(token.token(), plan.changeId(), plan.planHash(),
                token.expiresAt(), Map.of("note", "令牌一次性、5 分钟有效、绑定计划哈希"));
    }

    /** 确认卡片视图。 */
    public record ChangeView(String changeId, String projectId, String env, String kind,
                             String summary, String imageDigest, boolean highRisk,
                             String planHash, String gate) {
    }

    /** 确认请求。 */
    public record ConfirmRequest(String password) {
    }

    /** 补丁视图（确认卡片复核用）。 */
    public record PatchView(String patchId, String baseSha, String branch, String diff,
                            List<String> files, int added, int removed) {
    }

    /** 确认响应。 */
    public record ConfirmResponse(String confirmToken, String changeId, String planHash,
                                  Instant expiresAt, Map<String, String> meta) {
    }
}
