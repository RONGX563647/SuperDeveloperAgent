package top.rongx.agent.domain.settings;

import java.util.List;

/**
 * 模型 token 用量仓储。
 *
 * <p>每次模型调用记一条明细，同时累加到按天聚合表；会话累计、按模型/项目汇总
 * 都从聚合结果读取，避免每次刷新都全表扫描明细。
 */
public interface TokenUsageRepository {

    void record(ModelUsage usage);

    /** 会话明细（最近 N 条，按时间倒序）。 */
    List<ModelUsage> findBySession(String sessionId, int limit);

    /** 会话累计。 */
    UsageTotals sessionTotals(String sessionId);

    /** 全局用量汇总（最近 days 天）。 */
    UsageSummary summary(int days);

    void deleteBySession(String sessionId);

    /** 一次模型调用的用量明细。 */
    record ModelUsage(
            String sessionId,
            String projectId,
            String userId,
            String connectionId,
            String modelName,
            int inputTokens,
            int cachedTokens,
            int outputTokens,
            long createdAt) {

        public long totalTokens() {
            return (long) inputTokens + outputTokens;
        }
    }

    record UsageTotals(
            long calls,
            long inputTokens,
            long cachedTokens,
            long outputTokens,
            long totalTokens,
            long lastAt) {

        public static UsageTotals empty() {
            return new UsageTotals(0, 0, 0, 0, 0, 0);
        }

        /** 缓存命中率（0–100 的整数百分比）；没有输入 token 时为 0。 */
        public int cacheHitRate() {
            return inputTokens <= 0 ? 0
                    : (int) Math.round(cachedTokens * 100.0 / inputTokens);
        }
    }

    record DailyUsage(
            String day,
            long calls,
            long inputTokens,
            long cachedTokens,
            long outputTokens,
            long totalTokens) {

        /** 当日缓存命中率（0–100 整数）；无输入时为 0。 */
        public int cacheHitRate() {
            return inputTokens <= 0 ? 0
                    : (int) Math.round(cachedTokens * 100.0 / inputTokens);
        }
    }

    record ModelUsageSummary(
            String modelName,
            long calls,
            long inputTokens,
            long cachedTokens,
            long outputTokens,
            long totalTokens) {

        /** 该模型的缓存命中率（0–100 整数）；无输入时为 0。 */
        public int cacheHitRate() {
            return inputTokens <= 0 ? 0
                    : (int) Math.round(cachedTokens * 100.0 / inputTokens);
        }
    }

    record ProjectUsageSummary(
            String projectId,
            long calls,
            long inputTokens,
            long cachedTokens,
            long outputTokens,
            long totalTokens) {

        /** 该项目的缓存命中率（0–100 整数）；无输入时为 0。 */
        public int cacheHitRate() {
            return inputTokens <= 0 ? 0
                    : (int) Math.round(cachedTokens * 100.0 / inputTokens);
        }
    }

    /**
     * 全局用量汇总。
     *
     * @param cacheHitRate 总体缓存命中率（0–100 整数）。这是判断「压缩 prompt
     *                     有没有省到钱」（FEAT-5 第 3 项）的唯一可信指标。
     *                     做成组件而不是派生方法：springdoc 只序列化 record 组件，
     *                     派生方法不会出现在 OpenAPI 里，前端类型也就拿不到。
     *                     传 null 时按 totals 推导，旧调用点因此不用改。
     */
    record UsageSummary(
            int days,
            UsageTotals totals,
            List<DailyUsage> daily,
            List<ModelUsageSummary> byModel,
            List<ProjectUsageSummary> byProject,
            Integer cacheHitRate) {

        public UsageSummary {
            cacheHitRate = cacheHitRate == null
                    ? (totals == null ? 0 : totals.cacheHitRate())
                    : cacheHitRate;
        }

        /** 兼容旧调用：命中率由 totals 推导。 */
        public UsageSummary(int days, UsageTotals totals, List<DailyUsage> daily,
                            List<ModelUsageSummary> byModel,
                            List<ProjectUsageSummary> byProject) {
            this(days, totals, daily, byModel, byProject, null);
        }
    }
}
