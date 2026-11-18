package top.rongx.agent.infra.bugfix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.bugfix.BugFixEvent;
import top.rongx.agent.domain.bugfix.BugFixNotification;
import top.rongx.agent.domain.bugfix.BugFixNotificationStore;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixPolicyStore;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixRunArtifacts;
import top.rongx.agent.domain.bugfix.BugFixRunStore;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageRun;
import top.rongx.agent.domain.bugfix.BugFixStatus;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * Bug 自动修复的持久化存储（运行 + 时间线 + 阶段记录 + 租约 + 通知 + 策略）。
 *
 * <p>可靠性要点：
 * <ul>
 *   <li>连接、事务与方言统一交给 {@link PlatformDb}/Hibernate：SQLite 与 PostgreSQL 共用同一套写法；</li>
 *   <li>运行租约 / 环境租约：多实例或重复调度时只有一方能推进，避免重复部署同一测试环境；</li>
 *   <li>阶段记录：逐阶段保存尝试、耗时与证据，验证失败回到修复不会覆盖上一轮证据；</li>
 *   <li>通知发件箱：Issue 评论先落库再异步投递，评论接口抖动不会拖垮状态机；</li>
 *   <li>项目级策略：每个项目可独立配置测试环境与环境绑定，未配置时回退平台默认。</li>
 * </ul>
 */
@Repository
public class SqliteBugFixStore implements BugFixRunStore, BugFixPolicyStore, BugFixNotificationStore {

    private static final String RUN_COLUMNS = """
            id, issue_id, user_id, project_id, provider, repo, issue_number, issue_title,
            issue_body, branch, stage, status, message, analysis, analysis_json, fix_summary,
            base_sha, fix_sha, env, pr_url, evidence, attempt, created_at, updated_at, finished_at
            """;

    private static final String POLICY_COLUMNS = """
            enabled, auto_deploy, auto_create_pr, test_env, binding_id, max_attempts,
            base_url, build_command, test_command, updated_at
            """;

    private static final String SAVE_RUN_SQL = """
            INSERT INTO bugfix_runs
                (id, issue_id, user_id, project_id, provider, repo, issue_number,
                 issue_title, issue_body, branch, stage, status, message, analysis,
                 analysis_json, fix_summary, base_sha, fix_sha, env, pr_url, evidence,
                 attempt, created_at, updated_at, finished_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                issue_id = excluded.issue_id,
                user_id = excluded.user_id,
                project_id = excluded.project_id,
                provider = excluded.provider,
                repo = excluded.repo,
                issue_number = excluded.issue_number,
                issue_title = excluded.issue_title,
                issue_body = excluded.issue_body,
                branch = excluded.branch,
                stage = excluded.stage,
                status = excluded.status,
                message = excluded.message,
                analysis = excluded.analysis,
                analysis_json = excluded.analysis_json,
                fix_summary = excluded.fix_summary,
                base_sha = excluded.base_sha,
                fix_sha = excluded.fix_sha,
                env = excluded.env,
                pr_url = excluded.pr_url,
                evidence = excluded.evidence,
                attempt = excluded.attempt,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                finished_at = excluded.finished_at
            """;

    private static final String INSERT_EVENT_SQL = """
            INSERT INTO bugfix_events (run_id, stage, level, message, created_at)
            VALUES (?,?,?,?,?)
            """;

    private static final String PRUNE_EVENTS_SQL = """
            DELETE FROM bugfix_events WHERE run_id = ? AND id NOT IN
                (SELECT id FROM bugfix_events WHERE run_id = ? ORDER BY id DESC LIMIT ?)
            """;

    private static final String ACQUIRE_LEASE_SQL = """
            INSERT INTO bugfix_leases (run_id, owner, lease_until)
            VALUES (?,?,?) ON CONFLICT(run_id) DO NOTHING
            """;

    private static final String RENEW_LEASE_SQL = """
            UPDATE bugfix_leases SET lease_until = ?
            WHERE run_id = ? AND owner = ?
            """;

    private static final String ACQUIRE_ENV_LEASE_SQL = """
            INSERT INTO bugfix_env_leases (project_id, env, run_id, lease_until)
            VALUES (?,?,?,?) ON CONFLICT(project_id, env) DO NOTHING
            """;

    private static final String RENEW_ENV_LEASE_SQL = """
            UPDATE bugfix_env_leases SET lease_until = ?
            WHERE project_id = ? AND env = ? AND run_id = ?
            """;

    private static final String INSERT_STAGE_SQL = """
            INSERT INTO bugfix_stage_runs
                (run_id, stage, attempt, status, started_at)
            VALUES (?,?,?,?,?)
            RETURNING id
            """;

    private static final String SAVE_POLICY_SQL = """
            INSERT INTO bugfix_policy
                (id, enabled, auto_deploy, auto_create_pr, test_env, binding_id,
                 max_attempts, base_url, build_command, test_command, updated_at)
            VALUES (1,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                enabled = excluded.enabled,
                auto_deploy = excluded.auto_deploy,
                auto_create_pr = excluded.auto_create_pr,
                test_env = excluded.test_env,
                binding_id = excluded.binding_id,
                max_attempts = excluded.max_attempts,
                base_url = excluded.base_url,
                build_command = excluded.build_command,
                test_command = excluded.test_command,
                updated_at = excluded.updated_at
            """;

    private static final String SAVE_PROJECT_POLICY_SQL = """
            INSERT INTO bugfix_project_policy
                (project_id, enabled, auto_deploy, auto_create_pr, test_env, binding_id,
                 max_attempts, base_url, build_command, test_command, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(project_id) DO UPDATE SET
                enabled = excluded.enabled,
                auto_deploy = excluded.auto_deploy,
                auto_create_pr = excluded.auto_create_pr,
                test_env = excluded.test_env,
                binding_id = excluded.binding_id,
                max_attempts = excluded.max_attempts,
                base_url = excluded.base_url,
                build_command = excluded.build_command,
                test_command = excluded.test_command,
                updated_at = excluded.updated_at
            """;

    /** 每个运行保留的时间线事件上限，避免长跑任务把库撑大。 */
    private static final int MAX_EVENTS_PER_RUN = 300;

    private final PlatformDb db;

    @Autowired
    public SqliteBugFixStore(PlatformDb db) {
        this.db = db;
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public SqliteBugFixStore(@Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this(standaloneDb(Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.db")));
    }

    private static PlatformDb standaloneDb(Path dbFile) {
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "platform.db 初始化失败：" + e.getMessage(), e);
        }
        return PlatformDb.sqlite(dbFile);
    }

    // ---------------------------------------------------------------- 运行

    @Override
    public BugFixRun save(BugFixRun run) {
        try {
            db.run(em -> PlatformDb.update(em, SAVE_RUN_SQL,
                    run.id(),
                    run.issueId(),
                    run.userId(),
                    run.projectId(),
                    run.provider(),
                    run.repo(),
                    run.issueNumber(),
                    run.issueTitle(),
                    run.issueBody(),
                    run.branch(),
                    run.stage().name(),
                    run.status().name(),
                    run.message(),
                    run.analysis(),
                    run.analysisJson(),
                    run.fixSummary(),
                    run.baseSha(),
                    run.fixSha(),
                    run.env(),
                    run.prUrl(),
                    run.evidence(),
                    run.attempt(),
                    run.createdAt(),
                    run.updatedAt(),
                    run.finishedAt()));
            return run;
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "自动修复运行保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<BugFixRun> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + RUN_COLUMNS + " FROM bugfix_runs WHERE id = ?", id)
                    .map(SqliteBugFixStore::mapRun));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "自动修复运行查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<BugFixRun> list(String userId, String issueId, int limit) {
        boolean byUser = userId != null && !userId.isBlank();
        boolean byIssue = issueId != null && !issueId.isBlank();
        String sql = "SELECT " + RUN_COLUMNS + " FROM bugfix_runs WHERE 1=1"
                + (byUser ? " AND user_id = ?" : "")
                + (byIssue ? " AND issue_id = ?" : "")
                + " ORDER BY created_at DESC LIMIT ?";
        try {
            return db.read(em -> {
                Object[] args = byUser && byIssue
                        ? new Object[] {userId, issueId, limit <= 0 ? 50 : Math.min(limit, 200)}
                        : byUser ? new Object[] {userId, limit <= 0 ? 50 : Math.min(limit, 200)}
                        : byIssue ? new Object[] {issueId, limit <= 0 ? 50 : Math.min(limit, 200)}
                        : new Object[] {limit <= 0 ? 50 : Math.min(limit, 200)};
                return PlatformDb.rows(em, sql, args).stream().map(SqliteBugFixStore::mapRun).toList();
            });
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "自动修复运行列表查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<BugFixRun> listWaitingMerge(int limit) {
        String sql = "SELECT " + RUN_COLUMNS + " FROM bugfix_runs"
                + " WHERE status = 'WAITING_MERGE' AND pr_url IS NOT NULL AND pr_url <> ''"
                + " ORDER BY updated_at LIMIT ?";
        try {
            return db.read(em -> PlatformDb.rows(em, sql, limit <= 0 ? 20 : Math.min(limit, 100))
                    .stream().map(SqliteBugFixStore::mapRun).toList());
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "待合并运行查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<BugFixRun> activeByIssue(String issueId) {
        if (issueId == null || issueId.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT " + RUN_COLUMNS + " FROM bugfix_runs"
                + " WHERE issue_id = ? AND status IN ('QUEUED','RUNNING')"
                + " ORDER BY created_at DESC LIMIT 1";
        try {
            return db.read(em -> PlatformDb.row(em, sql, issueId).map(SqliteBugFixStore::mapRun));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "活动运行查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void appendEvent(BugFixEvent event) {
        try {
            db.run(em -> PlatformDb.update(em, INSERT_EVENT_SQL,
                    event.runId(), event.stage().name(), event.level(), event.message(),
                    event.createdAt()));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "自动修复事件写入失败：" + e.getMessage(), e);
        }
        try {
            db.run(em -> PlatformDb.update(em, PRUNE_EVENTS_SQL,
                    event.runId(), event.runId(), MAX_EVENTS_PER_RUN));
        } catch (RuntimeException ignored) {
            // 裁剪失败不影响主流程
        }
    }

    @Override
    public List<BugFixEvent> events(String runId) {
        String sql = """
                SELECT id, run_id, stage, level, message, created_at
                FROM bugfix_events WHERE run_id = ? ORDER BY id
                """;
        try {
            return db.read(em -> PlatformDb.rows(em, sql, runId).stream()
                    .map(row -> new BugFixEvent(
                            String.valueOf(((Number) row[0]).longValue()),
                            (String) row[1],
                            stage((String) row[2]),
                            (String) row[3],
                            (String) row[4],
                            ((Number) row[5]).longValue()))
                    .toList());
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "自动修复事件查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<String> recoverInterrupted() {
        long now = System.currentTimeMillis();
        try {
            return db.inTx(em -> {
                // 只清理过期租约，避免把其他实例仍在执行的任务抢回来
                PlatformDb.update(em, "DELETE FROM bugfix_leases WHERE lease_until < ?", now);
                PlatformDb.update(em, "DELETE FROM bugfix_env_leases WHERE lease_until < ?", now);
                PlatformDb.update(em, """
                        UPDATE bugfix_stage_runs SET status = 'FAILED',
                            message = '服务重启中断', ended_at = ?,
                            duration_ms = ? - started_at
                        WHERE status = 'RUNNING' AND run_id IN (
                            SELECT id FROM bugfix_runs WHERE status = 'RUNNING'
                            AND id NOT IN (SELECT run_id FROM bugfix_leases))
                        """, now, now);
                PlatformDb.update(em, """
                        UPDATE bugfix_runs SET status = 'QUEUED',
                            message = '服务重启，已重新排队', updated_at = ?
                        WHERE status = 'RUNNING'
                        AND id NOT IN (SELECT run_id FROM bugfix_leases)
                        """, now);
                return PlatformDb.rows(em,
                                "SELECT id, created_at FROM bugfix_runs WHERE status = 'QUEUED'"
                                        + " ORDER BY created_at")
                        .stream().map(row -> (String) row[0]).toList();
            });
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行状态恢复失败：" + e.getMessage(), e);
        }
    }

    private static BugFixRun mapRun(Object[] row) {
        return new BugFixRun(
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
                stage((String) row[10]),
                status((String) row[11]),
                (String) row[12],
                new BugFixRunArtifacts(
                        (String) row[13],
                        (String) row[14],
                        (String) row[15],
                        (String) row[16],
                        (String) row[17],
                        (String) row[18],
                        (String) row[19],
                        (String) row[20]),
                ((Number) row[21]).intValue(),
                ((Number) row[22]).longValue(),
                ((Number) row[23]).longValue(),
                ((Number) row[24]).longValue());
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
        try {
            return db.inTx(em -> {
                PlatformDb.update(em, "DELETE FROM bugfix_leases WHERE run_id = ? AND lease_until < ?",
                        runId, now);
                if (PlatformDb.update(em, ACQUIRE_LEASE_SQL, runId, owner, until) == 1) {
                    return true;
                }
                return PlatformDb.update(em, RENEW_LEASE_SQL, until, runId, owner) == 1;
            });
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行租约获取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean renewLease(String runId, String owner, long ttlMs) {
        try {
            return db.inTx(em -> PlatformDb.update(em, RENEW_LEASE_SQL,
                    System.currentTimeMillis() + ttlMs, runId, owner) == 1);
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行租约续期失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void releaseLease(String runId, String owner) {
        try {
            db.run(em -> PlatformDb.update(em,
                    "DELETE FROM bugfix_leases WHERE run_id = ? AND owner = ?", runId, owner));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行租约释放失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean acquireEnvLease(String projectId, String env, String runId, long ttlMs) {
        String project = projectId == null || projectId.isBlank() ? "__unknown__" : projectId;
        String environment = env == null || env.isBlank() ? "test" : env;
        long now = System.currentTimeMillis();
        long until = now + ttlMs;
        try {
            return db.inTx(em -> {
                PlatformDb.update(em, """
                        DELETE FROM bugfix_env_leases
                        WHERE project_id = ? AND env = ? AND lease_until < ?
                        """, project, environment, now);
                if (PlatformDb.update(em, ACQUIRE_ENV_LEASE_SQL,
                        project, environment, runId, until) == 1) {
                    return true;
                }
                return PlatformDb.update(em, RENEW_ENV_LEASE_SQL,
                        until, project, environment, runId) == 1;
            });
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "环境租约获取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void releaseEnvLease(String projectId, String env, String runId) {
        String project = projectId == null || projectId.isBlank() ? "__unknown__" : projectId;
        String environment = env == null || env.isBlank() ? "test" : env;
        try {
            db.run(em -> PlatformDb.update(em, """
                    DELETE FROM bugfix_env_leases
                    WHERE project_id = ? AND env = ? AND run_id = ?
                    """, project, environment, runId));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "环境租约释放失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void releaseEnvLeases(String runId) {
        try {
            db.run(em -> PlatformDb.update(em,
                    "DELETE FROM bugfix_env_leases WHERE run_id = ?", runId));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "环境租约释放失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 阶段记录

    @Override
    public long startStage(String runId, BugFixStage stage, int attempt, long startedAt) {
        try {
            return db.inTx(em -> PlatformDb.<Number>first(em, INSERT_STAGE_SQL,
                            runId, stage.name(), attempt, BugFixStageRun.RUNNING, startedAt)
                    .map(Number::longValue)
                    .orElse(0L));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "阶段记录创建失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void finishStage(long stageId, String status, String message, String artifact,
                            long endedAt, long durationMs) {
        if (stageId <= 0) {
            return;
        }
        try {
            db.run(em -> PlatformDb.update(em, """
                    UPDATE bugfix_stage_runs
                    SET status = ?, message = ?, artifact = ?, ended_at = ?, duration_ms = ?
                    WHERE id = ?
                    """, status, message, artifact, endedAt, durationMs, stageId));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "阶段记录更新失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void closeOpenStages(String runId, String status, String message, long endedAt) {
        try {
            db.run(em -> PlatformDb.update(em, """
                    UPDATE bugfix_stage_runs
                    SET status = ?, message = ?, ended_at = ?, duration_ms = ? - started_at
                    WHERE run_id = ? AND status = 'RUNNING'
                    """, status, message, endedAt, endedAt, runId));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "阶段记录收尾失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<BugFixStageRun> stageRuns(String runId) {
        String sql = """
                SELECT id, run_id, stage, attempt, status, message, artifact,
                       started_at, ended_at, duration_ms
                FROM bugfix_stage_runs WHERE run_id = ? ORDER BY id
                """;
        try {
            return db.read(em -> PlatformDb.rows(em, sql, runId).stream()
                    .map(row -> new BugFixStageRun(
                            ((Number) row[0]).longValue(),
                            (String) row[1],
                            stage((String) row[2]),
                            ((Number) row[3]).intValue(),
                            (String) row[4],
                            (String) row[5],
                            (String) row[6],
                            ((Number) row[7]).longValue(),
                            ((Number) row[8]).longValue(),
                            ((Number) row[9]).longValue()))
                    .toList());
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "阶段记录查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public int stageAttempts(String runId, BugFixStage stage) {
        try {
            return db.read(em -> (int) PlatformDb.count(em,
                    "SELECT COUNT(*) FROM bugfix_stage_runs WHERE run_id = ? AND stage = ?",
                    runId, stage.name()));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "阶段尝试次数查询失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 通知发件箱

    @Override
    public void enqueue(String runId, String message) {
        if (runId == null || message == null || message.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            db.run(em -> PlatformDb.update(em, """
                    INSERT INTO bugfix_notifications
                        (run_id, message, status, attempts, next_attempt_at, created_at)
                    VALUES (?,?,'PENDING',0,?,?)
                    """, runId, message, now, now));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "通知入队失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<BugFixNotification> due(int limit) {
        String sql = """
                SELECT id, run_id, message, status, attempts, next_attempt_at, last_error,
                       created_at
                FROM bugfix_notifications
                WHERE status = 'PENDING' AND next_attempt_at <= ?
                ORDER BY id LIMIT ?
                """;
        try {
            return db.read(em -> PlatformDb.rows(em, sql,
                            System.currentTimeMillis(), limit <= 0 ? 20 : Math.min(limit, 100))
                    .stream()
                    .map(row -> new BugFixNotification(
                            ((Number) row[0]).longValue(),
                            (String) row[1],
                            (String) row[2],
                            (String) row[3],
                            ((Number) row[4]).intValue(),
                            ((Number) row[5]).longValue(),
                            (String) row[6],
                            ((Number) row[7]).longValue()))
                    .toList());
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "待投递通知查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void markSent(long id) {
        try {
            db.run(em -> PlatformDb.update(em, """
                    UPDATE bugfix_notifications SET status = 'SENT', last_error = ''
                    WHERE id = ?
                    """, id));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "通知状态更新失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void markFailed(long id, String error, int maxAttempts) {
        int attempts;
        try {
            attempts = db.read(em -> (int) PlatformDb.count(em,
                    "SELECT attempts FROM bugfix_notifications WHERE id = ?", id));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "通知重试状态读取失败：" + e.getMessage(), e);
        }
        int next = attempts + 1;
        boolean terminal = next >= (maxAttempts <= 0 ? 5 : maxAttempts);
        long backoff = Math.min(300_000L, (long) Math.pow(2, Math.min(next, 6)) * 5_000L);
        try {
            db.run(em -> PlatformDb.update(em, """
                    UPDATE bugfix_notifications
                    SET status = ?, attempts = ?, last_error = ?, next_attempt_at = ?
                    WHERE id = ?
                    """, terminal ? BugFixNotification.FAILED : BugFixNotification.PENDING,
                    next, error == null ? "" : error, System.currentTimeMillis() + backoff, id));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "通知重试状态更新失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 策略

    @Override
    public BugFixPolicy load() {
        try {
            return db.read(em -> PlatformDb.row(em,
                            "SELECT " + POLICY_COLUMNS + " FROM bugfix_policy WHERE id = 1")
                    .map(SqliteBugFixStore::mapPolicy)
                    .orElseGet(BugFixPolicy::defaults));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "自动修复策略读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public BugFixPolicy load(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return load();
        }
        Optional<BugFixPolicy> policy;
        try {
            policy = db.read(em -> PlatformDb.row(em,
                            "SELECT " + POLICY_COLUMNS + " FROM bugfix_project_policy WHERE project_id = ?",
                            projectId)
                    .map(SqliteBugFixStore::mapPolicy));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "项目自动修复策略读取失败：" + e.getMessage(), e);
        }
        return policy.orElseGet(this::load);
    }

    @Override
    public BugFixPolicy save(BugFixPolicy policy) {
        BugFixPolicy normalized = policy.normalized().withUpdatedAt(System.currentTimeMillis());
        try {
            db.run(em -> PlatformDb.update(em, SAVE_POLICY_SQL,
                    normalized.enabled() ? 1 : 0,
                    normalized.autoDeploy() ? 1 : 0,
                    normalized.autoCreatePr() ? 1 : 0,
                    normalized.testEnv(),
                    normalized.bindingId(),
                    normalized.maxAttempts(),
                    normalized.baseUrl(),
                    normalized.buildCommand(),
                    normalized.testCommand(),
                    normalized.updatedAt()));
            return normalized;
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "自动修复策略保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public BugFixPolicy save(String projectId, BugFixPolicy policy) {
        if (projectId == null || projectId.isBlank()) {
            return save(policy);
        }
        BugFixPolicy normalized = policy.normalized().withUpdatedAt(System.currentTimeMillis());
        try {
            db.run(em -> PlatformDb.update(em, SAVE_PROJECT_POLICY_SQL,
                    projectId,
                    normalized.enabled() ? 1 : 0,
                    normalized.autoDeploy() ? 1 : 0,
                    normalized.autoCreatePr() ? 1 : 0,
                    normalized.testEnv(),
                    normalized.bindingId(),
                    normalized.maxAttempts(),
                    normalized.baseUrl(),
                    normalized.buildCommand(),
                    normalized.testCommand(),
                    normalized.updatedAt()));
            return normalized;
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "项目自动修复策略保存失败：" + e.getMessage(), e);
        }
    }

    private static BugFixPolicy mapPolicy(Object[] row) {
        return new BugFixPolicy(
                ((Number) row[0]).intValue() != 0,
                ((Number) row[1]).intValue() != 0,
                ((Number) row[2]).intValue() != 0,
                (String) row[3],
                (String) row[4],
                ((Number) row[5]).intValue(),
                (String) row[6],
                (String) row[7],
                (String) row[8],
                ((Number) row[9]).longValue());
    }
}
