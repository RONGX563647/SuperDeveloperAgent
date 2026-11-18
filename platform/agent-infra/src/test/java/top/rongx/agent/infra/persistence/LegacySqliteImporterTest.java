package top.rongx.agent.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.infra.persistence.config.AgentDbProperties;

/**
 * 旧版多文件 SQLite 向统一库的导入：只在显式调用时执行，启动链路不碰数据。
 */
class LegacySqliteImporterTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        PlatformDb.closeAll();
    }

    private PlatformDb platform() {
        return PlatformDb.sqlite(tempDir.resolve("platform.db"));
    }

    private AgentDbProperties props() {
        return new AgentDbProperties("", "", "", tempDir.resolve("platform.db").toString(), 2, true);
    }

    /** 造一个只含 run_ledger 的旧库文件。 */
    private void writeLegacy(String fileName, String id, String summary) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve(fileName));
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE run_ledger (id TEXT PRIMARY KEY, kind TEXT NOT NULL,
                        project_id TEXT NOT NULL, subject_id TEXT NOT NULL, owner_user_id TEXT NOT NULL,
                        state TEXT NOT NULL, summary TEXT NOT NULL, error TEXT NOT NULL,
                        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
                    """);
            statement.execute("INSERT INTO run_ledger VALUES ('" + id
                    + "','CHAT','p1','','u1','SUCCEEDED','" + summary + "','',1,2)");
        }
    }

    @Test
    @DisplayName("巡检只读：不导入任何行、不写完成标记")
    void inspectIsReadOnly() throws Exception {
        writeLegacy("run_ledger.db", "r-inspect", "巡检不该导入");
        PlatformDb db = platform();

        LegacySqliteImporter.Inspection inspection =
                new LegacySqliteImporter(db, props()).inspect();

        assertTrue(inspection.pending(), "存在未导入的旧库文件时应报告 pending");
        assertEquals("SQLITE", inspection.backend());
        assertEquals(tempDir.toAbsolutePath().normalize(), inspection.directory());
        assertEquals(1, inspection.files().size());
        LegacySqliteImporter.FileState state = inspection.files().get(0);
        assertEquals("run_ledger.db", state.file());
        assertFalse(state.imported());
        assertEquals(0, state.importedRows());
        assertTrue(state.sizeBytes() > 0);

        long rows = db.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM run_ledger WHERE id = 'r-inspect'"));
        assertEquals(0, rows, "巡检不得导入数据");
        long markers = db.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM platform_meta WHERE meta_key LIKE 'legacy-import:%'"));
        assertEquals(0, markers, "巡检不得写完成标记");
    }

    @Test
    @DisplayName("旧库数据停留在 WAL 时也能被导入并落盘（数据丢失恢复路径）")
    void importsRowsStillInWal() throws Exception {
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
                    INSERT INTO run_ledger VALUES ('r-wal','CHAT','p1','','u1','SUCCEEDED','WAL里的记录','',1,2)
                    """);
        }

        PlatformDb db = platform();
        LegacySqliteImporter.Report report =
                new LegacySqliteImporter(db, props()).importAll(false);

        assertEquals(1, report.imported(), "应导入 1 个文件");
        assertEquals(0, report.failed());
        long count = db.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM run_ledger WHERE id = 'r-wal' AND summary = 'WAL里的记录'"));
        assertEquals(1, count, "停留在旧库 WAL 的数据应被导入统一库");
    }

    @Test
    @DisplayName("重复执行幂等：第二次导入跳过已标记文件且不产生重复行")
    void importsLegacyRowsIdempotently() throws Exception {
        writeLegacy("run_ledger.db", "r1", "旧记录");
        PlatformDb db = platform();
        LegacySqliteImporter importer = new LegacySqliteImporter(db, props());

        LegacySqliteImporter.Report first = importer.importAll(false);
        LegacySqliteImporter.Report second = importer.importAll(false);

        assertEquals(1, first.imported());
        assertEquals(0, second.imported(), "已有完成标记的文件应被跳过");
        assertEquals(1, second.skipped());
        long count = db.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM run_ledger WHERE id = 'r1' AND summary = '旧记录'"));
        assertEquals(1, count);
    }

    @Test
    @DisplayName("导入完成后写入 platform_meta 标记，巡检能看到已导入行数")
    void writesCompletionMarker() throws Exception {
        writeLegacy("run_ledger.db", "r-m", "标记前");
        PlatformDb db = platform();
        LegacySqliteImporter importer = new LegacySqliteImporter(db, props());

        importer.importAll(false);
        long marker = db.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM platform_meta WHERE meta_key = 'legacy-import:run_ledger.db'"));
        assertEquals(1, marker, "导入完成后应写入 platform_meta 完成标记");

        LegacySqliteImporter.Inspection inspection = importer.inspect();
        assertFalse(inspection.pending(), "全部导入后不应再有 pending");
        assertTrue(inspection.files().get(0).imported());
        assertEquals(1, inspection.files().get(0).importedRows(), "标记里应记下导入行数");

        // 标记之后旧库继续新增数据：不应再被导入（一次性语义）
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("run_ledger.db"));
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO run_ledger VALUES ('r-m2','CHAT','p1','','u1','SUCCEEDED','标记后','',3,4)
                    """);
        }
        importer.importAll(false);
        long after = db.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM run_ledger WHERE id = 'r-m2'"));
        assertEquals(0, after, "已有完成标记的旧库不应被再次导入");
    }

    @Test
    @DisplayName("force 重放：忽略标记但幂等，不产生重复行")
    void forceReplaysIdempotently() throws Exception {
        writeLegacy("run_ledger.db", "r-force", "重放");
        PlatformDb db = platform();
        LegacySqliteImporter importer = new LegacySqliteImporter(db, props());

        importer.importAll(false);
        LegacySqliteImporter.Report forced = importer.importAll(true);

        assertEquals(1, forced.imported(), "force 应忽略完成标记重放");
        assertEquals(0, forced.skipped());
        long count = db.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM run_ledger WHERE id = 'r-force'"));
        assertEquals(1, count, "重放不得产生重复行");
    }

    @Test
    @DisplayName("目录里没有任何旧库文件时返回空报告，不抛异常（页面与启动都不会因此失败）")
    void missingLegacyFilesYieldEmptyReport() throws Exception {
        // 目录存在（统一库要能建），但里面没有 8 个历史分域库
        Path dir = tempDir.resolve("empty-legacy");
        java.nio.file.Files.createDirectories(dir);
        AgentDbProperties props = new AgentDbProperties("", "", "",
                dir.resolve("platform.db").toString(), 2, true);
        PlatformDb db = PlatformDb.sqlite(dir.resolve("platform.db"));

        LegacySqliteImporter.Inspection inspection = new LegacySqliteImporter(db, props).inspect();
        LegacySqliteImporter.Report report = new LegacySqliteImporter(db, props).importAll(false);

        assertTrue(inspection.files().isEmpty());
        assertFalse(inspection.pending());
        assertEquals(0, report.copied());
        assertEquals(0, report.failed());
        assertEquals(0, report.imported());
    }

    @Test
    @DisplayName("旧库目录整体不存在时返回空报告，不抛异常")
    void missingDirectoryYieldsEmptyReport() {
        AgentDbProperties props = new AgentDbProperties("", "", "",
                tempDir.resolve("platform.db").toString(), 2, true);
        PlatformDb db = PlatformDb.sqlite(tempDir.resolve("platform.db"));
        LegacySqliteImporter importer = new LegacySqliteImporter(db, props);

        LegacySqliteImporter.Inspection inspection = importer.inspect();
        LegacySqliteImporter.Report report = importer.importAll(false);

        assertTrue(inspection.files().isEmpty(), "无旧库文件时应返回空列表");
        assertFalse(inspection.pending());
        assertEquals(0, report.failed());
    }
}
