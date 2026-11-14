package top.rongx.agent.infra.run;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.run.RunKind;
import top.rongx.agent.domain.run.RunLedgerRepository;
import top.rongx.agent.domain.run.RunRecord;
import top.rongx.agent.domain.run.RunState;
import top.rongx.agent.infra.sqlite.SqliteConnections;

/**
 * 统一运行账本的 SQLite 实现（{@code _platform/run_ledger.db}）。
 *
 * <p>表结构刻意保持扁平：只存跨引擎可比的生命周期字段，不复制各编排的细节状态。
 * 重启恢复只处理"长时间未更新的 RUNNING"，避免多实例部署时误杀其他实例的运行。
 */
@Repository
public class SqliteRunLedgerRepository implements RunLedgerRepository {

    private static final String COLUMNS = """
            id, kind, project_id, subject_id, owner_user_id, state, summary, error,
            created_at, updated_at
            """;

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS run_ledger (
                id            TEXT PRIMARY KEY,
                kind          TEXT NOT NULL,
                project_id    TEXT NOT NULL,
                subject_id    TEXT NOT NULL DEFAULT '',
                owner_user_id TEXT NOT NULL DEFAULT '',
                state         TEXT NOT NULL,
                summary       TEXT NOT NULL DEFAULT '',
                error         TEXT NOT NULL DEFAULT '',
                created_at    INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL
            )
            """;

    private static final String INDEX_PROJECT = """
            CREATE INDEX IF NOT EXISTS idx_run_ledger_project
            ON run_ledger(project_id, updated_at DESC)
            """;

    private static final String INDEX_STATE = """
            CREATE INDEX IF NOT EXISTS idx_run_ledger_state
            ON run_ledger(state, updated_at)
            """;

    private final Path dbFile;

    public SqliteRunLedgerRepository(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("run_ledger.db");
        try {
            java.nio.file.Files.createDirectories(dbFile.getParent());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("运行账本目录创建失败：" + e.getMessage(), e);
        }
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(SCHEMA);
            st.execute(INDEX_PROJECT);
            st.execute(INDEX_STATE);
        } catch (SQLException e) {
            throw new IllegalStateException("run_ledger 表初始化失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void save(RunRecord record) {
        String sql = """
                INSERT INTO run_ledger (%s) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    state = excluded.state,
                    summary = excluded.summary,
                    error = excluded.error,
                    updated_at = excluded.updated_at
                """.formatted(COLUMNS);
        try (Connection conn = connect(); PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, record.id());
            st.setString(2, record.kind().name());
            st.setString(3, record.projectId());
            st.setString(4, record.subjectId() == null ? "" : record.subjectId());
            st.setString(5, record.ownerUserId() == null ? "" : record.ownerUserId());
            st.setString(6, record.state().name());
            st.setString(7, record.summary() == null ? "" : record.summary());
            st.setString(8, record.error() == null ? "" : record.error());
            st.setLong(9, record.createdAt());
            st.setLong(10, record.updatedAt());
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("运行账本写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<RunRecord> find(String id) {
        String sql = "SELECT " + COLUMNS + " FROM run_ledger WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("运行账本读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<RunRecord> byProject(String projectId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM run_ledger WHERE project_id = ?"
                + " ORDER BY updated_at DESC LIMIT ?";
        return query(sql, st -> {
            st.setString(1, projectId);
            st.setInt(2, Math.max(1, Math.min(limit, 200)));
        });
    }

    @Override
    public List<RunRecord> active() {
        String sql = "SELECT " + COLUMNS + " FROM run_ledger WHERE state IN ('QUEUED', 'RUNNING')"
                + " ORDER BY updated_at DESC";
        return query(sql, st -> {
        });
    }

    @Override
    public int markStaleRunning(long staleBefore, String reason) {
        String sql = """
                UPDATE run_ledger SET state = 'FAILED', error = ?, summary = ?, updated_at = ?
                WHERE state = 'RUNNING' AND updated_at < ?
                """;
        try (Connection conn = connect(); PreparedStatement st = conn.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            st.setString(1, reason);
            st.setString(2, "运行中断：" + reason);
            st.setLong(3, now);
            st.setLong(4, staleBefore);
            return st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("运行账本恢复失败：" + e.getMessage(), e);
        }
    }

    private List<RunRecord> query(String sql, StatementBinder binder) {
        try (Connection conn = connect(); PreparedStatement st = conn.prepareStatement(sql)) {
            binder.bind(st);
            List<RunRecord> result = new ArrayList<>();
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("运行账本查询失败：" + e.getMessage(), e);
        }
    }

    private static RunRecord map(ResultSet rs) throws SQLException {
        return new RunRecord(
                rs.getString("id"),
                RunKind.valueOf(rs.getString("kind")),
                rs.getString("project_id"),
                rs.getString("subject_id"),
                rs.getString("owner_user_id"),
                RunState.valueOf(rs.getString("state")),
                rs.getString("summary"),
                rs.getString("error"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private Connection connect() throws SQLException {
        return SqliteConnections.get(dbFile);
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
