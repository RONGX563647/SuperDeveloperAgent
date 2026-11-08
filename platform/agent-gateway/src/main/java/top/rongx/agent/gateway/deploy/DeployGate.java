package top.rongx.agent.gateway.deploy;

import org.springframework.stereotype.Component;
import top.rongx.agent.domain.change.PlanHasher;
import top.rongx.agent.domain.deploy.EnvironmentPromotion;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.governance.ConfirmTokenService;

/**
 * 服务器操作的双重前置（两条入口共用，避免"换个 URL 就绕过"）：
 *
 * <ol>
 *   <li><b>证据</b>：测试环境已有成功案例（自动校验，规则在 domain）；</li>
 *   <li><b>人为</b>：一次性的确认令牌（绑定 project + env + sha，5 分钟、用过即废）。</li>
 * </ol>
 *
 * <p>其余环境（dev / test）不做任何拦截：agent 可以全权跑，直到跑通并固化。
 */
@Component
public class DeployGate {

    private final RecipeStore recipes;
    private final PipelineEngine engine;
    private final ConfirmTokenService tokens;

    public DeployGate(RecipeStore recipes, PipelineEngine engine, ConfirmTokenService tokens) {
        this.recipes = recipes;
        this.engine = engine;
        this.tokens = tokens;
    }

    /** 测试证据是否齐备（返回 null 表示齐备）。 */
    public String evidenceProblem(String projectId, String env, String sha) {
        return EnvironmentPromotion.problem(recipes, engine::status, projectId, env, sha);
    }

    /**
     * 高危操作校验：环境需要证据 + 令牌时必须都满足。
     *
     * @throws IllegalArgumentException E-601（证据不足）/ E-602（缺少人工确认）
     */
    public void requireIfNeeded(String projectId, String env, String sha, String confirmToken) {
        if (!EnvironmentPromotion.requiresTestEvidence(env)) {
            return;
        }
        String problem = evidenceProblem(projectId, env, sha);
        if (problem != null) {
            throw new IllegalArgumentException("E-601 " + problem);
        }
        if (confirmToken == null || confirmToken.isBlank()) {
            throw new IllegalArgumentException(
                    "E-602 在服务器上操作需要人工确认：请先在部署面板点「确认发布」获取令牌");
        }
        tokens.consume(confirmToken, changeId(projectId, env, sha), planHash(projectId, env, sha));
    }

    /** 签发令牌（调用方必须已校验证据）。 */
    public String issue(String userId, String projectId, String env, String sha) {
        return tokens.issue(userId, changeId(projectId, env, sha), planHash(projectId, env, sha)).token();
    }

    public static String changeId(String projectId, String env, String sha) {
        return "deploy:" + projectId + ":" + env + ":" + sha.substring(0, 7);
    }

    /** 计划哈希：绑定"在哪个环境发哪个提交"，防止确认 A 执行 B。 */
    public static String planHash(String projectId, String env, String sha) {
        return PlanHasher.hash(projectId, env, "deploy", "release", sha);
    }
}
