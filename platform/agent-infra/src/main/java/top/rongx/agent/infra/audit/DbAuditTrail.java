package top.rongx.agent.infra.audit;

import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.port.AuditTrail;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 审计落库实现（BUG-37）：写 audit_event 表，append-only。
 *
 * <p>只 INSERT；不提供任何 UPDATE/DELETE。审计与治理清理解耦：
 * {@code GovernanceCleanupJob} 清 change_plans/code_patches/run_ledger 时本表不受影响，
 * 仍可按 target_id 追溯"谁确认了什么"。
 */
@Repository
public class DbAuditTrail implements AuditTrail {

    private final PlatformDb db;

    public DbAuditTrail(PlatformDb db) {
        this.db = db;
    }

    @Override
    public void record(String actor, String role, String action, String targetType,
                       String targetId, String projectId, String env, String result, String detail) {
        db.run(em -> PlatformDb.update(em, """
                INSERT INTO audit_event
                    (id, ts, actor, role, action, target_type, target_id, project_id, env, result, detail_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                nextId(), System.currentTimeMillis(),
                actor == null ? "unknown" : actor,
                role,
                action == null ? "unknown" : action,
                targetType == null ? "unknown" : targetType,
                targetId, projectId, env, result, detail));
    }

    /** 时间戳毫秒 + 4 位随机，SQLite/PG 通用（V5 迁移用 bigint 主键，不用自增序列）。 */
    private static long nextId() {
        return System.currentTimeMillis() * 10_000L
                + (long) (Math.random() * 10_000L);
    }
}
