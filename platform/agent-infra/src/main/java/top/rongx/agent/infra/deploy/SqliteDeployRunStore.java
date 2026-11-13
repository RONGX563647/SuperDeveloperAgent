package top.rongx.agent.infra.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.deploy.DeployRun;
import top.rongx.agent.domain.deploy.DeployRunStatus;
import top.rongx.agent.domain.deploy.DeployRunStore;

/**
 * 部署运行仓储（SQLite，与项目/环境共用 projects.db）。
 *
 * <p>互斥靠单条 {@code INSERT ... SELECT ... WHERE NOT EXISTS} 完成，不依赖 JVM 锁，
 * 多实例部署也不会出现同一环境并发发布。
 */
@Repository
public class SqliteDeployRunStore implements DeployRunStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteDeployRunStore.class);

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS deploy_runs (
                id           TEXT PRIMARY KEY,
                run_id       TEXT,
                project_id   TEXT NOT NULL,
                env          TEXT NOT NULL,
                sha          TEXT NOT NULL,
                pipeline_id  TEXT,
                status       TEXT NOT NULL,
                reason_code  TEXT,
                message      TEXT,
                attempt      INTEGER NOT NULL DEFAULT 1,
                started_at   INTEGER NOT NULL,
                finished_at  INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_deploy_runs_env
                ON deploy_runs(project_id, env, started_at);
            CREATE INDEX IF NOT EXISTS idx_deploy_runs_status
                ON deploy_runs(project_id, env, status);
            """;

    private final Path dbFile;

    public SqliteDeployRunStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("projects.db");
        createParent();
        initSchema();
    }

    private void createParent() {
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("无法创建部署记录目录：" + e.getMessage(), e);
        }
    }

    private void initSchema() {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            for (String ddl : SCHEMA.split(";")) {
                if (!ddl.isBlank()) {
                    st.executeUpdate(ddl);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("部署运行表初始化失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<DeployRun> tryStart(String projectId, String env, String sha,
                                        String pipelineId, int attempt) {
        String id = "dr-" + UUID.randomUUID().toString().substring(0, 12);
        long now = System.currentTimeMillis();
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try (PreparedStatement st = conn.prepareStatement("""
                    INSERT INTO deploy_runs
                        (id, run_id, project_id, env, sha, pipeline_id, status,
                         reason_code, message, attempt, started_at, finished_at)
                    SELECT ?, '', ?, ?, ?, ?, 'PENDING', '', '',
                           COALESCE((SELECT MAX(attempt) FROM deploy_runs
                                      WHERE project_id=? AND env=? AND sha=?), 0) + 1,
                           ?, 0
                    WHERE NOT EXISTS (
                        SELECT 1 FROM deploy_runs
                        WHERE project_id=? AND env=? AND status IN ('PENDING','RUNNING'))
                    """)) {
                st.setString(1, id);
                st.setString(2, projectId);
                st.setString(3, env);
                st.setString(4, sha);
                st.setString(5, pipelineId);
                st.setString(6, projectId);
                st.setString(7, env);
                st.setString(8, sha);
                st.setLong(9, now);
                st.setString(10, projectId);
                st.setString(11, env);
                int inserted = st.executeUpdate();
                conn.commit();
                if (inserted == 0) {
                    return Optional.empty();
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
            int savedAttempt = attempt;
            long savedAt = now;
            try (PreparedStatement q = conn.prepareStatement(
                    "SELECT attempt, started_at FROM deploy_runs WHERE id=?")) {
                q.setString(1, id);
                try (ResultSet rs = q.executeQuery()) {
                    if (rs.next()) {
                        savedAttempt = rs.getInt(1);
                        savedAt = rs.getLong(2);
                    }
                }
            }
            return Optional.of(new DeployRun(id, "", projectId, env, sha, pipelineId,
                    DeployRunStatus.PENDING, "", "", savedAttempt, savedAt, 0));
        } catch (SQLException e) {
            throw new IllegalStateException("部署互斥抢占失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void bindRunId(String id, String runId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE deploy_runs SET run_id=?, status='RUNNING' WHERE id=?
                     """)) {
            st.setString(1, runId);
            st.setString(2, id);
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("回填 runId 失败：{}", e.getMessage());
        }
    }

    @Override
    public void finish(String id, DeployRunStatus status, String reasonCode, String message) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE deploy_runs
                     SET status=?, reason_code=?, message=?, finished_at=?
                     WHERE id=?
                     """)) {
            st.setString(1, status.name());
            st.setString(2, reasonCode == null ? "" : reasonCode);
            st.setString(3, message == null ? "" : message);
            st.setLong(4, System.currentTimeMillis());
            st.setString(5, id);
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("部署状态写入失败：{}", e.getMessage());
        }
    }

    @Override
    public Optional<DeployRun> active(String projectId, String env) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT * FROM deploy_runs
                     WHERE project_id=? AND env=? AND status IN ('PENDING','RUNNING')
                     ORDER BY started_at DESC LIMIT 1
                     """)) {
            st.setString(1, projectId);
            st.setString(2, env);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.warn("查询进行中部署失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public int countSince(String projectId, String env, long sinceMillis) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT COUNT(*) FROM deploy_runs
                     WHERE project_id=? AND env=? AND started_at>=?
                     """)) {
            st.setString(1, projectId);
            st.setString(2, env);
            st.setLong(3, sinceMillis);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("统计部署预算失败：{}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int consecutiveFailures(String projectId, String env) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT status FROM deploy_runs
                     WHERE project_id=? AND env=? AND status NOT IN ('PENDING','RUNNING')
                     ORDER BY started_at DESC LIMIT 30
                     """)) {
            st.setString(1, projectId);
            st.setString(2, env);
            try (ResultSet rs = st.executeQuery()) {
                int failures = 0;
                while (rs.next()) {
                    String status = rs.getString(1);
                    if ("FAILED".equals(status) || "ROLLED_BACK".equals(status)) {
                        failures++;
                    } else {
                        break;
                    }
                }
                return failures;
            }
        } catch (SQLException e) {
            log.warn("统计连续失败失败：{}", e.getMessage());
            return 0;
        }
    }

    @Override
    public List<DeployRun> recent(String projectId, String env, int limit) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT * FROM deploy_runs
                     WHERE project_id=? AND env=?
                     ORDER BY started_at DESC LIMIT ?
                     """)) {
            st.setString(1, projectId);
            st.setString(2, env);
            st.setInt(3, Math.max(1, limit));
            try (ResultSet rs = st.executeQuery()) {
                List<DeployRun> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            log.warn("查询部署记录失败：{}", e.getMessage());
            return List.of();
        }
    }

    private DeployRun map(ResultSet rs) throws SQLException {
        return new DeployRun(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("project_id"),
                rs.getString("env"),
                rs.getString("sha"),
                rs.getString("pipeline_id"),
                DeployRunStatus.valueOf(rs.getString("status")),
                rs.getString("reason_code"),
                rs.getString("message"),
                rs.getInt("attempt"),
                rs.getLong("started_at"),
                rs.getLong("finished_at"));
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }
}
