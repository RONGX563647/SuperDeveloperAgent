package top.rongx.agent.infra.persistence.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import top.rongx.agent.infra.persistence.LegacySqliteImporter;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 装配层回归：旧库导入<b>不得</b>挂在启动链路上。
 *
 * <p>历史事故：{@code @Bean(initMethod = "run")} 让每次启动都静默导入旧库并在日志里
 * 打印"新增 N 行"，用户既没点过也没预期。这里把"启动不写数据"钉成断言：
 * 一是 Bean 上没有 initMethod；二是把旧库文件放在目录里、只创建 Bean 不调用方法，
 * 统一库必须一行不多、一个完成标记都不写。
 */
class DataSourceConfigTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        PlatformDb.closeAll();
    }

    @Test
    @DisplayName("SQLite 模式自动创建目录并返回可用连接")
    void sqliteCreatesDatabaseFile() throws Exception {
        Path db = tempDir.resolve("nested/platform.db");
        AgentDbProperties props = new AgentDbProperties("", "", "", db.toString(), 2, false);

        javax.sql.DataSource dataSource = new DataSourceConfig().dataSource(props);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE smoke (id TEXT PRIMARY KEY)");
            statement.execute("INSERT INTO smoke (id) VALUES ('ok')");
            try (java.sql.ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM smoke")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) == 1);
            }
        } finally {
            ((com.zaxxer.hikari.HikariDataSource) dataSource).close();
        }

        assertTrue(java.nio.file.Files.isRegularFile(db));
    }

    @Test
    @DisplayName("旧库导入 Bean 没有 initMethod：启动不会自动导入")
    void legacyImporterHasNoInitMethod() throws Exception {
        Method factory = DataSourceConfig.class.getMethod("legacySqliteImporter",
                PlatformDb.class, AgentDbProperties.class);
        Bean bean = factory.getAnnotation(Bean.class);

        assertTrue(bean != null, "legacySqliteImporter 必须仍是 Bean（供设置页注入）");
        assertEquals("", bean.initMethod(),
                "不得声明 initMethod：旧库导入只能由设置页显式触发，不能在启动时执行");
    }

    @Test
    @DisplayName("只装配 Bean 不调用方法：统一库不新增任何行与标记")
    void wiringAloneImportsNothing() throws Exception {
        // 目录里放一个真实旧库，模拟"用户机器上历史库还在"的现场。
        // 用 run_ledger：它是已映射的实体表，统一库里必然存在，断言才有意义。
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("governance.db"));
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE run_ledger (id TEXT PRIMARY KEY, kind TEXT NOT NULL,
                        project_id TEXT NOT NULL, subject_id TEXT NOT NULL, owner_user_id TEXT NOT NULL,
                        state TEXT NOT NULL, summary TEXT NOT NULL, error TEXT NOT NULL,
                        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
                    """);
            statement.execute("""
                    INSERT INTO run_ledger VALUES ('legacy-1','CHAT','p1','','u1','SUCCEEDED','旧库记录','',1,2)
                    """);
        }
        Path platformFile = tempDir.resolve("platform.db");
        AgentDbProperties props = new AgentDbProperties("", "", "",
                platformFile.toString(), 2, true);

        PlatformDb db = PlatformDb.sqlite(platformFile);
        DataSourceConfig config = new DataSourceConfig();
        // 装配 = Spring 创建 Bean 的全部动作；只要没有 initMethod，就不会调 importAll
        LegacySqliteImporter importer = config.legacySqliteImporter(db, props);
        assertTrue(importer != null);

        long legacyRows = db.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM run_ledger WHERE id = 'legacy-1'"));
        assertEquals(0L, legacyRows, "仅装配 Bean 不得导入旧库数据");
        long markers = db.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM platform_meta WHERE meta_key LIKE 'legacy-import:%'"));
        assertEquals(0L, markers, "仅装配 Bean 不得写完成标记");

        // 巡检同样是只读的：页面上先看状态不该产生副作用
        LegacySqliteImporter.Inspection inspection = importer.inspect();
        assertTrue(inspection.pending(), "旧库存在且未导入时应提示可导入");
        assertFalse(inspection.files().isEmpty());
        long stillEmpty = db.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM run_ledger WHERE id = 'legacy-1'"));
        assertEquals(0L, stillEmpty, "巡检后仍不得有数据");
    }
}
