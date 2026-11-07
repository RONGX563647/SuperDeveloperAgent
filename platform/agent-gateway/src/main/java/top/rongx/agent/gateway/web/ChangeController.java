package top.rongx.agent.gateway.web;

import java.time.Instant;
import java.util.Map;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.gateway.security.PasswordVerifier;
import top.rongx.agent.domain.port.ChangeStore;
import top.rongx.agent.governance.ConfirmToken;
import top.rongx.agent.governance.ConfirmTokenService;
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

    private final ChangeStore registry;
    private final ConfirmTokenService tokens;
    private final PasswordVerifier passwordVerifier;

    public ChangeController(ChangeStore registry,
                            ConfirmTokenService tokens,
                            PasswordVerifier passwordVerifier) {
        this.registry = registry;
        this.tokens = tokens;
        this.passwordVerifier = passwordVerifier;
    }

    /** 确认卡片数据：展示字段而非自然语言摘要（规约 §4.10 第四道守卫）。 */
    @GetMapping("/{changeId}")
    public ChangeView view(@PathVariable String changeId) {
        ChangePlan plan = registry.find(changeId)
                .orElseThrow(() -> new IllegalArgumentException("变更不存在: " + changeId));
        return new ChangeView(plan.changeId(), plan.projectId(), plan.env(), plan.kind(),
                plan.summary(), plan.imageDigest(), plan.highRisk(), plan.planHash(),
                plan.highRisk() ? "SECOND_PASSWORD" : "CONFIRM_TOKEN");
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
                                   @RequestHeader(value = "X-User-Id", required = false) String userId,
                                   @RequestBody(required = false) ConfirmRequest request) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("未认证：缺少 X-User-Id");
        }
        ChangePlan plan = registry.find(changeId)
                .orElseThrow(() -> new IllegalArgumentException("变更不存在: " + changeId));

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

    /** 确认响应。 */
    public record ConfirmResponse(String confirmToken, String changeId, String planHash,
                                  Instant expiresAt, Map<String, String> meta) {
    }
}
