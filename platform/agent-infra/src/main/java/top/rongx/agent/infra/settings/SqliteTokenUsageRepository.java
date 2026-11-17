package top.rongx.agent.infra.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.settings.TokenUsageRepository;
import top.rongx.agent.infra.sqlite.SqliteConnections;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;

/**
 * token 用量明细（SQLite，独立库 {@code _platform/token_usage.db}）。
 *
 * <p>明细只追加不覆盖：按天 / 模型 / 项目的聚合在查询时完成，
 * 删除会话时能连同它的用量一起物理清掉，不会留下对不上的汇总数字。
 */
@Repository
public class SqliteTokenUsageRepository implements TokenUsageRepository {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS token_usage (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id    TEXT NOT NULL,
                project_id    TEXT,
                user_id       TEXT,
                connection_id TEXT,
                model_name    TEXT,
                input_tokens  INTEGER NOT NULL DEFAULT 0,
                cached_tokens INTEGER NOT NULL DEFAULT 0,
                output_tokens INTEGER NOT NULL DEFAULT 0,
                total_tokens  INTEGER NOT NULL DEFAULT 0,
                created_at    INTEGER NOT NULL
            )
            """;

    private static final String INDEX_SESSION = """
            CREATE INDEX IF NOT EXISTS idx_token_usage_session
            ON token_usage(session_id, created_at DESC)
            """;

    private static final String INDEX_CREATED = """
            CREATE INDEX IF NOT EXISTS idx_token_usage_created
            ON token_usage(created_at)
            """;

    private final Path dbFile;

    public SqliteTokenUsageRepository(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("token_usage.db");
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "token 用量库目录创建失败：" + e.getMessage(), e);
        }
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = open()) {
            for (String ddl : List.of(SCHEMA, INDEX_SESSION, INDEX_CREATED)) {
                try (PreparedStatement st = conn.prepareStatement(ddl)) {
                    st.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "token_usage 表初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return SqliteConnections.get(dbFile);
    }

    @Override
    public void record(ModelUsage usage) {
        if (usage == null || usage.sessionId() == null || usage.sessionId().isBlank()) {
            return;
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO token_usage
                         (session_id, project_id, user_id, connection_id, model_name,
                          input_tokens, cached_tokens, output_tokens, total_tokens, created_at)
                     VALUES (?,?,?,?,?,?,?,?,?,?)
                     """)) {
            st.setString(1, usage.sessionId());
            st.setString(2, usage.projectId());
            st.setString(3, usage.userId());
            st.setString(4, usage.connectionId());
            st.setString(5, usage.modelName());
            st.setInt(6, Math.max(0, usage.inputTokens()));
            st.setInt(7, Math.max(0, usage.cachedTokens()));
            st.setInt(8, Math.max(0, usage.outputTokens()));
            st.setLong(9, usage.totalTokens());
            st.setLong(10, usage.createdAt() > 0 ? usage.createdAt() : System.currentTimeMillis());
            st.executeUpdate();
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "token 用量写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<ModelUsage> findBySession(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT session_id, project_id, user_id, connection_id, model_name,
                            input_tokens, cached_tokens, output_tokens, created_at
                     FROM token_usage WHERE session_id = ?
                     ORDER BY created_at DESC LIMIT ?
                     """)) {
            st.setString(1, sessionId);
            st.setInt(2, Math.max(1, limit));
            try (ResultSet rs = st.executeQuery()) {
                List<ModelUsage> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ModelUsage(
                            rs.getString("session_id"), rs.getString("project_id"),
                            rs.getString("user_id"), rs.getString("connection_id"),
                            rs.getString("model_name"), rs.getInt("input_tokens"),
                            rs.getInt("cached_tokens"), rs.getInt("output_tokens"),
                            rs.getLong("created_at")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "token 用量查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public UsageTotals sessionTotals(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UsageTotals.empty();
        }
        String sql = """
                SELECT COUNT(*) calls,
                       COALESCE(SUM(input_tokens), 0) input_tokens,
                       COALESCE(SUM(cached_tokens), 0) cached_tokens,
                       COALESCE(SUM(output_tokens), 0) output_tokens,
                       COALESCE(SUM(total_tokens), 0) total_tokens,
                       COALESCE(MAX(created_at), 0) last_at
                FROM token_usage WHERE session_id = ?
                """;
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, sessionId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? totals(rs) : UsageTotals.empty();
            }
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "会话 token 汇总失败：" + e.getMessage(), e);
        }
    }

    @Override
    public UsageSummary summary(int days) {
        int window = Math.max(1, Math.min(days, 365));
        long since = LocalDate.now().minusDays(window - 1L)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        try (Connection conn = open()) {
            UsageTotals totals = queryTotals(conn, since);
            return new UsageSummary(window, totals,
                    queryDaily(conn, since),
                    queryByModel(conn, since),
                    queryByProject(conn, since));
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "token 用量汇总失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM token_usage WHERE session_id = ?")) {
            st.setString(1, sessionId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "token 用量删除失败：" + e.getMessage(), e);
        }
    }

    private UsageTotals queryTotals(Connection conn, long since) throws SQLException {
        String sql = """
                SELECT COUNT(*) calls,
                       COALESCE(SUM(input_tokens), 0) input_tokens,
                       COALESCE(SUM(cached_tokens), 0) cached_tokens,
                       COALESCE(SUM(output_tokens), 0) output_tokens,
                       COALESCE(SUM(total_tokens), 0) total_tokens,
                       COALESCE(MAX(created_at), 0) last_at
                FROM token_usage WHERE created_at >= ?
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setLong(1, since);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? totals(rs) : UsageTotals.empty();
            }
        }
    }

    private List<DailyUsage> queryDaily(Connection conn, long since) throws SQLException {
        String sql = """
                SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch', 'localtime') day,
                       COUNT(*) calls,
                       COALESCE(SUM(input_tokens), 0) input_tokens,
                       COALESCE(SUM(cached_tokens), 0) cached_tokens,
                       COALESCE(SUM(output_tokens), 0) output_tokens,
                       COALESCE(SUM(total_tokens), 0) total_tokens
                FROM token_usage WHERE created_at >= ?
                GROUP BY day ORDER BY day
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setLong(1, since);
            try (ResultSet rs = st.executeQuery()) {
                List<DailyUsage> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new DailyUsage(rs.getString("day"), rs.getLong("calls"),
                            rs.getLong("input_tokens"), rs.getLong("cached_tokens"),
                            rs.getLong("output_tokens"), rs.getLong("total_tokens")));
                }
                return out;
            }
        }
    }

    private List<ModelUsageSummary> queryByModel(Connection conn, long since) throws SQLException {
        String sql = """
                SELECT COALESCE(model_name, 'unknown') model_name,
                       COUNT(*) calls,
                       COALESCE(SUM(input_tokens), 0) input_tokens,
                       COALESCE(SUM(cached_tokens), 0) cached_tokens,
                       COALESCE(SUM(output_tokens), 0) output_tokens,
                       COALESCE(SUM(total_tokens), 0) total_tokens
                FROM token_usage WHERE created_at >= ?
                GROUP BY model_name ORDER BY total_tokens DESC
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setLong(1, since);
            try (ResultSet rs = st.executeQuery()) {
                List<ModelUsageSummary> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ModelUsageSummary(rs.getString("model_name"),
                            rs.getLong("calls"), rs.getLong("input_tokens"),
                            rs.getLong("cached_tokens"), rs.getLong("output_tokens"),
                            rs.getLong("total_tokens")));
                }
                return out;
            }
        }
    }

    private List<ProjectUsageSummary> queryByProject(Connection conn, long since)
            throws SQLException {
        String sql = """
                SELECT COALESCE(project_id, 'unknown') project_id,
                       COUNT(*) calls,
                       COALESCE(SUM(input_tokens), 0) input_tokens,
                       COALESCE(SUM(cached_tokens), 0) cached_tokens,
                       COALESCE(SUM(output_tokens), 0) output_tokens,
                       COALESCE(SUM(total_tokens), 0) total_tokens
                FROM token_usage WHERE created_at >= ?
                GROUP BY project_id ORDER BY total_tokens DESC
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setLong(1, since);
            try (ResultSet rs = st.executeQuery()) {
                List<ProjectUsageSummary> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ProjectUsageSummary(rs.getString("project_id"),
                            rs.getLong("calls"), rs.getLong("input_tokens"),
                            rs.getLong("cached_tokens"), rs.getLong("output_tokens"),
                            rs.getLong("total_tokens")));
                }
                return out;
            }
        }
    }

    private static UsageTotals totals(ResultSet rs) throws SQLException {
        return new UsageTotals(rs.getLong("calls"), rs.getLong("input_tokens"),
                rs.getLong("cached_tokens"), rs.getLong("output_tokens"),
                rs.getLong("total_tokens"), rs.getLong("last_at"));
    }
}
