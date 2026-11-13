package top.rongx.agent.infra.bugfix;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.bugfix.BugFixEvent;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixPolicyStore;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixRunStore;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStatus;

/**
 * Bug 自动修复的 SQLite 存储（运行 + 时间线 + 策略）。
 *
 * <p>与 Issue 存储分开放在 {@code automation.db}：自动化运行写入频繁（阶段推进 + 事件追加），
 * 独立库避免与项目/任务读写争用同一个写锁。每次 save 整体覆盖运行快照，保证重启后阶段、
 * 证据与分支信息一致。
 */
@Repository
public class SqliteBugFixStore implements BugFixRunStore, BugFixPolicyStore {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS bugfix_runs (
                id           TEXT PRIMARY KEY,
                issue_id     TEXT NOT NULL,
                user_id      TEXT NOT NULL,
                project_id   TEXT NOT NULL,
                provider     TEXT,
                repo         TEXT,
                issue_number TEXT,
                issue_title  TEXT,
                issue_body   TEXT,
                branch       TEXT,
                stage        TEXT NOT NULL,
                status       TEXT NOT NULL,
                message      TEXT,
                analysis     TEXT,
                base_sha     TEXT,
                fix_sha      TEXT,
                env          TEXT,
                pr_url       TEXT,
                evidence     TEXT,
                attempt      INTEGER NOT NULL DEFAULT 0,
                created_at   INTEGER NOT NULL,
                updated_at   INTEGER NOT NULL,
                finished_at  INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_bugfix_runs_issue ON bugfix_runs(issue_id, status);
            CREATE INDEX IF NOT EXISTS idx_bugfix_runs_user ON bugfix_runs(user_id, created_at);
            CREATE TABLE IF NOT EXISTS bugfix_events (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id     TEXT NOT NULL,
                stage      TEXT NOT NULL,
                level      TEXT NOT NULL,
                message    TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_bugfix_events_run ON bugfix_events(run_id, id);
            CREATE TABLE IF NOT EXISTS bugfix_policy (
                id             INTEGER PRIMARY KEY CHECK (id = 1),
                enabled        INTEGER NOT NULL DEFAULT 0,
                auto_deploy    INTEGER NOT NULL DEFAULT 1,
                auto_create_pr INTEGER NOT NULL DEFAULT 1,
                test_env       TEXT NOT NULL DEFAULT 'test',
                binding_id     TEXT NOT NULL DEFAULT '',
                max_attempts   INTEGER NOT NULL DEFAULT 2,
                updated_at     INTEGER NOT NULL DEFAULT 0
            );
            """;

    private static final String RUN_COLUMNS = """
            id, issue_id, user_id, project_id, provider, repo, issue_number, issue_title,
            issue_body, branch, stage, status, message, analysis, base_sha, fix_sha, env,
            pr_url, evidence, attempt, created_at, updated_at, finished_at
            """;

    private static final String POLICY_COLUMNS = """
            enabled, auto_deploy, auto_create_pr, test_env, binding_id, max_attempts, updated_at
            """;

    /** 每个运行保留的时间线事件上限，避免长跑任务把库撑大。 */
    private static final int MAX_EVENTS_PER_RUN = 300;

    private final Path dbFile;

    public SqliteBugFixStore(@Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("automation.db");
        initSchema();
    }

    private void initSchema() {
        try {
            Files.createDirectories(dbFile.getParent());
            try (Connection conn = open(); Statement st = conn.createStatement()) {
                // SQLite JDBC 一次 prepare 只能执行第一条语句，必须拆开逐条执行
                for (String sql : SCHEMA.split(";")) {
                    String statement = sql.trim();
                    if (!statement.isEmpty()) {
                        st.executeUpdate(statement);
                    }
                }
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("automation.db 初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    // ---------------------------------------------------------------- 运行

    @Override
    public BugFixRun save(BugFixRun run) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT OR REPLACE INTO bugfix_runs
                         (id, issue_id, user_id, project_id, provider, repo, issue_number,
                          issue_title, issue_body, branch, stage, status, message, analysis,
                          base_sha, fix_sha, env, pr_url, evidence, attempt, created_at,
                          updated_at, finished_at)
                     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
            st.setString(1, run.id());
            st.setString(2, run.issueId());
            st.setString(3, run.userId());
            st.setString(4, run.projectId());
            st.setString(5, run.provider());
            st.setString(6, run.repo());
            st.setString(7, run.issueNumber());
            st.setString(8, run.issueTitle());
            st.setString(9, run.issueBody());
            st.setString(10, run.branch());
            st.setString(11, run.stage().name());
            st.setString(12, run.status().name());
            st.setString(13, run.message());
            st.setString(14, run.analysis());
            st.setString(15, run.baseSha());
            st.setString(16, run.fixSha());
            st.setString(17, run.env());
            st.setString(18, run.prUrl());
            st.setString(19, run.evidence());
            st.setInt(20, run.attempt());
            st.setLong(21, run.createdAt());
            st.setLong(22, run.updatedAt());
            st.setLong(23, run.finishedAt());
            st.executeUpdate();
            return run;
        } catch (SQLException e) {
            throw new IllegalStateException("自动修复运行保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<BugFixRun> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + RUN_COLUMNS + " FROM bugfix_runs WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("自动修复运行查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<BugFixRun> list(String userId, String issueId, int limit) {
        List<BugFixRun> runs = new ArrayList<>();
        boolean byIssue = issueId != null && !issueId.isBlank();
        String sql = "SELECT " + RUN_COLUMNS + " FROM bugfix_runs WHERE user_id = ?"
                + (byIssue ? " AND issue_id = ?" : "")
                + " ORDER BY created_at DESC LIMIT ?";
        try (Connection conn = open(); PreparedStatement st = conn.prepareStatement(sql)) {
            int index = 1;
            st.setString(index++, userId);
            if (byIssue) {
                st.setString(index++, issueId);
            }
            st.setInt(index, limit <= 0 ? 50 : Math.min(limit, 200));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    runs.add(read(rs));
                }
            }
            return runs;
        } catch (SQLException e) {
            throw new IllegalStateException("自动修复运行列表查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<BugFixRun> activeByIssue(String issueId) {
        if (issueId == null || issueId.isBlank()) {
            return Optional.empty();
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT %s FROM bugfix_runs
                     WHERE issue_id = ? AND status IN ('QUEUED','RUNNING')
                     ORDER BY created_at DESC LIMIT 1
                     """.formatted(RUN_COLUMNS))) {
            st.setString(1, issueId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("活动运行查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void appendEvent(BugFixEvent event) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO bugfix_events (run_id, stage, level, message, created_at)
                     VALUES (?,?,?,?,?)
                     """)) {
            st.setString(1, event.runId());
            st.setString(2, event.stage().name());
            st.setString(3, event.level());
            st.setString(4, event.message());
            st.setLong(5, event.createdAt());
            st.executeUpdate();
            pruneEvents(conn, event.runId());
        } catch (SQLException e) {
            throw new IllegalStateException("自动修复事件写入失败：" + e.getMessage(), e);
        }
    }

    private void pruneEvents(Connection conn, String runId) {
        try (PreparedStatement st = conn.prepareStatement("""
                DELETE FROM bugfix_events WHERE run_id = ? AND id NOT IN
                    (SELECT id FROM bugfix_events WHERE run_id = ? ORDER BY id DESC LIMIT ?)
                """)) {
            st.setString(1, runId);
            st.setString(2, runId);
            st.setInt(3, MAX_EVENTS_PER_RUN);
            st.executeUpdate();
        } catch (SQLException ignored) {
            // 裁剪失败不影响主流程
        }
    }

    @Override
    public List<BugFixEvent> events(String runId) {
        List<BugFixEvent> events = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT id, run_id, stage, level, message, created_at
                     FROM bugfix_events WHERE run_id = ? ORDER BY id
                     """)) {
            st.setString(1, runId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    events.add(new BugFixEvent(
                            String.valueOf(rs.getLong("id")),
                            rs.getString("run_id"),
                            stage(rs.getString("stage")),
                            rs.getString("level"),
                            rs.getString("message"),
                            rs.getLong("created_at")));
                }
            }
            return events;
        } catch (SQLException e) {
            throw new IllegalStateException("自动修复事件查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public int markInterrupted() {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE bugfix_runs
                     SET status = 'BLOCKED', message = '服务重启中断，可点击重试继续',
                         updated_at = ?
                     WHERE status = 'RUNNING'
                     """)) {
            st.setLong(1, System.currentTimeMillis());
            return st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("运行状态恢复失败：" + e.getMessage(), e);
        }
    }

    private static BugFixRun read(ResultSet rs) throws SQLException {
        return new BugFixRun(
                rs.getString("id"),
                rs.getString("issue_id"),
                rs.getString("user_id"),
                rs.getString("project_id"),
                rs.getString("provider"),
                rs.getString("repo"),
                rs.getString("issue_number"),
                rs.getString("issue_title"),
                rs.getString("issue_body"),
                rs.getString("branch"),
                stage(rs.getString("stage")),
                status(rs.getString("status")),
                rs.getString("message"),
                rs.getString("analysis"),
                rs.getString("base_sha"),
                rs.getString("fix_sha"),
                rs.getString("env"),
                rs.getString("pr_url"),
                rs.getString("evidence"),
                rs.getInt("attempt"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getLong("finished_at"));
    }

    private static BugFixStage stage(String value) {
        try {
            return BugFixStage.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return BugFixStage.ANALYZE;
        }
    }

    private static BugFixStatus status(String value) {
        try {
            return BugFixStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return BugFixStatus.BLOCKED;
        }
    }

    // ---------------------------------------------------------------- 策略

    @Override
    public BugFixPolicy load() {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + POLICY_COLUMNS + " FROM bugfix_policy WHERE id = 1");
             ResultSet rs = st.executeQuery()) {
            if (!rs.next()) {
                return BugFixPolicy.defaults();
            }
            return new BugFixPolicy(
                    rs.getInt("enabled") != 0,
                    rs.getInt("auto_deploy") != 0,
                    rs.getInt("auto_create_pr") != 0,
                    rs.getString("test_env"),
                    rs.getString("binding_id"),
                    rs.getInt("max_attempts"),
                    rs.getLong("updated_at"));
        } catch (SQLException e) {
            throw new IllegalStateException("自动修复策略读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public BugFixPolicy save(BugFixPolicy policy) {
        BugFixPolicy normalized = policy.normalized()
                .withUpdatedAt(System.currentTimeMillis());
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT OR REPLACE INTO bugfix_policy
                         (id, enabled, auto_deploy, auto_create_pr, test_env,
                          binding_id, max_attempts, updated_at)
                     VALUES (1,?,?,?,?,?,?,?)
                     """)) {
            st.setInt(1, normalized.enabled() ? 1 : 0);
            st.setInt(2, normalized.autoDeploy() ? 1 : 0);
            st.setInt(3, normalized.autoCreatePr() ? 1 : 0);
            st.setString(4, normalized.testEnv());
            st.setString(5, normalized.bindingId());
            st.setInt(6, normalized.maxAttempts());
            st.setLong(7, normalized.updatedAt());
            st.executeUpdate();
            return normalized;
        } catch (SQLException e) {
            throw new IllegalStateException("自动修复策略保存失败：" + e.getMessage(), e);
        }
    }
}
