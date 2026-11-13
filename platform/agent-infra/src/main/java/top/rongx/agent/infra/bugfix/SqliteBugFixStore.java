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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.bugfix.BugFixEvent;
import top.rongx.agent.domain.bugfix.BugFixNotification;
import top.rongx.agent.domain.bugfix.BugFixNotificationStore;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixPolicyStore;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixRunStore;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageRun;
import top.rongx.agent.domain.bugfix.BugFixStatus;

/**
 * Bug 自动修复的 SQLite 存储（运行 + 时间线 + 阶段记录 + 租约 + 通知 + 策略）。
 *
 * <p>可靠性要点：
 * <ul>
 *   <li>WAL + {@code busy_timeout}：阶段推进与 webhook 并发写时不再直接抛 database is locked；</li>
 *   <li>运行租约 / 环境租约：多实例或重复调度时只有一方能推进，避免重复部署同一测试环境；</li>
 *   <li>阶段记录：逐阶段保存尝试、耗时与证据，验证失败回到修复不会覆盖上一轮证据；</li>
 *   <li>通知发件箱：Issue 评论先落库再异步投递，评论接口抖动不会拖垮状态机；</li>
 *   <li>项目级策略：每个项目可独立配置测试环境与环境绑定，未配置时回退平台默认。</li>
 * </ul>
 */
@Repository
public class SqliteBugFixStore implements BugFixRunStore, BugFixPolicyStore, BugFixNotificationStore {

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
                analysis_json TEXT,
                fix_summary  TEXT,
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
            CREATE INDEX IF NOT EXISTS idx_bugfix_runs_status ON bugfix_runs(status, updated_at);
            CREATE TABLE IF NOT EXISTS bugfix_events (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id     TEXT NOT NULL,
                stage      TEXT NOT NULL,
                level      TEXT NOT NULL,
                message    TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_bugfix_events_run ON bugfix_events(run_id, id);
            CREATE TABLE IF NOT EXISTS bugfix_stage_runs (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id      TEXT NOT NULL,
                stage       TEXT NOT NULL,
                attempt     INTEGER NOT NULL DEFAULT 1,
                status      TEXT NOT NULL,
                message     TEXT,
                artifact    TEXT,
                started_at  INTEGER NOT NULL,
                ended_at    INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_bugfix_stage_runs_run ON bugfix_stage_runs(run_id, id);
            CREATE TABLE IF NOT EXISTS bugfix_leases (
                run_id      TEXT PRIMARY KEY,
                owner       TEXT NOT NULL,
                lease_until INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS bugfix_env_leases (
                project_id  TEXT NOT NULL,
                env         TEXT NOT NULL,
                run_id      TEXT NOT NULL,
                lease_until INTEGER NOT NULL,
                PRIMARY KEY (project_id, env)
            );
            CREATE TABLE IF NOT EXISTS bugfix_notifications (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id         TEXT NOT NULL,
                message        TEXT NOT NULL,
                status         TEXT NOT NULL DEFAULT 'PENDING',
                attempts       INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL DEFAULT 0,
                last_error     TEXT,
                created_at     INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_bugfix_notifications_pending
                ON bugfix_notifications(status, next_attempt_at);
            CREATE TABLE IF NOT EXISTS bugfix_policy (
                id             INTEGER PRIMARY KEY CHECK (id = 1),
                enabled        INTEGER NOT NULL DEFAULT 0,
                auto_deploy    INTEGER NOT NULL DEFAULT 1,
                auto_create_pr INTEGER NOT NULL DEFAULT 1,
                test_env       TEXT NOT NULL DEFAULT 'test',
                binding_id     TEXT NOT NULL DEFAULT '',
                max_attempts   INTEGER NOT NULL DEFAULT 2,
                base_url       TEXT NOT NULL DEFAULT '',
                build_command  TEXT NOT NULL DEFAULT '',
                test_command   TEXT NOT NULL DEFAULT '',
                updated_at     INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS bugfix_project_policy (
                project_id     TEXT PRIMARY KEY,
                enabled        INTEGER NOT NULL DEFAULT 0,
                auto_deploy    INTEGER NOT NULL DEFAULT 1,
                auto_create_pr INTEGER NOT NULL DEFAULT 1,
                test_env       TEXT NOT NULL DEFAULT 'test',
                binding_id     TEXT NOT NULL DEFAULT '',
                max_attempts   INTEGER NOT NULL DEFAULT 2,
                base_url       TEXT NOT NULL DEFAULT '',
                build_command  TEXT NOT NULL DEFAULT '',
                test_command   TEXT NOT NULL DEFAULT '',
                updated_at     INTEGER NOT NULL DEFAULT 0
            );
            """;

    private static final String RUN_COLUMNS = """
            id, issue_id, user_id, project_id, provider, repo, issue_number, issue_title,
            issue_body, branch, stage, status, message, analysis, analysis_json, fix_summary,
            base_sha, fix_sha, env, pr_url, evidence, attempt, created_at, updated_at, finished_at
            """;

    private static final String POLICY_COLUMNS = """
            enabled, auto_deploy, auto_create_pr, test_env, binding_id, max_attempts,
            base_url, build_command, test_command, updated_at
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
                // 连接级 PRAGMA：WAL 只需设置一次，busy_timeout 每个连接都要
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                // SQLite JDBC 一次 prepare 只能执行第一条语句，必须拆开逐条执行
                for (String sql : SCHEMA.split(";")) {
                    String statement = sql.trim();
                    if (!statement.isEmpty()) {
                        st.executeUpdate(statement);
                    }
                }
            }
            // 兼容旧库：新增列逐个补齐（CREATE TABLE IF NOT EXISTS 不会加列）
            ensureColumn("bugfix_runs", "analysis_json", "TEXT");
            ensureColumn("bugfix_runs", "fix_summary", "TEXT");
            ensureColumn("bugfix_policy", "base_url", "TEXT NOT NULL DEFAULT ''");
            ensureColumn("bugfix_policy", "build_command", "TEXT NOT NULL DEFAULT ''");
            ensureColumn("bugfix_policy", "test_command", "TEXT NOT NULL DEFAULT ''");
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("automation.db 初始化失败：" + e.getMessage(), e);
        }
    }

    private void ensureColumn(String table, String column, String type) {
        Set<String> columns = new HashSet<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("PRAGMA table_info(" + table + ")");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取表结构失败：" + table, e);
        }
        if (columns.contains(column)) {
            return;
        }
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (SQLException e) {
            throw new IllegalStateException("自动升级表结构失败：" + table + "." + column, e);
        }
    }

    private Connection open() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return conn;
    }

    // ---------------------------------------------------------------- 运行

    @Override
    public BugFixRun save(BugFixRun run) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT OR REPLACE INTO bugfix_runs
                         (id, issue_id, user_id, project_id, provider, repo, issue_number,
                          issue_title, issue_body, branch, stage, status, message, analysis,
                          analysis_json, fix_summary, base_sha, fix_sha, env, pr_url, evidence,
                          attempt, created_at, updated_at, finished_at)
                     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
            st.setString(15, run.analysisJson());
            st.setString(16, run.fixSummary());
            st.setString(17, run.baseSha());
            st.setString(18, run.fixSha());
            st.setString(19, run.env());
            st.setString(20, run.prUrl());
            st.setString(21, run.evidence());
            st.setInt(22, run.attempt());
            st.setLong(23, run.createdAt());
            st.setLong(24, run.updatedAt());
            st.setLong(25, run.finishedAt());
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
        boolean byUser = userId != null && !userId.isBlank();
        boolean byIssue = issueId != null && !issueId.isBlank();
        String sql = "SELECT " + RUN_COLUMNS + " FROM bugfix_runs WHERE 1=1"
                + (byUser ? " AND user_id = ?" : "")
                + (byIssue ? " AND issue_id = ?" : "")
                + " ORDER BY created_at DESC LIMIT ?";
        try (Connection conn = open(); PreparedStatement st = conn.prepareStatement(sql)) {
            int index = 1;
            if (byUser) {
                st.setString(index++, userId);
            }
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
    public List<String> recoverInterrupted() {
        long now = System.currentTimeMillis();
        List<String> queued = new ArrayList<>();
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // 只清理过期租约，避免把其他实例仍在执行的任务抢回来
                st.executeUpdate("DELETE FROM bugfix_leases WHERE lease_until < " + now);
                st.executeUpdate("DELETE FROM bugfix_env_leases WHERE lease_until < " + now);
                st.executeUpdate("""
                        UPDATE bugfix_stage_runs SET status = 'FAILED',
                            message = '服务重启中断', ended_at = %d,
                            duration_ms = %d - started_at
                        WHERE status = 'RUNNING' AND run_id IN (
                            SELECT id FROM bugfix_runs WHERE status = 'RUNNING'
                            AND id NOT IN (SELECT run_id FROM bugfix_leases))
                        """.formatted(now, now));
                st.executeUpdate("""
                        UPDATE bugfix_runs SET status = 'QUEUED',
                            message = '服务重启，已重新排队', updated_at = %d
                        WHERE status = 'RUNNING'
                        AND id NOT IN (SELECT run_id FROM bugfix_leases)
                        """.formatted(now));
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id FROM bugfix_runs WHERE status = 'QUEUED' ORDER BY created_at")) {
                while (rs.next()) {
                    queued.add(rs.getString("id"));
                }
            }
            conn.commit();
            return queued;
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
                rs.getString("analysis_json"),
                rs.getString("fix_summary"),
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

    // ---------------------------------------------------------------- 租约

    @Override
    public boolean tryAcquireLease(String runId, String owner, long ttlMs) {
        long now = System.currentTimeMillis();
        long until = now + ttlMs;
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement clean = conn.prepareStatement(
                        "DELETE FROM bugfix_leases WHERE run_id = ? AND lease_until < ?")) {
                    clean.setString(1, runId);
                    clean.setLong(2, now);
                    clean.executeUpdate();
                }
                try (PreparedStatement insert = conn.prepareStatement("""
                        INSERT OR IGNORE INTO bugfix_leases (run_id, owner, lease_until)
                        VALUES (?,?,?)
                        """)) {
                    insert.setString(1, runId);
                    insert.setString(2, owner);
                    insert.setLong(3, until);
                    if (insert.executeUpdate() == 1) {
                        conn.commit();
                        return true;
                    }
                }
                try (PreparedStatement renew = conn.prepareStatement("""
                        UPDATE bugfix_leases SET lease_until = ?
                        WHERE run_id = ? AND owner = ?
                        """)) {
                    renew.setLong(1, until);
                    renew.setString(2, runId);
                    renew.setString(3, owner);
                    boolean ok = renew.executeUpdate() == 1;
                    conn.commit();
                    return ok;
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("运行租约获取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean renewLease(String runId, String owner, long ttlMs) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE bugfix_leases SET lease_until = ?
                     WHERE run_id = ? AND owner = ?
                     """)) {
            st.setLong(1, System.currentTimeMillis() + ttlMs);
            st.setString(2, runId);
            st.setString(3, owner);
            return st.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("运行租约续期失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void releaseLease(String runId, String owner) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM bugfix_leases WHERE run_id = ? AND owner = ?")) {
            st.setString(1, runId);
            st.setString(2, owner);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("运行租约释放失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean acquireEnvLease(String projectId, String env, String runId, long ttlMs) {
        String project = projectId == null || projectId.isBlank() ? "__unknown__" : projectId;
        String environment = env == null || env.isBlank() ? "test" : env;
        long now = System.currentTimeMillis();
        long until = now + ttlMs;
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement clean = conn.prepareStatement("""
                        DELETE FROM bugfix_env_leases
                        WHERE project_id = ? AND env = ? AND lease_until < ?
                        """)) {
                    clean.setString(1, project);
                    clean.setString(2, environment);
                    clean.setLong(3, now);
                    clean.executeUpdate();
                }
                try (PreparedStatement insert = conn.prepareStatement("""
                        INSERT OR IGNORE INTO bugfix_env_leases
                            (project_id, env, run_id, lease_until)
                        VALUES (?,?,?,?)
                        """)) {
                    insert.setString(1, project);
                    insert.setString(2, environment);
                    insert.setString(3, runId);
                    insert.setLong(4, until);
                    if (insert.executeUpdate() == 1) {
                        conn.commit();
                        return true;
                    }
                }
                try (PreparedStatement renew = conn.prepareStatement("""
                        UPDATE bugfix_env_leases SET lease_until = ?
                        WHERE project_id = ? AND env = ? AND run_id = ?
                        """)) {
                    renew.setLong(1, until);
                    renew.setString(2, project);
                    renew.setString(3, environment);
                    renew.setString(4, runId);
                    boolean ok = renew.executeUpdate() == 1;
                    conn.commit();
                    return ok;
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("环境租约获取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void releaseEnvLease(String projectId, String env, String runId) {
        String project = projectId == null || projectId.isBlank() ? "__unknown__" : projectId;
        String environment = env == null || env.isBlank() ? "test" : env;
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     DELETE FROM bugfix_env_leases
                     WHERE project_id = ? AND env = ? AND run_id = ?
                     """)) {
            st.setString(1, project);
            st.setString(2, environment);
            st.setString(3, runId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("环境租约释放失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void releaseEnvLeases(String runId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM bugfix_env_leases WHERE run_id = ?")) {
            st.setString(1, runId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("环境租约释放失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 阶段记录

    @Override
    public long startStage(String runId, BugFixStage stage, int attempt, long startedAt) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO bugfix_stage_runs
                         (run_id, stage, attempt, status, started_at)
                     VALUES (?,?,?,?,?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            st.setString(1, runId);
            st.setString(2, stage.name());
            st.setInt(3, attempt);
            st.setString(4, BugFixStageRun.RUNNING);
            st.setLong(5, startedAt);
            st.executeUpdate();
            try (ResultSet keys = st.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("阶段记录创建失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void finishStage(long stageId, String status, String message, String artifact,
                            long endedAt, long durationMs) {
        if (stageId <= 0) {
            return;
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE bugfix_stage_runs
                     SET status = ?, message = ?, artifact = ?, ended_at = ?, duration_ms = ?
                     WHERE id = ?
                     """)) {
            st.setString(1, status);
            st.setString(2, message);
            st.setString(3, artifact);
            st.setLong(4, endedAt);
            st.setLong(5, durationMs);
            st.setLong(6, stageId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("阶段记录更新失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void closeOpenStages(String runId, String status, String message, long endedAt) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE bugfix_stage_runs
                     SET status = ?, message = ?, ended_at = ?, duration_ms = ? - started_at
                     WHERE run_id = ? AND status = 'RUNNING'
                     """)) {
            st.setString(1, status);
            st.setString(2, message);
            st.setLong(3, endedAt);
            st.setLong(4, endedAt);
            st.setString(5, runId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("阶段记录收尾失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<BugFixStageRun> stageRuns(String runId) {
        List<BugFixStageRun> result = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT id, run_id, stage, attempt, status, message, artifact,
                            started_at, ended_at, duration_ms
                     FROM bugfix_stage_runs WHERE run_id = ? ORDER BY id
                     """)) {
            st.setString(1, runId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    result.add(new BugFixStageRun(
                            rs.getLong("id"),
                            rs.getString("run_id"),
                            stage(rs.getString("stage")),
                            rs.getInt("attempt"),
                            rs.getString("status"),
                            rs.getString("message"),
                            rs.getString("artifact"),
                            rs.getLong("started_at"),
                            rs.getLong("ended_at"),
                            rs.getLong("duration_ms")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("阶段记录查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public int stageAttempts(String runId, BugFixStage stage) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT COUNT(*) FROM bugfix_stage_runs WHERE run_id = ? AND stage = ?
                     """)) {
            st.setString(1, runId);
            st.setString(2, stage.name());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("阶段尝试次数查询失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 通知发件箱

    @Override
    public void enqueue(String runId, String message) {
        if (runId == null || message == null || message.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO bugfix_notifications
                         (run_id, message, status, attempts, next_attempt_at, created_at)
                     VALUES (?,?,'PENDING',0,?,?)
                     """)) {
            st.setString(1, runId);
            st.setString(2, message);
            st.setLong(3, now);
            st.setLong(4, now);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("通知入队失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<BugFixNotification> due(int limit) {
        List<BugFixNotification> result = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT id, run_id, message, status, attempts, next_attempt_at, last_error,
                            created_at
                     FROM bugfix_notifications
                     WHERE status = 'PENDING' AND next_attempt_at <= ?
                     ORDER BY id LIMIT ?
                     """)) {
            st.setLong(1, System.currentTimeMillis());
            st.setInt(2, limit <= 0 ? 20 : Math.min(limit, 100));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    result.add(new BugFixNotification(
                            rs.getLong("id"),
                            rs.getString("run_id"),
                            rs.getString("message"),
                            rs.getString("status"),
                            rs.getInt("attempts"),
                            rs.getLong("next_attempt_at"),
                            rs.getString("last_error"),
                            rs.getLong("created_at")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("待投递通知查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void markSent(long id) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE bugfix_notifications SET status = 'SENT', last_error = ''
                     WHERE id = ?
                     """)) {
            st.setLong(1, id);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("通知状态更新失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void markFailed(long id, String error, int maxAttempts) {
        int attempts = 0;
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT attempts FROM bugfix_notifications WHERE id = ?")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    attempts = rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("通知重试状态读取失败：" + e.getMessage(), e);
        }
        int next = attempts + 1;
        boolean terminal = next >= (maxAttempts <= 0 ? 5 : maxAttempts);
        long backoff = Math.min(300_000L, (long) Math.pow(2, Math.min(next, 6)) * 5_000L);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE bugfix_notifications
                     SET status = ?, attempts = ?, last_error = ?, next_attempt_at = ?
                     WHERE id = ?
                     """)) {
            st.setString(1, terminal ? BugFixNotification.FAILED : BugFixNotification.PENDING);
            st.setInt(2, next);
            st.setString(3, error == null ? "" : error);
            st.setLong(4, System.currentTimeMillis() + backoff);
            st.setLong(5, id);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("通知重试状态更新失败：" + e.getMessage(), e);
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
            return readPolicy(rs);
        } catch (SQLException e) {
            throw new IllegalStateException("自动修复策略读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public BugFixPolicy load(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return load();
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + POLICY_COLUMNS + " FROM bugfix_project_policy WHERE project_id = ?")) {
            st.setString(1, projectId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return load();
                }
                return readPolicy(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("项目自动修复策略读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public BugFixPolicy save(BugFixPolicy policy) {
        BugFixPolicy normalized = policy.normalized().withUpdatedAt(System.currentTimeMillis());
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT OR REPLACE INTO bugfix_policy
                         (id, enabled, auto_deploy, auto_create_pr, test_env, binding_id,
                          max_attempts, base_url, build_command, test_command, updated_at)
                     VALUES (1,?,?,?,?,?,?,?,?,?,?)
                     """)) {
            bindPolicy(st, normalized);
            st.executeUpdate();
            return normalized;
        } catch (SQLException e) {
            throw new IllegalStateException("自动修复策略保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public BugFixPolicy save(String projectId, BugFixPolicy policy) {
        if (projectId == null || projectId.isBlank()) {
            return save(policy);
        }
        BugFixPolicy normalized = policy.normalized().withUpdatedAt(System.currentTimeMillis());
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT OR REPLACE INTO bugfix_project_policy
                         (project_id, enabled, auto_deploy, auto_create_pr, test_env, binding_id,
                          max_attempts, base_url, build_command, test_command, updated_at)
                     VALUES (?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
            st.setString(1, projectId);
            bindPolicy(st, normalized, 2);
            st.executeUpdate();
            return normalized;
        } catch (SQLException e) {
            throw new IllegalStateException("项目自动修复策略保存失败：" + e.getMessage(), e);
        }
    }

    private static void bindPolicy(PreparedStatement st, BugFixPolicy policy) throws SQLException {
        bindPolicy(st, policy, 1);
    }

    private static void bindPolicy(PreparedStatement st, BugFixPolicy policy, int offset)
            throws SQLException {
        st.setInt(offset, policy.enabled() ? 1 : 0);
        st.setInt(offset + 1, policy.autoDeploy() ? 1 : 0);
        st.setInt(offset + 2, policy.autoCreatePr() ? 1 : 0);
        st.setString(offset + 3, policy.testEnv());
        st.setString(offset + 4, policy.bindingId());
        st.setInt(offset + 5, policy.maxAttempts());
        st.setString(offset + 6, policy.baseUrl());
        st.setString(offset + 7, policy.buildCommand());
        st.setString(offset + 8, policy.testCommand());
        st.setLong(offset + 9, policy.updatedAt());
    }

    private static BugFixPolicy readPolicy(ResultSet rs) throws SQLException {
        return new BugFixPolicy(
                rs.getInt("enabled") != 0,
                rs.getInt("auto_deploy") != 0,
                rs.getInt("auto_create_pr") != 0,
                rs.getString("test_env"),
                rs.getString("binding_id"),
                rs.getInt("max_attempts"),
                rs.getString("base_url"),
                rs.getString("build_command"),
                rs.getString("test_command"),
                rs.getLong("updated_at"));
    }
}
