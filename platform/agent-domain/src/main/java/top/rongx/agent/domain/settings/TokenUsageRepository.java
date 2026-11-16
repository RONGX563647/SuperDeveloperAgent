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
    }

    record DailyUsage(
            String day,
            long calls,
            long inputTokens,
            long cachedTokens,
            long outputTokens,
            long totalTokens) {
    }

    record ModelUsageSummary(
            String modelName,
            long calls,
            long inputTokens,
            long cachedTokens,
            long outputTokens,
            long totalTokens) {
    }

    record ProjectUsageSummary(
            String projectId,
            long calls,
            long inputTokens,
            long cachedTokens,
            long outputTokens,
            long totalTokens) {
    }

    record UsageSummary(
            int days,
            UsageTotals totals,
            List<DailyUsage> daily,
            List<ModelUsageSummary> byModel,
            List<ProjectUsageSummary> byProject) {
    }
}
