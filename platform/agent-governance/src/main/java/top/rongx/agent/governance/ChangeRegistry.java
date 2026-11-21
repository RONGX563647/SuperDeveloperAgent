package top.rongx.agent.governance;

import top.rongx.agent.domain.workspace.WorkspacePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.change.PlanHasher;
import top.rongx.agent.domain.port.ChangeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.PromotionException;

/**
 * 变更单存储（JPA 持久化）。
 *
 * <p>职责边界：<b>只负责登记与查询</b>。签发确认令牌必须由 {@link ConfirmTokenService}
 * 在"用户已登录的请求链路"中完成，工具无权调用。
 *
 * <p>planHash 保证：{@link #request} 把 imageDigest / frontendDistHash 一并纳入哈希
 * （确认后换 digest 会使令牌失效）；{@link #registerWithHash} 由调用方（动作路径）提供
 * "基于实际配方"的哈希。
 *
 * <p>持久化原因：变更单是"人工确认—执行"的凭证，进程重启后仍必须能查到并核验，
 * 否则确认卡片会凭空消失。落盘位置与其它平台数据一致：{@code <workspace>/_platform/platform.db}。
 */
@Service
public class ChangeRegistry implements ChangeStore {

    private static final String COLUMNS = """
            change_id, project_id, env, kind, plan_hash, summary,
            image_digest, frontend_dist_hash, high_risk, created_at, status
            """;

    private final GovernanceDb db;

    @Autowired
    public ChangeRegistry(GovernanceDb db) {
        this.db = db;
    }

    public ChangeRegistry(@Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this(GovernanceDb.sqlite(platformDbFile(workspaceRoot)));
    }

    /** 独立运行时要保证库文件所在目录存在；schema 仍由 Hibernate 生成。 */
    private static Path platformDbFile(String workspaceRoot) {
        Path dbFile = WorkspacePaths.db(Path.of(workspaceRoot));
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "变更单存储目录创建失败：" + e.getMessage(), e);
        }
        return dbFile;
    }

    @Override
    public ChangePlan request(String projectId, String env, String kind, String summary,
                              String imageDigest, String frontendDistHash, boolean highRisk) {
        String planHash = PlanHasher.hash(projectId, env, kind, imageDigest, frontendDistHash, summary);
        return save(new ChangePlan("chg-" + UUID.randomUUID(), projectId, env, kind, planHash,
                summary, imageDigest, frontendDistHash, highRisk, Instant.now()));
    }

    @Override
    public ChangePlan registerWithHash(String projectId, String env, String kind, String summary,
                                       String planHash, boolean highRisk) {
        return save(new ChangePlan("chg-" + UUID.randomUUID(), projectId, env, kind, planHash,
                summary, null, null, highRisk, Instant.now()));
    }

    private ChangePlan save(ChangePlan plan) {
        String sql = """
                INSERT INTO change_plans
                    (change_id, project_id, env, kind, plan_hash, summary,
                     image_digest, frontend_dist_hash, high_risk, created_at, status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """;
        try {
            db.run(em -> GovernanceDb.update(em, sql,
                    plan.changeId(),
                    plan.projectId(),
                    plan.env(),
                    plan.kind(),
                    plan.planHash(),
                    plan.summary(),
                    plan.imageDigest(),
                    plan.frontendDistHash(),
                    plan.highRisk() ? 1 : 0,
                    plan.createdAt().toEpochMilli(),
                    plan.status().name()));
            return plan;
        } catch (RuntimeException e) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "变更单登记失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ChangePlan> find(String changeId) {
        if (changeId == null || changeId.isBlank()) {
            return Optional.empty();
        }
        try {
            return db.read(em -> GovernanceDb.row(em,
                    "SELECT " + COLUMNS + " FROM change_plans WHERE change_id = ?", changeId)
                    .map(ChangeRegistry::map));
        } catch (RuntimeException e) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "变更单查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ChangePlan> findLatest(String projectId, String kindPrefix) {
        if (projectId == null || projectId.isBlank()
                || kindPrefix == null || kindPrefix.isBlank()) {
            return Optional.empty();
        }
        try {
            return db.read(em -> GovernanceDb.row(em,
                    "SELECT " + COLUMNS + " FROM change_plans"
                            + " WHERE project_id = ? AND kind LIKE ?"
                            + " ORDER BY created_at DESC LIMIT 1",
                    projectId, kindPrefix + "%").map(ChangeRegistry::map));
        } catch (RuntimeException e) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "变更单查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void updateStatus(String changeId, ChangePlan.Status status) {
        if (changeId == null || changeId.isBlank()) {
            return;
        }
        try {
            db.run(em -> GovernanceDb.update(em,
                    "UPDATE change_plans SET status = ? WHERE change_id = ?",
                    (status == null ? ChangePlan.Status.PENDING : status).name(), changeId));
        } catch (RuntimeException e) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "变更单状态更新失败：" + e.getMessage(), e);
        }
    }

    /** 未知/空状态按 PENDING 处理（兼容老库与手工改库）。 */
    private static ChangePlan.Status statusOf(String raw) {
        try {
            return ChangePlan.Status.valueOf(raw == null ? "PENDING" : raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return ChangePlan.Status.PENDING;
        }
    }

    /** 清理早于 cutoff 的变更单（确认卡片只关心近期数据）。返回删除行数。 */
    public int purgeBefore(Instant cutoff) {
        try {
            return db.inTx(em -> GovernanceDb.update(em,
                    "DELETE FROM change_plans WHERE created_at < ?", cutoff.toEpochMilli()));
        } catch (RuntimeException e) {
            throw new PromotionException(ErrorCode.PROMOTION_INVALID_STATE, "变更单清理失败：" + e.getMessage(), e);
        }
    }

    private static ChangePlan map(Object[] row) {
        return new ChangePlan(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[4],
                (String) row[5],
                (String) row[6],
                (String) row[7],
                ((Number) row[8]).intValue() == 1,
                Instant.ofEpochMilli(((Number) row[9]).longValue()),
                statusOf((String) row[10]));
    }
}
