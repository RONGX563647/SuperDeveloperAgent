package top.rongx.agent.infra.settings;

import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.settings.TokenUsageRepository;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * token 用量明细（JPA，统一库 {@code _platform/platform.db}）。
 *
 * <p>明细只追加不覆盖：按天 / 模型 / 项目的聚合在查询时完成，
 * 删除会话时能连同它的用量一起物理清掉，不会留下对不上的汇总数字。
 */
@Repository
public class SqliteTokenUsageRepository implements TokenUsageRepository {

    private static final String TOTALS_SQL = """
            SELECT COUNT(*) calls,
                   COALESCE(SUM(input_tokens), 0) input_tokens,
                   COALESCE(SUM(cached_tokens), 0) cached_tokens,
                   COALESCE(SUM(output_tokens), 0) output_tokens,
                   COALESCE(SUM(total_tokens), 0) total_tokens,
                   COALESCE(MAX(created_at), 0) last_at
            FROM token_usage WHERE %s
            """;

    private final PlatformDb db;

    @Autowired
    public SqliteTokenUsageRepository(PlatformDb db) {
        this.db = db;
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public SqliteTokenUsageRepository(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this(PlatformDb.sqlite(Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.db")));
    }

    @Override
    public void record(ModelUsage usage) {
        if (usage == null || usage.sessionId() == null || usage.sessionId().isBlank()) {
            return;
        }
        try {
            db.run(em -> PlatformDb.update(em, """
                    INSERT INTO token_usage
                        (session_id, project_id, user_id, connection_id, model_name,
                         input_tokens, cached_tokens, output_tokens, total_tokens, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """,
                    usage.sessionId(),
                    usage.projectId(),
                    usage.userId(),
                    usage.connectionId(),
                    usage.modelName(),
                    Math.max(0, usage.inputTokens()),
                    Math.max(0, usage.cachedTokens()),
                    Math.max(0, usage.outputTokens()),
                    usage.totalTokens(),
                    usage.createdAt() > 0 ? usage.createdAt() : System.currentTimeMillis()));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "token 用量写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<ModelUsage> findBySession(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            return db.read(em -> PlatformDb.rows(em, """
                    SELECT session_id, project_id, user_id, connection_id, model_name,
                           input_tokens, cached_tokens, output_tokens, created_at
                    FROM token_usage WHERE session_id = ?
                    ORDER BY created_at DESC LIMIT ?
                    """, sessionId, Math.max(1, limit)).stream()
                    .map(row -> new ModelUsage(
                            (String) row[0], (String) row[1],
                            (String) row[2], (String) row[3],
                            (String) row[4], ((Number) row[5]).intValue(),
                            ((Number) row[6]).intValue(), ((Number) row[7]).intValue(),
                            ((Number) row[8]).longValue()))
                    .toList());
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "token 用量查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public UsageTotals sessionTotals(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UsageTotals.empty();
        }
        try {
            return db.read(em -> PlatformDb.row(em,
                    TOTALS_SQL.formatted("session_id = ?"), sessionId)
                    .map(SqliteTokenUsageRepository::totals)
                    .orElseGet(UsageTotals::empty));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "会话 token 汇总失败：" + e.getMessage(), e);
        }
    }

    @Override
    public UsageSummary summary(int days) {
        int window = Math.max(1, Math.min(days, 365));
        long since = LocalDate.now().minusDays(window - 1L)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try {
            return db.read(em -> new UsageSummary(window,
                    queryTotals(em, since),
                    queryDaily(em, since),
                    queryByModel(em, since),
                    queryByProject(em, since)));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "token 用量汇总失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            db.run(em -> PlatformDb.update(em,
                    "DELETE FROM token_usage WHERE session_id = ?", sessionId));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "token 用量删除失败：" + e.getMessage(), e);
        }
    }

    private static UsageTotals queryTotals(EntityManager em, long since) {
        return PlatformDb.row(em, TOTALS_SQL.formatted("created_at >= ?"), since)
                .map(SqliteTokenUsageRepository::totals)
                .orElseGet(UsageTotals::empty);
    }

    /** 按本地时区日期分组：SQL 只取明细，日期换算留给 Java，避免依赖 SQLite 的 strftime。 */
    private static List<DailyUsage> queryDaily(EntityManager em, long since) {
        Map<LocalDate, long[]> buckets = new TreeMap<>();
        for (Object[] row : PlatformDb.rows(em, """
                SELECT created_at, input_tokens, cached_tokens, output_tokens, total_tokens
                FROM token_usage WHERE created_at >= ?
                """, since)) {
            long createdAt = ((Number) row[0]).longValue();
            LocalDate day = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).toLocalDate();
            long[] acc = buckets.computeIfAbsent(day, ignored -> new long[5]);
            acc[0] += 1;
            acc[1] += ((Number) row[1]).longValue();
            acc[2] += ((Number) row[2]).longValue();
            acc[3] += ((Number) row[3]).longValue();
            acc[4] += ((Number) row[4]).longValue();
        }
        return buckets.entrySet().stream()
                .map(entry -> new DailyUsage(entry.getKey().toString(), entry.getValue()[0],
                        entry.getValue()[1], entry.getValue()[2],
                        entry.getValue()[3], entry.getValue()[4]))
                .toList();
    }

    private static List<ModelUsageSummary> queryByModel(EntityManager em, long since) {
        return PlatformDb.rows(em, """
                SELECT COALESCE(model_name, 'unknown') model_name,
                       COUNT(*) calls,
                       COALESCE(SUM(input_tokens), 0) input_tokens,
                       COALESCE(SUM(cached_tokens), 0) cached_tokens,
                       COALESCE(SUM(output_tokens), 0) output_tokens,
                       COALESCE(SUM(total_tokens), 0) total_tokens
                FROM token_usage WHERE created_at >= ?
                GROUP BY model_name ORDER BY total_tokens DESC
                """, since).stream()
                .map(row -> new ModelUsageSummary((String) row[0],
                        ((Number) row[1]).longValue(), ((Number) row[2]).longValue(),
                        ((Number) row[3]).longValue(), ((Number) row[4]).longValue(),
                        ((Number) row[5]).longValue()))
                .toList();
    }

    private static List<ProjectUsageSummary> queryByProject(EntityManager em, long since) {
        return PlatformDb.rows(em, """
                SELECT COALESCE(project_id, 'unknown') project_id,
                       COUNT(*) calls,
                       COALESCE(SUM(input_tokens), 0) input_tokens,
                       COALESCE(SUM(cached_tokens), 0) cached_tokens,
                       COALESCE(SUM(output_tokens), 0) output_tokens,
                       COALESCE(SUM(total_tokens), 0) total_tokens
                FROM token_usage WHERE created_at >= ?
                GROUP BY project_id ORDER BY total_tokens DESC
                """, since).stream()
                .map(row -> new ProjectUsageSummary((String) row[0],
                        ((Number) row[1]).longValue(), ((Number) row[2]).longValue(),
                        ((Number) row[3]).longValue(), ((Number) row[4]).longValue(),
                        ((Number) row[5]).longValue()))
                .toList();
    }

    private static UsageTotals totals(Object[] row) {
        return new UsageTotals(((Number) row[0]).longValue(), ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(), ((Number) row[3]).longValue(),
                ((Number) row[4]).longValue(), ((Number) row[5]).longValue());
    }
}
