package top.rongx.agent.infra.settings;

import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;
import top.rongx.agent.domain.gitissue.GitIssueIntegration;
import top.rongx.agent.domain.gitissue.GitIssueIntegrationRepository;
import top.rongx.agent.domain.gitissue.GitIssueRepository;
import top.rongx.agent.infra.security.SecretBox;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.IntakeException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * Issue 任务 / 评论 / 事件去重 / 集成配置的存储。
 *
 * <p>表结构由 Hibernate 依据 {@code @Entity} 生成；webhook 密钥按平台主密钥
 * AES-GCM 加密，接口只回掩码。
 */
@Repository
public class SqliteGitIssueStore implements GitIssueRepository, GitIssueIntegrationRepository {

    private static final String ISSUE_COLUMNS = """
            id, code, user_id, project_id, provider, repo, issue_number, title, body, state, kind,
            priority, labels, url, source, sync_status, sync_error, assignee, created_at,
            updated_at, version
            """;

    private static final String INTEGRATION_COLUMNS = """
            provider, owner_user_id, enabled, repo_whitelist, secret_enc, secret_iv,
            auto_create_task, sync_direction, conflict_policy, updated_at
            """;

    private final PlatformDb db;
    private final SecretBox secretBox;

    @Autowired
    public SqliteGitIssueStore(PlatformDb db, SecretBox secretBox) {
        this.db = db;
        this.secretBox = secretBox;
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public SqliteGitIssueStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
            SecretBox secretBox) {
        this(PlatformDb.sqlite(Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.db")), secretBox);
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
        try {
            return db.read(em -> PlatformDb.rows(em, sql.toString(), args.toArray()).stream()
                    .map(SqliteGitIssueStore::mapIssue).toList());
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "任务查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<GitIssue> find(String id, String userId) {
        try {
            return db.read(em -> PlatformDb.row(em,
                            "SELECT " + ISSUE_COLUMNS + " FROM git_issues WHERE id = ? AND user_id = ?",
                            id, userId)
                    .map(SqliteGitIssueStore::mapIssue));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "任务查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<GitIssue> findByRemote(String provider, String repo, String number) {
        if (number == null || number.isBlank()) {
            return Optional.empty();
        }
        String normalizedProvider = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        String normalizedRepo = repo == null ? "" : repo.trim();
        try {
            return db.read(em -> PlatformDb.row(em,
                            "SELECT " + ISSUE_COLUMNS + " FROM git_issues "
                                    + "WHERE provider = ? AND repo = ? AND issue_number = ? "
                                    + "ORDER BY updated_at DESC LIMIT 1",
                            normalizedProvider, normalizedRepo, number.trim())
                    .map(SqliteGitIssueStore::mapIssue));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "任务绑定查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public GitIssue save(GitIssue issue) {
        long now = System.currentTimeMillis();
        try {
            return db.inTx(em -> {
                boolean exists = PlatformDb.first(em,
                        "SELECT 1 FROM git_issues WHERE id = ?", issue.id()).isPresent();
                // 编码只在首次创建时分配；更新路径沿用数据库里的值，避免被空值覆盖
                String code = issue.code() == null ? "" : issue.code().trim();
                if (code.isBlank()) {
                    code = exists ? existingCode(em, issue.id()) : nextCode(em, issue);
                }
                // 标题里带编码（FEAT-4 第 1 项）：编码在创建时才分配，调用方拿不到，
                // 所以由存储层统一加前缀。用户已经自己写了编码就不重复加。
                String title = exists ? issue.title() : withCodePrefix(issue.title(), code);
                if (!exists) {
                    PlatformDb.update(em, """
                            INSERT INTO git_issues
                                (id, code, user_id, project_id, provider, repo, issue_number, title, body,
                                 state, kind, priority, labels, url, source, sync_status, sync_error,
                                 assignee, created_at, updated_at, version)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                            """,
                            issue.id(), code, issue.userId(), issue.projectId(), issue.provider(),
                            issue.repo(), issue.number(), title, issue.body(), issue.state(),
                            issue.kind(), issue.priority(), issue.labels(), issue.url(), issue.source(),
                            issue.syncStatus(), issue.syncError(), issue.assignee(),
                            issue.createdAt() > 0 ? issue.createdAt() : now, now, issue.version());
                } else {
                    PlatformDb.update(em, """
                            UPDATE git_issues SET
                                project_id=?, provider=?, repo=?, issue_number=?, title=?, body=?,
                                state=?, kind=?, priority=?, labels=?, url=?, source=?, sync_status=?,
                                sync_error=?, assignee=?, updated_at=?, version=?
                            WHERE id=?
                            """,
                            issue.projectId(), issue.provider(), issue.repo(), issue.number(),
                            issue.title(), issue.body(), issue.state(), issue.kind(), issue.priority(),
                            issue.labels(), issue.url(), issue.source(), issue.syncStatus(),
                            issue.syncError(), issue.assignee(), now, issue.version(), issue.id());
                }
                return new GitIssue(issue.id(), code, issue.userId(), issue.projectId(),
                        issue.provider(), issue.repo(), issue.number(), title, issue.body(),
                        issue.state(), issue.kind(), issue.priority(), issue.labels(), issue.url(),
                        issue.source(), issue.syncStatus(), issue.syncError(), issue.assignee(),
                        issue.createdAt() > 0 ? issue.createdAt() : now, now, issue.version());
            });
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "任务保存失败：" + e.getMessage(), e);
        }
    }

    /**
     * 标题前缀：把编码写进标题，让列表、提交信息、归档名三处对得上同一个编号。
     *
     * <p>为什么在存储层做：编码是创建时按分类自增分配的，调用方提交草稿时
     * 根本还不知道自己会拿到哪个编码。放在这里既保证唯一性，也让所有入口
     * （任务页、Agent 工具、Git webhook）行为一致。
     *
     * <p>幂等：用户/模型已经自己写了 {@code BUG-3 xxx} 这种标题时不再重复加。
     */
    static String withCodePrefix(String title, String code) {
        String text = title == null ? "" : title.trim();
        String prefix = code == null ? "" : code.trim();
        if (prefix.isBlank() || text.isBlank()) {
            return text;
        }
        // 已带编码（大小写不敏感、允许后面跟空格或冒号）就不重复
        if (text.regionMatches(true, 0, prefix, 0, prefix.length())) {
            String rest = text.substring(prefix.length());
            if (rest.isEmpty() || rest.startsWith(" ") || rest.startsWith(":") || rest.startsWith("：")) {
                return text;
            }
        }
        return prefix + " " + text;
    }

    /**
     * 分配任务编码：项目内按分类自增（FEAT-1 / BUG-3），创建后不再变化。
     *
     * <p>同一用户、同一项目、同一分类取当前最大序号 +1；旧任务没有编码时按创建顺序回填。
     */
    private static String nextCode(EntityManager em, GitIssue issue) {
        String prefix = codePrefix(issue.kind());
        String project = issue.projectId() == null ? "" : issue.projectId();
        Object current = PlatformDb.first(em,
                "SELECT COALESCE(MAX(CAST(SUBSTR(code, ?) AS INTEGER)), 0) FROM git_issues "
                        + "WHERE user_id = ? AND kind = ? AND COALESCE(project_id, '') = ? "
                        + "AND code LIKE ?",
                prefix.length() + 1, issue.userId(), issue.kind(), project, prefix + "%")
                .orElse(0);
        int next = current instanceof Number number ? number.intValue() + 1 : 1;
        return prefix + next;
    }

    /** 更新时沿用已分配的编码：调用方没带 code 也不能把它清空。 */
    private static String existingCode(EntityManager em, String issueId) {
        return PlatformDb.<String>first(em, "SELECT code FROM git_issues WHERE id = ?", issueId)
                .map(value -> value == null ? "" : value)
                .orElse("");
    }

    private static String codePrefix(String kind) {
        return "FEAT".equalsIgnoreCase(kind) ? "FEAT-" : "BUG-";
    }

    @Override
    public void delete(String id, String userId) {
        try {
            db.inTx(em -> {
                PlatformDb.update(em, "DELETE FROM git_issues WHERE id = ? AND user_id = ?", id, userId);
                PlatformDb.update(em, "DELETE FROM git_issue_comments WHERE issue_id = ?", id);
                return null;
            });
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "任务删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public long countOpen(String userId) {
        try {
            return db.read(em -> PlatformDb.count(em,
                    "SELECT COUNT(*) FROM git_issues WHERE user_id = ? AND state = 'OPEN'", userId));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "任务统计失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 评论

    @Override
    public List<GitIssueComment> comments(String issueId) {
        try {
            return db.read(em -> PlatformDb.rows(em, """
                            SELECT id, issue_id, author, body, source, created_at
                            FROM git_issue_comments WHERE issue_id = ? ORDER BY created_at, id
                            """, issueId).stream()
                    .map(SqliteGitIssueStore::mapComment).toList());
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "评论查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public GitIssueComment addComment(GitIssueComment comment) {
        try {
            db.run(em -> PlatformDb.update(em, """
                    INSERT INTO git_issue_comments
                        (id, issue_id, author, body, source, created_at)
                    VALUES (?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET
                        issue_id = excluded.issue_id,
                        author = excluded.author,
                        body = excluded.body,
                        source = excluded.source,
                        created_at = excluded.created_at
                    """,
                    comment.id(), comment.issueId(), comment.author(), comment.body(),
                    comment.source(),
                    comment.createdAt() > 0 ? comment.createdAt() : System.currentTimeMillis()));
            return comment;
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "评论保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteComment(String commentId, String issueId) {
        try {
            db.run(em -> PlatformDb.update(em,
                    "DELETE FROM git_issue_comments WHERE id = ? AND issue_id = ?", commentId, issueId));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "评论删除失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 事件去重

    @Override
    public boolean markEvent(String eventKey) {
        if (eventKey == null || eventKey.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        try {
            return db.inTx(em -> {
                PlatformDb.update(em, "DELETE FROM git_issue_events WHERE received_at < ?",
                        now - 30L * 24 * 3600 * 1000);
                return PlatformDb.update(em, """
                        INSERT INTO git_issue_events (event_key, received_at) VALUES (?,?)
                        ON CONFLICT(event_key) DO NOTHING
                        """, eventKey, now) > 0;
            });
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "事件去重失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 集成配置

    @Override
    public List<GitIssueIntegration> findAll() {
        try {
            return db.read(em -> PlatformDb.rows(em,
                            "SELECT " + INTEGRATION_COLUMNS + " FROM git_integrations ORDER BY provider")
                    .stream().map(this::mapIntegration).toList());
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "Issue 同步配置查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<GitIssueIntegration> find(String provider) {
        String normalized = normalizeProvider(provider);
        try {
            return db.read(em -> PlatformDb.row(em,
                            "SELECT " + INTEGRATION_COLUMNS + " FROM git_integrations WHERE provider = ?",
                            normalized)
                    .map(this::mapIntegration));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "Issue 同步配置查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> secretOf(String provider) {
        String normalized = normalizeProvider(provider);
        try {
            return db.read(em -> PlatformDb.row(em,
                            "SELECT secret_enc, secret_iv FROM git_integrations WHERE provider = ?",
                            normalized)
                    .flatMap(row -> {
                        String enc = (String) row[0];
                        if (enc == null) {
                            return Optional.empty();
                        }
                        return Optional.of(secretBox.decrypt(enc, (String) row[1]));
                    }));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "Webhook 密钥读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public GitIssueIntegration save(Draft draft, String webhookSecret, String ownerUserId) {
        String provider = normalizeProvider(draft.provider());
        long now = System.currentTimeMillis();
        try {
            db.inTx(em -> {
                SecretBox.Sealed existing = existingSecret(em, provider);
                SecretBox.Sealed sealed = webhookSecret != null && !webhookSecret.isBlank()
                        ? secretBox.encrypt(webhookSecret.trim()) : existing;
                String direction = normalizeDirection(draft.syncDirection());
                String conflict = "PLATFORM_WINS".equalsIgnoreCase(draft.conflictPolicy())
                        ? "PLATFORM_WINS" : "GIT_WINS";
                String owner = ownerUserId == null ? "" : ownerUserId;
                PlatformDb.update(em, """
                        INSERT INTO git_integrations
                            (provider, owner_user_id, enabled, repo_whitelist, secret_enc, secret_iv,
                             auto_create_task, sync_direction, conflict_policy, updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(provider) DO UPDATE SET
                            owner_user_id=excluded.owner_user_id,
                            enabled=excluded.enabled,
                            repo_whitelist=excluded.repo_whitelist,
                            secret_enc=COALESCE(excluded.secret_enc, secret_enc),
                            secret_iv=COALESCE(excluded.secret_iv, secret_iv),
                            auto_create_task=excluded.auto_create_task,
                            sync_direction=excluded.sync_direction,
                            conflict_policy=excluded.conflict_policy,
                            updated_at=excluded.updated_at
                        """,
                        provider, owner, draft.enabled() ? 1 : 0,
                        draft.repoWhitelist() == null ? "" : draft.repoWhitelist().trim(),
                        sealed == null ? null : sealed.cipherB64(),
                        sealed == null ? null : sealed.ivB64(),
                        draft.autoCreateTask() ? 1 : 0, direction, conflict, now);
                return null;
            });
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "Issue 同步配置保存失败：" + e.getMessage(), e);
        }
        return find(provider).orElseThrow();
    }

    private SecretBox.Sealed existingSecret(EntityManager em, String provider) {
        return PlatformDb.row(em,
                        "SELECT secret_enc, secret_iv FROM git_integrations WHERE provider = ?", provider)
                .flatMap(row -> {
                    String enc = (String) row[0];
                    if (enc == null) {
                        return Optional.empty();
                    }
                    return Optional.of(new SecretBox.Sealed(enc, (String) row[1]));
                })
                .orElse(null);
    }

    @Override
    public void delete(String provider) {
        String normalized = normalizeProvider(provider);
        try {
            db.run(em -> PlatformDb.update(em,
                    "DELETE FROM git_integrations WHERE provider = ?", normalized));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "Issue 同步配置删除失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 映射

    private static GitIssue mapIssue(Object[] row) {
        return new GitIssue(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[4],
                (String) row[5],
                (String) row[6],
                (String) row[7],
                (String) row[8],
                (String) row[9],
                (String) row[10],
                (String) row[11],
                (String) row[12],
                (String) row[13],
                (String) row[14],
                (String) row[15],
                (String) row[16],
                (String) row[17],
                ((Number) row[18]).longValue(),
                ((Number) row[19]).longValue(),
                (String) row[20]);
    }

    private static GitIssueComment mapComment(Object[] row) {
        return new GitIssueComment(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[4],
                ((Number) row[5]).longValue());
    }

    private GitIssueIntegration mapIntegration(Object[] row) {
        String enc = (String) row[4];
        String plain = enc == null ? "" : secretBox.decrypt(enc, (String) row[5]);
        String masked = plain.isBlank() ? null
                : (plain.length() <= 4 ? "***" : plain.substring(0, 4) + "***");
        return new GitIssueIntegration(
                (String) row[0],
                (String) row[1],
                ((Number) row[2]).intValue() == 1,
                (String) row[3],
                !plain.isBlank(),
                masked,
                ((Number) row[6]).intValue() == 1,
                (String) row[7],
                (String) row[8],
                ((Number) row[9]).longValue());
    }

    private static String normalizeProvider(String provider) {
        String p = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        if (!p.equals("GITEE") && !p.equals("GITHUB") && !p.equals("GITLAB")) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "provider 只能是 GITEE / GITHUB / GITLAB");
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
