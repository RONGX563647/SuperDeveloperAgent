package top.rongx.agent.infra.settings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;
import top.rongx.agent.domain.gitissue.GitIssueIntegration;
import top.rongx.agent.domain.gitissue.GitIssueIntegrationRepository;
import top.rongx.agent.domain.gitissue.GitIssueRepository;
import top.rongx.agent.infra.security.SecretBox;

/**
 * Issue 任务 / 评论 / 事件去重 / 集成配置的 SQLite 存储。
 *
 * <p>与平台的其它设置共用 {@code _platform/projects.db}；webhook 密钥按平台主密钥
 * AES-GCM 加密，接口只回掩码。
 */
@Repository
public class SqliteGitIssueStore implements GitIssueRepository, GitIssueIntegrationRepository {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS git_issues (
                id           TEXT PRIMARY KEY,
                user_id      TEXT NOT NULL,
                project_id   TEXT,
                provider     TEXT NOT NULL,
                repo         TEXT NOT NULL,
                issue_number TEXT NOT NULL,
                title        TEXT NOT NULL,
                body         TEXT,
                state        TEXT NOT NULL,
                kind         TEXT NOT NULL DEFAULT 'BUG',
                labels       TEXT,
                url          TEXT,
                source       TEXT NOT NULL,
                sync_status  TEXT NOT NULL,
                sync_error   TEXT,
                assignee     TEXT,
                created_at   INTEGER NOT NULL,
                updated_at   INTEGER NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_git_issues_user
                ON git_issues (user_id, updated_at DESC);
            CREATE INDEX IF NOT EXISTS idx_git_issues_remote
                ON git_issues (provider, repo, issue_number);

            CREATE TABLE IF NOT EXISTS git_issue_comments (
                id         TEXT PRIMARY KEY,
                issue_id   TEXT NOT NULL,
                author     TEXT,
                body       TEXT NOT NULL,
                source     TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_git_issue_comments_issue
                ON git_issue_comments (issue_id, created_at);

            CREATE TABLE IF NOT EXISTS git_issue_events (
                event_key   TEXT PRIMARY KEY,
                received_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS git_integrations (
                provider           TEXT PRIMARY KEY,
                owner_user_id      TEXT,
                enabled            INTEGER NOT NULL,
                repo_whitelist     TEXT,
                secret_enc         TEXT,
                secret_iv          TEXT,
                auto_create_task   INTEGER NOT NULL,
                sync_direction     TEXT NOT NULL,
                conflict_policy    TEXT NOT NULL,
                updated_at         INTEGER NOT NULL
            )
            """;

    private static final String ISSUE_COLUMNS = """
            id, user_id, project_id, provider, repo, issue_number, title, body, state, kind,
            labels, url, source, sync_status, sync_error, assignee, created_at, updated_at
            """;

    private static final String INTEGRATION_COLUMNS = """
            provider, owner_user_id, enabled, repo_whitelist, secret_enc, secret_iv,
            auto_create_task, sync_direction, conflict_policy, updated_at
            """;

    private final Path dbFile;
    private final SecretBox secretBox;

    public SqliteGitIssueStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
            SecretBox secretBox) {
        this.dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("projects.db");
        this.secretBox = secretBox;
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = open(); java.sql.Statement st = conn.createStatement()) {
            // SQLite JDBC 一次 prepare 只执行第一条语句，需要逐条执行建表/索引
            for (String sql : SCHEMA.split(";")) {
                String statement = sql.trim();
                if (!statement.isEmpty()) {
                    st.executeUpdate(statement);
                }
            }
            // 旧库补列：kind 是后加的分类字段，CREATE TABLE IF NOT EXISTS 不会补
            boolean hasKind = false;
            try (ResultSet rs = st.executeQuery("PRAGMA table_info(git_issues)")) {
                while (rs.next()) {
                    if ("kind".equalsIgnoreCase(rs.getString("name"))) {
                        hasKind = true;
                    }
                }
            }
            if (!hasKind) {
                st.executeUpdate(
                        "ALTER TABLE git_issues ADD COLUMN kind TEXT NOT NULL DEFAULT 'BUG'");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("git issue 表初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return top.rongx.agent.infra.sqlite.SqliteConnections.get(dbFile);
    }

    // ---------------------------------------------------------------- Issue

    @Override
    public List<GitIssue> list(String userId, String provider, String state, String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + ISSUE_COLUMNS + " FROM git_issues WHERE user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (provider != null && !provider.isBlank()) {
            sql.append(" AND provider = ?");
            args.add(provider.trim().toUpperCase(Locale.ROOT));
        }
        if (state != null && !state.isBlank()) {
            sql.append(" AND state = ?");
            args.add(state.trim().toUpperCase(Locale.ROOT));
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (title LIKE ? OR body LIKE ? OR repo LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY CASE state WHEN 'OPEN' THEN 0 ELSE 1 END, updated_at DESC");
        try (Connection conn = open(); PreparedStatement st = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                st.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = st.executeQuery()) {
                List<GitIssue> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapIssue(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("任务查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<GitIssue> find(String id, String userId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + ISSUE_COLUMNS + " FROM git_issues WHERE id = ? AND user_id = ?")) {
            st.setString(1, id);
            st.setString(2, userId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(mapIssue(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("任务查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<GitIssue> findByRemote(String provider, String repo, String number) {
        if (number == null || number.isBlank()) {
            return Optional.empty();
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + ISSUE_COLUMNS + " FROM git_issues "
                             + "WHERE provider = ? AND repo = ? AND issue_number = ? "
                             + "ORDER BY updated_at DESC LIMIT 1")) {
            st.setString(1, provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT));
            st.setString(2, repo == null ? "" : repo.trim());
            st.setString(3, number.trim());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(mapIssue(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("任务绑定查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public GitIssue save(GitIssue issue) {
        long now = System.currentTimeMillis();
        try (Connection conn = open()) {
            boolean exists;
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT 1 FROM git_issues WHERE id = ?")) {
                st.setString(1, issue.id());
                try (ResultSet rs = st.executeQuery()) {
                    exists = rs.next();
                }
            }
            if (!exists) {
                try (PreparedStatement st = conn.prepareStatement("""
                        INSERT INTO git_issues
                            (id, user_id, project_id, provider, repo, issue_number, title, body,
                             state, kind, labels, url, source, sync_status, sync_error, assignee,
                             created_at, updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """)) {
                    bindIssue(st, issue, now);
                    st.executeUpdate();
                }
            } else {
                try (PreparedStatement st = conn.prepareStatement("""
                        UPDATE git_issues SET
                            project_id=?, provider=?, repo=?, issue_number=?, title=?, body=?,
                            state=?, kind=?, labels=?, url=?, source=?, sync_status=?,
                            sync_error=?, assignee=?, updated_at=?
                        WHERE id=?
                        """)) {
                    st.setString(1, issue.projectId());
                    st.setString(2, issue.provider());
                    st.setString(3, issue.repo());
                    st.setString(4, issue.number());
                    st.setString(5, issue.title());
                    st.setString(6, issue.body());
                    st.setString(7, issue.state());
                    st.setString(8, issue.kind());
                    st.setString(9, issue.labels());
                    st.setString(10, issue.url());
                    st.setString(11, issue.source());
                    st.setString(12, issue.syncStatus());
                    st.setString(13, issue.syncError());
                    st.setString(14, issue.assignee());
                    st.setLong(15, now);
                    st.setString(16, issue.id());
                    st.executeUpdate();
                }
            }
            GitIssue saved = new GitIssue(issue.id(), issue.userId(), issue.projectId(),
                    issue.provider(), issue.repo(), issue.number(), issue.title(), issue.body(),
                    issue.state(), issue.kind(), issue.labels(), issue.url(), issue.source(),
                    issue.syncStatus(), issue.syncError(), issue.assignee(),
                    issue.createdAt() > 0 ? issue.createdAt() : now, now);
            return saved;
        } catch (SQLException e) {
            throw new IllegalStateException("任务保存失败：" + e.getMessage(), e);
        }
    }

    private void bindIssue(PreparedStatement st, GitIssue issue, long now) throws SQLException {
        st.setString(1, issue.id());
        st.setString(2, issue.userId());
        st.setString(3, issue.projectId());
        st.setString(4, issue.provider());
        st.setString(5, issue.repo());
        st.setString(6, issue.number());
        st.setString(7, issue.title());
        st.setString(8, issue.body());
        st.setString(9, issue.state());
        st.setString(10, issue.kind());
        st.setString(11, issue.labels());
        st.setString(12, issue.url());
        st.setString(13, issue.source());
        st.setString(14, issue.syncStatus());
        st.setString(15, issue.syncError());
        st.setString(16, issue.assignee());
        st.setLong(17, issue.createdAt() > 0 ? issue.createdAt() : now);
        st.setLong(18, now);
    }

    @Override
    public void delete(String id, String userId) {
        try (Connection conn = open()) {
            try (PreparedStatement st = conn.prepareStatement(
                    "DELETE FROM git_issues WHERE id = ? AND user_id = ?")) {
                st.setString(1, id);
                st.setString(2, userId);
                st.executeUpdate();
            }
            try (PreparedStatement st = conn.prepareStatement(
                    "DELETE FROM git_issue_comments WHERE issue_id = ?")) {
                st.setString(1, id);
                st.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("任务删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public long countOpen(String userId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT COUNT(*) FROM git_issues WHERE user_id = ? AND state = 'OPEN'")) {
            st.setString(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("任务统计失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 评论

    @Override
    public List<GitIssueComment> comments(String issueId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT id, issue_id, author, body, source, created_at
                     FROM git_issue_comments WHERE issue_id = ? ORDER BY created_at, id
                     """)) {
            st.setString(1, issueId);
            try (ResultSet rs = st.executeQuery()) {
                List<GitIssueComment> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapComment(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("评论查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public GitIssueComment addComment(GitIssueComment comment) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT OR REPLACE INTO git_issue_comments
                         (id, issue_id, author, body, source, created_at)
                     VALUES (?,?,?,?,?,?)
                     """)) {
            st.setString(1, comment.id());
            st.setString(2, comment.issueId());
            st.setString(3, comment.author());
            st.setString(4, comment.body());
            st.setString(5, comment.source());
            st.setLong(6, comment.createdAt() > 0 ? comment.createdAt() : System.currentTimeMillis());
            st.executeUpdate();
            return comment;
        } catch (SQLException e) {
            throw new IllegalStateException("评论保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteComment(String commentId, String issueId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM git_issue_comments WHERE id = ? AND issue_id = ?")) {
            st.setString(1, commentId);
            st.setString(2, issueId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("评论删除失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 事件去重

    @Override
    public boolean markEvent(String eventKey) {
        if (eventKey == null || eventKey.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        try (Connection conn = open()) {
            try (PreparedStatement cleanup = conn.prepareStatement(
                    "DELETE FROM git_issue_events WHERE received_at < ?")) {
                cleanup.setLong(1, now - 30L * 24 * 3600 * 1000);
                cleanup.executeUpdate();
            }
            try (PreparedStatement st = conn.prepareStatement(
                    "INSERT OR IGNORE INTO git_issue_events (event_key, received_at) VALUES (?,?)")) {
                st.setString(1, eventKey);
                st.setLong(2, now);
                return st.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("事件去重失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 集成配置

    @Override
    public List<GitIssueIntegration> findAll() {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + INTEGRATION_COLUMNS + " FROM git_integrations "
                             + "ORDER BY provider")) {
            try (ResultSet rs = st.executeQuery()) {
                List<GitIssueIntegration> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapIntegration(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Issue 同步配置查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<GitIssueIntegration> find(String provider) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + INTEGRATION_COLUMNS + " FROM git_integrations WHERE provider = ?")) {
            st.setString(1, normalizeProvider(provider));
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(mapIntegration(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Issue 同步配置查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> secretOf(String provider) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT secret_enc, secret_iv FROM git_integrations WHERE provider = ?")) {
            st.setString(1, normalizeProvider(provider));
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next() || rs.getString("secret_enc") == null) {
                    return Optional.empty();
                }
                return Optional.of(secretBox.decrypt(
                        rs.getString("secret_enc"), rs.getString("secret_iv")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Webhook 密钥读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public GitIssueIntegration save(Draft draft, String webhookSecret, String ownerUserId) {
        String provider = normalizeProvider(draft.provider());
        long now = System.currentTimeMillis();
        try (Connection conn = open()) {
            SecretBox.Sealed existing = existingSecret(conn, provider);
            SecretBox.Sealed sealed = webhookSecret != null && !webhookSecret.isBlank()
                    ? secretBox.encrypt(webhookSecret.trim()) : existing;
            String direction = normalizeDirection(draft.syncDirection());
            String conflict = "PLATFORM_WINS".equalsIgnoreCase(draft.conflictPolicy())
                    ? "PLATFORM_WINS" : "GIT_WINS";
            String owner = ownerUserId == null ? "" : ownerUserId;
            try (PreparedStatement st = conn.prepareStatement("""
                    INSERT INTO git_integrations
                        (provider, owner_user_id, enabled, repo_whitelist, secret_enc, secret_iv,
                         auto_create_task, sync_direction, conflict_policy, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(provider) DO UPDATE SET
                        owner_user_id=excluded.owner_user_id,
                        enabled=excluded.enabled,
                        repo_whitelist=excluded.repo_whitelist,
                        secret_enc=COALESCE(excluded.secret_enc, git_integrations.secret_enc),
                        secret_iv=COALESCE(excluded.secret_iv, git_integrations.secret_iv),
                        auto_create_task=excluded.auto_create_task,
                        sync_direction=excluded.sync_direction,
                        conflict_policy=excluded.conflict_policy,
                        updated_at=excluded.updated_at
                    """)) {
                st.setString(1, provider);
                st.setString(2, owner);
                st.setBoolean(3, draft.enabled());
                st.setString(4, draft.repoWhitelist() == null ? "" : draft.repoWhitelist().trim());
                st.setString(5, sealed == null ? null : sealed.cipherB64());
                st.setString(6, sealed == null ? null : sealed.ivB64());
                st.setBoolean(7, draft.autoCreateTask());
                st.setString(8, direction);
                st.setString(9, conflict);
                st.setLong(10, now);
                st.executeUpdate();
            }
            return find(provider).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("Issue 同步配置保存失败：" + e.getMessage(), e);
        }
    }

    private SecretBox.Sealed existingSecret(Connection conn, String provider) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT secret_enc, secret_iv FROM git_integrations WHERE provider = ?")) {
            st.setString(1, provider);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next() || rs.getString("secret_enc") == null) {
                    return null;
                }
                return new SecretBox.Sealed(rs.getString("secret_enc"), rs.getString("secret_iv"));
            }
        }
    }

    @Override
    public void delete(String provider) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM git_integrations WHERE provider = ?")) {
            st.setString(1, normalizeProvider(provider));
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Issue 同步配置删除失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 映射

    private GitIssue mapIssue(ResultSet rs) throws SQLException {
        return new GitIssue(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("project_id"),
                rs.getString("provider"),
                rs.getString("repo"),
                rs.getString("issue_number"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("state"),
                rs.getString("kind"),
                rs.getString("labels"),
                rs.getString("url"),
                rs.getString("source"),
                rs.getString("sync_status"),
                rs.getString("sync_error"),
                rs.getString("assignee"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private GitIssueComment mapComment(ResultSet rs) throws SQLException {
        return new GitIssueComment(
                rs.getString("id"),
                rs.getString("issue_id"),
                rs.getString("author"),
                rs.getString("body"),
                rs.getString("source"),
                rs.getLong("created_at"));
    }

    private GitIssueIntegration mapIntegration(ResultSet rs) throws SQLException {
        String enc = rs.getString("secret_enc");
        String plain = enc == null ? "" : secretBox.decrypt(enc, rs.getString("secret_iv"));
        String masked = plain.isBlank() ? null
                : (plain.length() <= 4 ? "***" : plain.substring(0, 4) + "***");
        return new GitIssueIntegration(
                rs.getString("provider"),
                rs.getString("owner_user_id"),
                rs.getBoolean("enabled"),
                rs.getString("repo_whitelist"),
                !plain.isBlank(),
                masked,
                rs.getBoolean("auto_create_task"),
                rs.getString("sync_direction"),
                rs.getString("conflict_policy"),
                rs.getLong("updated_at"));
    }

    private static String normalizeProvider(String provider) {
        String p = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        if (!p.equals("GITEE") && !p.equals("GITHUB") && !p.equals("GITLAB")) {
            throw new IllegalArgumentException("provider 只能是 GITEE / GITHUB / GITLAB");
        }
        return p;
    }

    private static String normalizeDirection(String value) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "PUSH_ONLY", "PULL_ONLY" -> v;
            default -> "BIDIRECTIONAL";
        };
    }
}
