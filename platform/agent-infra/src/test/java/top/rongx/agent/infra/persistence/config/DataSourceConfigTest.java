package top.rongx.agent.infra.persistence.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 装配层契约：数据源可建可用，且<b>启动链路不再夹带旧库导入</b>。
 *
 * <p>历史事故：旧库导入曾用 {@code @Bean(initMethod = "run")} 挂在启动装配上，
 * 每次启动静默导入并打日志。那条需求已取消，{@code LegacySqliteImporter} 整体删除；
 * 这里断言装配面只剩数据源与不改数据的 WAL checkpoint。
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
        AgentDbProperties props = new AgentDbProperties("", "", "", db.toString(), 2);

        javax.sql.DataSource dataSource = new DataSourceConfig().dataSource(props);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE smoke (id TEXT PRIMARY KEY)");
            statement.execute("INSERT INTO smoke (id) VALUES ('ok')");
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM smoke")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) == 1);
            }
        } finally {
            ((com.zaxxer.hikari.HikariDataSource) dataSource).close();
        }

        assertTrue(Files.isRegularFile(db));
    }

    @Test
    @DisplayName("装配面不含旧库导入：相关方法已从 DataSourceConfig 删除")
    void noLegacyImporterOnConfig() {
        boolean present = java.util.Arrays.stream(DataSourceConfig.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().toLowerCase(java.util.Locale.ROOT).contains("legacy"));
        org.junit.jupiter.api.Assertions.assertFalse(present,
                "旧库导入需求已取消，DataSourceConfig 不得再装配 LegacySqliteImporter");
    }

    @Test
    @DisplayName("PlatformDb 支持热切换：换后端后同一引用读到新库")
    void platformDbSupportsHotSwap() throws Exception {
        Path first = tempDir.resolve("first.db");
        Path second = tempDir.resolve("second.db");

        // 用 create 而非 sqlite：后者会进全局 STANDALONE 注册表，closeAll 会二次关闭
        PlatformDb shared = PlatformDb.create("jdbc:sqlite:" + first, "", "");
        try {
            shared.run(em -> PlatformDb.update(em,
                    "CREATE TABLE swap_probe (id TEXT PRIMARY KEY)"));
            shared.run(em -> PlatformDb.update(em,
                    "INSERT INTO swap_probe (id) VALUES ('first')"));

            PlatformDb next = PlatformDb.create("jdbc:sqlite:" + second, "", "");
            next.run(em -> PlatformDb.update(em,
                    "CREATE TABLE swap_probe (id TEXT PRIMARY KEY)"));

            PlatformDb previous = shared.swapTo(next);

            // 同一引用现在读的是第二个库：表在但没数据
            long rows = shared.read(em -> PlatformDb.count(em, "SELECT COUNT(*) FROM swap_probe"));
            assertEquals(0L, rows, "切换后同一 PlatformDb 引用必须读新后端");

            // swapTo 必须把"被换下的第一套"以独立对象交回来：
            // 若返回 this，调用方 close 会把刚切上去的新后端关掉（经典自噬 bug）。
            org.junit.jupiter.api.Assertions.assertNotNull(previous,
                    "首次切换要把换下的自持后端交回，供调用方关闭");
            // 旧后端此刻仍可用（在途请求不会炸），关掉后才会释放文件句柄
            long oldRows = previous.read(em ->
                    PlatformDb.count(em, "SELECT COUNT(*) FROM swap_probe"));
            assertEquals(1L, oldRows, "换下的旧后端应仍可读，直到显式关闭");
            previous.close();

            // 关的是旧库：新后端必须还活着
            long stillNew = shared.read(em -> PlatformDb.count(em, "SELECT COUNT(*) FROM swap_probe"));
            assertEquals(0L, stillNew, "关闭旧后端不得影响当前生效的新后端");
        } finally {
            shared.close();
        }
    }
}
