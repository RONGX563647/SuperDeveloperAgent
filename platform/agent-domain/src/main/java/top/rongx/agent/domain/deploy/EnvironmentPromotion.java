package top.rongx.agent.domain.deploy;

import java.util.function.Function;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.domain.run.StageStatus;

/**
 * 环境晋升规则：**服务器上操作之前，必须已有"测试机成功案例"**。
 *
 * <p>这不是审批流（不要求第二个人点同意），而是一次**证据校验**：
 * <ol>
 *   <li>测试环境有已固化的可复现脚本；</li>
 *   <li>该脚本关联的证据运行仍然是 SUCCEEDED；</li>
 *   <li>证据验证的提交与本次要发布的提交一致。</li>
 * </ol>
 *
 * <p>缺任何一条都拒绝在服务器上操作。放在 domain 是为了让引擎与 HTTP 层用同一份规则，
 * 而不是各写一遍（两处不一致 = 有人能找到绕过路径）。
 */
public final class EnvironmentPromotion {

    private EnvironmentPromotion() {
    }

    /** 需要先有测试环境成功案例的环境。 */
    public static boolean requiresTestEvidence(String env) {
        return "prod".equals(env);
    }

    /**
     * 判断能否在目标环境操作。
     *
     * @param store    环境脚本存储（null = 未启用，视为无法证明，拒绝）
     * @param statusOf 按 runId 查运行状态（由调用方提供，避免 domain 依赖引擎实现）
     * @param projectId 项目
     * @param env      目标环境
     * @param sha      本次要发布的提交（可为 null：不校验提交一致）
     * @return 不满足时的可读原因；满足返回 null
     */
    public static String problem(RecipeStore store, Function<String, StageStatus> statusOf,
                                 String projectId, String env, String sha) {
        if (!requiresTestEvidence(env)) {
            return null;
        }
        if (store == null) {
            return "未启用环境脚本存储，无法确认测试机成功案例（拒绝在服务器上操作）";
        }
        var test = store.current(projectId, "test");
        if (test.isEmpty()) {
            return "测试环境还没有成功案例：请先在测试环境跑通一次（平台会自动固化为可复现脚本）";
        }
        DeployRecipe recipe = test.get();
        if (!recipe.hasEvidence()) {
            return "测试环境的脚本 " + recipe.env() + " v" + recipe.version() + " 没有关联成功运行证据";
        }
        StageStatus evidence = statusOf == null ? null : statusOf.apply(recipe.evidenceRunId());
        if (evidence != StageStatus.SUCCEEDED) {
            return "测试环境证据运行 " + recipe.evidenceRunId() + " 状态为 " + evidence + "（需要成功）";
        }
        if (sha != null && !sha.isBlank() && recipe.gitSha() != null && !recipe.gitSha().isBlank()
                && !recipe.gitSha().equals(sha)) {
            return "测试环境验证的是提交 " + shortSha(recipe.gitSha())
                    + "，本次发布是 " + shortSha(sha) + "：请先在测试环境验证该提交";
        }
        return null;
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() <= 7 ? String.valueOf(sha) : sha.substring(0, 7);
    }
}
