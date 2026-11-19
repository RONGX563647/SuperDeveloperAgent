package top.rongx.agent.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 双库内核：EMF 建表、事务、原生查询与 null 参数绑定。 */
class PlatformDbTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        PlatformDb.closeAll();
    }

    @Test
    @DisplayName("SQLite 下按实体建表并可读写")
    void createsSchemaAndReadsBack() {
        PlatformDb db = PlatformDb.sqlite(tempDir.resolve("platform.db"));
        db.run(em -> PlatformDb.update(em, """
                INSERT INTO run_ledger (id, kind, project_id, subject_id, owner_user_id,
                                        state, summary, error, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, "run-1", "CHAT", "p1", "", "u1", "RUNNING", "执行中", "", 1L, 2L));

        List<Object[]> rows = db.read(em -> PlatformDb.rows(em,
                "SELECT id, state, summary FROM run_ledger WHERE id = ?", "run-1"));
        assertEquals(1, rows.size());
        assertEquals("RUNNING", rows.get(0)[1]);
    }

    @Test
    @DisplayName("null 参数按 SQL NULL 绑定，不会触发类型推断失败")
    void bindsNullParameters() {
        PlatformDb db = PlatformDb.sqlite(tempDir.resolve("nulls.db"));
        db.run(em -> PlatformDb.update(em, """
                INSERT INTO project_envs (project_id, env, app_url, compose_dir) VALUES (?,?,?,?)
                """, "p1", "test", null, null));

        long count = db.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM project_envs WHERE project_id = ? AND app_url IS NULL", "p1"));
        assertEquals(1, count);
    }

    @Test
    @DisplayName("自增主键表可插入并回读")
    void supportsIdentityColumns() {
        PlatformDb db = PlatformDb.sqlite(tempDir.resolve("identity.db"));
        db.run(em -> PlatformDb.update(em, """
                INSERT INTO token_usage (session_id, project_id, user_id, connection_id, model_name,
                                         input_tokens, cached_tokens, output_tokens, total_tokens, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, "s1", "p1", "u1", "c1", "m1", 1, 0, 2, 3, 4L));

        long count = db.read(em -> PlatformDb.count(em, "SELECT COUNT(*) FROM token_usage"));
        assertEquals(1, count);
        assertTrue(db.entityManagerFactory().isOpen());
    }

    @Test
    @DisplayName("事务回滚后不留下半条记录")
    void rollsBackOnFailure() {
        PlatformDb db = PlatformDb.sqlite(tempDir.resolve("tx.db"));
        try {
            db.run((EntityManager em) -> {
                PlatformDb.update(em, """
                        INSERT INTO project_envs (project_id, env, app_url, compose_dir) VALUES (?,?,?,?)
                        """, "p1", "test", "", "");
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // 预期异常
        }
        long count = db.read(em -> PlatformDb.count(em, "SELECT COUNT(*) FROM project_envs"));
        assertEquals(0, count);
    }
}
