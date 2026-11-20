package top.rongx.agent.infra.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.infra.persistence.PlatformDb;

/** BUG-37：审计落库 append-only——可 INSERT、可按 target 追溯、无 UPDATE/DELETE 路径。 */
class DbAuditTrailTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        PlatformDb.closeAll();
    }

    @Test
    @DisplayName("记录可落库并按 target_id 读回")
    void recordsAndReadsBack() {
        PlatformDb db = PlatformDb.sqlite(tempDir.resolve("audit.db"));
        db.run(em -> PlatformDb.update(em, """
                CREATE TABLE IF NOT EXISTS audit_event (
                    id bigint NOT NULL PRIMARY KEY,
                    ts bigint NOT NULL,
                    actor text NOT NULL,
                    role text,
                    action text NOT NULL,
                    target_type text NOT NULL,
                    target_id text,
                    project_id text,
                    env text,
                    result text,
                    detail_json text
                )
                """));
        DbAuditTrail audit = new DbAuditTrail(db);

        audit.record("devops", "devops", "change:confirm", "change_plan",
                "chg-1", "RY", "test", "OK", "stack_deploy");
        audit.record("viewer", "viewer", "settings:reveal", "env_var",
                "DB_PASSWORD", null, "prod", "DENIED", "无 settings:reveal 权限");

        // SQLite 方言下原生查询的行元素类型不稳定，改用单列查询断言
        String row = db.read(em -> PlatformDb.<String>first(em,
                "SELECT actor || '|' || action || '|' || target_type || '|' || target_id || '|' "
                        + "|| project_id || '|' || env || '|' || result || '|' || detail_json "
                        + "FROM audit_event WHERE target_id = ? ORDER BY ts", "chg-1")).orElse("");
        assertEquals("devops|change:confirm|change_plan|chg-1|RY|test|OK|stack_deploy", row);

        String denied = db.read(em -> PlatformDb.<String>first(em,
                "SELECT result FROM audit_event WHERE action = ?", "settings:reveal")).orElse("");
        assertEquals("DENIED", denied);
    }

    @Test
    @DisplayName("null 字段按 SQL NULL 绑定；记录条数只增不减（append-only 语义）")
    void appendsOnly() {
        PlatformDb db = PlatformDb.sqlite(tempDir.resolve("audit2.db"));
        db.run(em -> PlatformDb.update(em, """
                CREATE TABLE IF NOT EXISTS audit_event (
                    id bigint NOT NULL PRIMARY KEY,
                    ts bigint NOT NULL,
                    actor text NOT NULL,
                    role text,
                    action text NOT NULL,
                    target_type text NOT NULL,
                    target_id text,
                    project_id text,
                    env text,
                    result text,
                    detail_json text
                )
                """));
        DbAuditTrail audit = new DbAuditTrail(db);
        audit.record("devops", null, "change:apply", "change_plan", "chg-2", "RY", "test", "OK", null);
        audit.record("devops", null, "change:apply", "change_plan", "chg-2", "RY", "test", "FAILED", "boom");

        long count = db.read(em -> PlatformDb.count(em, "SELECT COUNT(*) FROM audit_event"));
        assertEquals(2, count, "两次 record 应产生两行，append-only");

        String failed = db.read(em -> PlatformDb.<String>first(em,
                "SELECT detail_json FROM audit_event WHERE result = ?", "FAILED")).orElse("");
        assertEquals("boom", failed);
    }
}
