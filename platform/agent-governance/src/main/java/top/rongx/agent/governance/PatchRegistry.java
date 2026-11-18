package top.rongx.agent.governance;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.patch.CodePatch;
import top.rongx.agent.domain.port.PatchStore;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;

/**
 * 待确认补丁存储（JPA 持久化）。
 *
 * <p>为什么必须落库：补丁是「人工确认 → 应用」的凭证，进程重启后确认卡片仍要能取回
 * 文件全文与 diff，否则用户点击确认只会得到 patch 不存在。落盘位置与变更单一致：
 * {@code <workspace>/_platform/platform.db}。
 *
 * <p>文件改动拆到子表而不是塞 JSON：补丁内容可能含引号/换行/超大文件，关系表更稳，
 * 也方便将来按 path 审计。保存采用「先删后插」的整单替换，保证重复 save 幂等。
 */
@Service
public class PatchRegistry implements PatchStore {

    private static final String COLUMNS = """
            patch_id, project_id, env, base_sha, branch, diff, diff_hash,
            added, removed, created_at
            """;

    private final GovernanceDb db;

    @Autowired
    public PatchRegistry(GovernanceDb db) {
        this.db = db;
    }

    public PatchRegistry(@Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this(GovernanceDb.sqlite(platformDbFile(workspaceRoot)));
    }

    /** 独立运行时要保证库文件所在目录存在；schema 仍由 Hibernate 生成。 */
    private static Path platformDbFile(String workspaceRoot) {
        Path dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.db");
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "补丁存储目录创建失败：" + e.getMessage(), e);
        }
        return dbFile;
    }

    @Override
    public void save(CodePatch patch) {
        String upsert = """
                INSERT INTO code_patches
                    (patch_id, project_id, env, base_sha, branch, diff, diff_hash,
                     added, removed, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(patch_id) DO UPDATE SET
                    project_id = excluded.project_id,
                    env = excluded.env,
                    base_sha = excluded.base_sha,
                    branch = excluded.branch,
                    diff = excluded.diff,
                    diff_hash = excluded.diff_hash,
                    added = excluded.added,
                    removed = excluded.removed,
                    created_at = excluded.created_at
                """;
        try {
            db.run(em -> {
                GovernanceDb.update(em, "DELETE FROM code_patch_edits WHERE patch_id = ?", patch.patchId());
                GovernanceDb.update(em, upsert,
                        patch.patchId(),
                        patch.projectId(),
                        patch.env(),
                        patch.baseSha(),
                        patch.branch(),
                        patch.diff(),
                        patch.diffHash(),
                        patch.added(),
                        patch.removed(),
                        patch.createdAt());
                int seq = 0;
                for (CodePatch.FileEdit file : patch.edits()) {
                    GovernanceDb.update(em, """
                            INSERT INTO code_patch_edits (patch_id, seq, path, content)
                            VALUES (?,?,?,?)
                            """, patch.patchId(), seq++, file.path(), file.content());
                }
            });
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "补丁保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<CodePatch> find(String patchId) {
        if (patchId == null || patchId.isBlank()) {
            return Optional.empty();
        }
        try {
            return db.read(em -> GovernanceDb.row(em,
                            "SELECT " + COLUMNS + " FROM code_patches WHERE patch_id = ?", patchId)
                    .map(row -> new CodePatch(
                            patchId,
                            (String) row[1],
                            (String) row[2],
                            (String) row[3],
                            (String) row[4],
                            edits(em, patchId),
                            (String) row[5],
                            (String) row[6],
                            ((Number) row[7]).intValue(),
                            ((Number) row[8]).intValue(),
                            ((Number) row[9]).longValue())));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "补丁查询失败：" + e.getMessage(), e);
        }
    }

    private static List<CodePatch.FileEdit> edits(EntityManager em, String patchId) {
        List<CodePatch.FileEdit> edits = new ArrayList<>();
        for (Object[] row : GovernanceDb.rows(em, """
                SELECT path, content FROM code_patch_edits
                WHERE patch_id = ? ORDER BY seq
                """, patchId)) {
            edits.add(new CodePatch.FileEdit((String) row[0], (String) row[1]));
        }
        return edits;
    }

    @Override
    public void remove(String patchId) {
        try {
            db.run(em -> {
                GovernanceDb.update(em, "DELETE FROM code_patch_edits WHERE patch_id = ?", patchId);
                GovernanceDb.update(em, "DELETE FROM code_patches WHERE patch_id = ?", patchId);
            });
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "补丁删除失败：" + e.getMessage(), e);
        }
    }

    /** 清理早于 cutoff 的补丁（确认卡片只关心近期数据）。返回删除行数。 */
    public int purgeBefore(Instant cutoff) {
        try {
            return db.inTx(em -> {
                long millis = cutoff.toEpochMilli();
                GovernanceDb.update(em, """
                        DELETE FROM code_patch_edits WHERE patch_id IN
                            (SELECT patch_id FROM code_patches WHERE created_at < ?)
                        """, millis);
                return GovernanceDb.update(em,
                        "DELETE FROM code_patches WHERE created_at < ?", millis);
            });
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "补丁清理失败：" + e.getMessage(), e);
        }
    }
}
