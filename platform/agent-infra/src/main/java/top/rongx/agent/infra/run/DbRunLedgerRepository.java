package top.rongx.agent.infra.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;
import top.rongx.agent.domain.run.RunKind;
import top.rongx.agent.domain.run.RunLedgerRepository;
import top.rongx.agent.domain.run.RunRecord;
import top.rongx.agent.domain.run.RunState;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 统一运行账本的 JPA 实现。
 *
 * <p>表结构由 {@code RunLedgerEntity} 生成；连接、事务与 SQLite/PostgreSQL 方言
 * 由 Hibernate 负责。表结构刻意保持扁平：只存跨引擎可比的生命周期字段，
 * 不复制各编排的细节状态。
 */
@Repository
public class DbRunLedgerRepository implements RunLedgerRepository {

    private static final String COLUMNS = """
            id, kind, project_id, subject_id, owner_user_id, state, summary, error,
            created_at, updated_at
            """;

    private static final String SAVE_SQL = """
            INSERT INTO run_ledger (id, kind, project_id, subject_id, owner_user_id,
                                    state, summary, error, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                state = excluded.state,
                summary = excluded.summary,
                error = excluded.error,
                updated_at = excluded.updated_at
            """;

    private final PlatformDb db;

    @Autowired
    public DbRunLedgerRepository(PlatformDb db) {
        this.db = db;
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public DbRunLedgerRepository(String workspaceRoot) {
        this(PlatformDb.sqlite(Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.db")));
    }

    @Override
    public void save(RunRecord record) {
        try {
            db.run(em -> PlatformDb.update(em, SAVE_SQL,
                    record.id(),
                    record.kind().name(),
                    record.projectId(),
                    record.subjectId() == null ? "" : record.subjectId(),
                    record.ownerUserId() == null ? "" : record.ownerUserId(),
                    record.state().name(),
                    record.summary() == null ? "" : record.summary(),
                    record.error() == null ? "" : record.error(),
                    record.createdAt(),
                    record.updatedAt()));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行账本写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<RunRecord> find(String id) {
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + COLUMNS + " FROM run_ledger WHERE id = ?", id).map(DbRunLedgerRepository::map));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行账本读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<RunRecord> byProject(String projectId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM run_ledger WHERE project_id = ?"
                + " ORDER BY updated_at DESC LIMIT ?";
        try {
            return db.read(em -> PlatformDb.rows(em, sql, projectId,
                    Math.max(1, Math.min(limit, 200))).stream().map(DbRunLedgerRepository::map).toList());
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行账本查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<RunRecord> active() {
        String sql = "SELECT " + COLUMNS + " FROM run_ledger WHERE state IN ('QUEUED', 'RUNNING')"
                + " ORDER BY updated_at DESC";
        try {
            return db.read(em -> PlatformDb.rows(em, sql).stream()
                    .map(DbRunLedgerRepository::map).toList());
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行账本查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public int markStaleRunning(long staleBefore, String reason) {
        String sql = """
                UPDATE run_ledger SET state = 'FAILED', error = ?, summary = ?, updated_at = ?
                WHERE state = 'RUNNING' AND updated_at < ?
                """;
        try {
            return db.inTx(em -> PlatformDb.update(em, sql,
                    reason, "运行中断：" + reason, System.currentTimeMillis(), staleBefore));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行账本恢复失败：" + e.getMessage(), e);
        }
    }

    @Override
    public int purgeTerminalBefore(long updatedBefore) {
        // 只删终态记录；QUEUED/RUNNING/BLOCKED 可能还在推进，留给恢复任务处理
        String sql = """
                DELETE FROM run_ledger
                WHERE state NOT IN ('QUEUED', 'RUNNING', 'BLOCKED') AND updated_at < ?
                """;
        try {
            return db.inTx(em -> PlatformDb.update(em, sql, updatedBefore));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行账本清理失败：" + e.getMessage(), e);
        }
    }

    @Override
    public int deleteBySubject(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            return 0;
        }
        try {
            return db.inTx(em -> PlatformDb.update(em,
                    "DELETE FROM run_ledger WHERE subject_id = ?", subjectId));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行账本删除失败：" + e.getMessage(), e);
        }
    }

    private static RunRecord map(Object[] row) {
        return new RunRecord(
                (String) row[0],
                RunKind.valueOf((String) row[1]),
                (String) row[2],
                (String) row[3],
                (String) row[4],
                RunState.valueOf((String) row[5]),
                (String) row[6],
                (String) row[7],
                ((Number) row[8]).longValue(),
                ((Number) row[9]).longValue());
    }
}
