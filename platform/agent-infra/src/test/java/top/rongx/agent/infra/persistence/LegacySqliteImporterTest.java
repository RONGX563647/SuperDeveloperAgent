package top.rongx.agent.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.infra.persistence.config.AgentDbProperties;

/** 旧版多文件 SQLite 向统一库的一次性导入。 */
class LegacySqliteImporterTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        PlatformDb.closeAll();
    }

    @Test
    @DisplayName("导入旧库行且重复执行幂等")
    void importsLegacyRowsIdempotently() throws Exception {
        Path platform = tempDir.resolve("platform.db");
        Path legacy = tempDir.resolve("run_ledger.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + legacy);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE run_ledger (id TEXT PRIMARY KEY, kind TEXT NOT NULL,
                        project_id TEXT NOT NULL, subject_id TEXT NOT NULL, owner_user_id TEXT NOT NULL,
                        state TEXT NOT NULL, summary TEXT NOT NULL, error TEXT NOT NULL,
                        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
                    """);
            statement.execute("""
                    INSERT INTO run_ledger VALUES ('r1','CHAT','p1','','u1','RUNNING','旧记录','',1,2)
                    """);
        }

        PlatformDb db = PlatformDb.sqlite(platform);
        AgentDbProperties properties = new AgentDbProperties("", "", "",
                platform.toString(), 2, true);
        new LegacySqliteImporter(db, properties).run();
        new LegacySqliteImporter(db, properties).run();

        long count = db.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM run_ledger WHERE id = 'r1' AND summary = '旧记录'"));
        assertEquals(1, count);
    }
}
