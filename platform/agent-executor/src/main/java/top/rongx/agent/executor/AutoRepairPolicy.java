package top.rongx.agent.executor;

/**
 * 运行失败后的自动修复策略。
 *
 * <p>设计原则：平台只对「能自己验证」的故障自动动手，验证不过就绝不自动重跑，
 * 避免用一次失败换 N 次无效重试。所有开关都由配置注入，不写死任何环境路径或镜像标签。
 *
 * @param enabled         是否启用自动修复（关闭后保持原来的「失败即终态 + 人工重跑」）
 * @param maxAttempts     单次运行最多几轮「修复 → 验证 → 自动续跑」（防止死循环）
 * @param pullTimeoutMs   自动拉取工具链镜像的硬超时
 * @param agentFallback   确定性修复失败后是否交给发布 Agent 诊断（慢路径，可关）
 * @param autoResume      修复验证通过后是否自动从失败阶段续跑（关闭时只诊断、不重跑）
 */
public record AutoRepairPolicy(boolean enabled, int maxAttempts, long pullTimeoutMs,
                               boolean agentFallback, boolean autoResume) {

    /** 默认 3 分钟：足够拉常用工具链镜像，又不会让一次失败拖垮整条流水线。 */
    public static final long DEFAULT_PULL_TIMEOUT_MS = 180_000L;

    /** 最多 5 轮，防止配置写错造成无限重试。 */
    private static final int MAX_ATTEMPTS_LIMIT = 5;

    public AutoRepairPolicy {
        maxAttempts = Math.max(0, Math.min(maxAttempts, MAX_ATTEMPTS_LIMIT));
        pullTimeoutMs = pullTimeoutMs <= 0 ? DEFAULT_PULL_TIMEOUT_MS : pullTimeoutMs;
    }

    public static AutoRepairPolicy defaults() {
        return new AutoRepairPolicy(true, 2, DEFAULT_PULL_TIMEOUT_MS, true, true);
    }

    public static AutoRepairPolicy disabled() {
        return new AutoRepairPolicy(false, 0, DEFAULT_PULL_TIMEOUT_MS, false, false);
    }

    /** 供 Spring 配置装配（超时以秒为单位，便于写成环境变量）。 */
    public static AutoRepairPolicy of(boolean enabled, int maxAttempts, long pullTimeoutSeconds,
                                      boolean agentFallback, boolean autoResume) {
        return new AutoRepairPolicy(enabled, maxAttempts,
                Math.max(1L, pullTimeoutSeconds) * 1000L, agentFallback, autoResume);
    }
}
