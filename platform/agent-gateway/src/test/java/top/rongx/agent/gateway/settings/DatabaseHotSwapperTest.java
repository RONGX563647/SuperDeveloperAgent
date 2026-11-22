package top.rongx.agent.gateway.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.infra.persistence.PlatformDb;
import top.rongx.agent.infra.persistence.config.AgentDbProperties;
import top.rongx.agent.infra.persistence.config.DbBackendConfig;

/**
 * 热切换编排：探活 → 建 schema → 搬数据 → 落配置 → 原子替换。
 *
 * <p><b>这组用例守的是「失败不破坏现状」</b>：热切换最大的风险不是切不过去，
 * 而是切到一半把运行中的后端弄坏——那会让整个平台失去数据库。因此每条失败路径
 * 都要断言：原后端仍可读写、配置文件没有被动过。
 *
 * <p>不连真实 PostgreSQL：成功路径需要外部 PG 实例，由手工联调覆盖；
 * 这里覆盖前四步的拒绝行为与纯 SQLite 回退路径。
 *
 * <p><b>为什么初始后端用内存库</b>：切换会把当前后端换下，那个被换下的连接池在测试里
 * 拿不到引用，落在临时文件上会一直占着 {@code .db-wal}，Windows 上表现为
 * {@code Failed to close extension context}。内存库没有文件句柄，正合适承载
 * 「验证原数据还在」这类断言。
 */
class DatabaseHotSwapperTest {

    @TempDir
    Path tempDir;

    /** 本用例创建的 PlatformDb：收尾时统一关闭，避免泄漏连接池。 */
    private final List<PlatformDb> opened = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (PlatformDb db : opened) {
            try {
                db.close();
            } catch (RuntimeException ignored) {
                // 已关闭则忽略
            }
        }
        opened.clear();
        PlatformDb.closeAll();
    }

    /** 创建并登记，确保收尾一定被关闭。 */
    private PlatformDb open(String jdbcUrl) {
        PlatformDb db = PlatformDb.create(jdbcUrl, "", "");
        opened.add(db);
        return db;
    }

    /** 起一个可用的后端，并写入一行标记数据用于验证"没被动过"。 */
    private PlatformDb liveBackend() {
        PlatformDb db = open("jdbc:sqlite::memory:");
        db.run(em -> PlatformDb.update(em, "CREATE TABLE probe (id TEXT PRIMARY KEY)"));
        db.run(em -> PlatformDb.update(em, "INSERT INTO probe (id) VALUES ('alive')"));
        return db;
    }

    private AgentDbProperties props() {
        return new AgentDbProperties("", "", "", tempDir.resolve("live.db").toString(), 2);
    }

    private long probeRows(PlatformDb db) {
        long rows = db.read(em -> PlatformDb.count(em, "SELECT COUNT(*) FROM probe"));
        return rows;
    }

    private static long countTable(PlatformDb db, String table) {
        long rows = db.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name = ?", table));
        return rows;
    }

    @Test
    @DisplayName("目标 URL 不是 PostgreSQL：直接拒绝，现有后端不受影响")
    void rejectsNonPostgresTarget() {
        PlatformDb live = liveBackend();
        DatabaseHotSwapper swapper = new DatabaseHotSwapper(live);
        AgentDbProperties props = props();

        EnvironmentException error = assertThrows(EnvironmentException.class,
                () -> swapper.switchToPostgres(props, "jdbc:mysql://host/db",
                        "u", "p", "cipher", false));
        assertTrue(error.getMessage().contains("jdbc:postgresql:"));
        assertEquals(1L, probeRows(live), "拒绝后原后端必须仍可读");

        assertThrows(EnvironmentException.class,
                () -> swapper.switchToPostgres(props, null, "u", "p", "cipher", false));
    }

    @Test
    @DisplayName("探活失败：不建表、不落配置、不换后端")
    void probeFailureLeavesBackendUntouched() {
        PlatformDb live = liveBackend();
        DatabaseHotSwapper swapper = new DatabaseHotSwapper(live);
        AgentDbProperties props = props();

        // 指向必然连不上的端口：探活失败要抛错，且什么都还没做
        EnvironmentException error = assertThrows(EnvironmentException.class,
                () -> swapper.switchToPostgres(props, "jdbc:postgresql://127.0.0.1:1/none",
                        "nobody", "wrong", "cipher", true));

        assertTrue(error.getMessage().contains("现有后端未改变"), error.getMessage());
        assertEquals(1L, probeRows(live), "探活失败后原后端必须仍可读");

        // 配置也不能被写：否则重启后就会指向一个连不上的库
        Path configFile = DbBackendConfig.file(Path.of(props.sqliteFile()));
        assertFalse(Files.exists(configFile),
                "探活失败时不得写 db-backend.yml，否则重启会指向不可用目标");
    }

    @Test
    @DisplayName("切回 SQLite：建表、替换后端、清掉配置")
    void switchToSqliteBuildsSchemaAndClearsConfig() {
        AgentDbProperties props = props();
        Path configFile = DbBackendConfig.file(Path.of(props.sqliteFile()));
        // 先造"保存过 PG 配置"的状态，验证切回时会清掉
        DbBackendConfig.write(configFile, "jdbc:postgresql://host:5432/db", "u", "cipher", "iv");
        assertTrue(Files.exists(configFile));

        PlatformDb live = liveBackend();
        DatabaseHotSwapper swapper = new DatabaseHotSwapper(live);

        DatabaseHotSwapper.Result result = swapper.switchToSqlite(props);

        assertTrue(result.ok());
        assertEquals("SQLITE", result.toBackend());
        assertTrue(result.target().startsWith("jdbc:sqlite:"));

        // 切回之后同一引用读的是 SQLite，且 Flyway 已把平台表建出来
        assertEquals(1L, countTable(live, "platform_meta"),
                "切回时必须先把 schema 建好，否则首批请求撞表不存在");

        assertFalse(Files.exists(configFile), "切回 SQLite 后应删掉 db-backend.yml");
    }

    @Test
    @DisplayName("切回 SQLite 不依赖既有库：空目录也能建出可用后端")
    void switchToSqliteWorksOnEmptyDirectory() {
        Path fresh = tempDir.resolve("nested/fresh.db");
        AgentDbProperties props = new AgentDbProperties("", "", "", fresh.toString(), 2);
        PlatformDb live = liveBackend();

        DatabaseHotSwapper.Result result = new DatabaseHotSwapper(live).switchToSqlite(props);

        assertTrue(result.ok());
        assertTrue(Files.isRegularFile(fresh), "目标 SQLite 文件应被创建");
        assertEquals(1L, countTable(live, "run_ledger"), "空目录切回时 Flyway 必须建出业务表");
    }

    @Test
    @DisplayName("切回已有 schema 的 SQLite：不重放 Flyway 历史迁移（V7 drop app_url）")
    void switchToSqliteSkipsFlywayWhenSchemaExists() throws Exception {
        // 模拟"平台原本的 SQLite 库"：Hibernate 建的是最终模型——project_envs 已无 app_url 列，
        // 且没有 flyway_schema_history。旧实现会让 Flyway baseline-version=1 重放 V7 的
        // DROP COLUMN app_url，在无该列的库上报 no such column；这里断言不再重放。
        Path existing = tempDir.resolve("existing-schema.db");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + existing);
             var st = c.createStatement()) {
            st.execute("CREATE TABLE run_ledger (id TEXT PRIMARY KEY)");
            st.execute("CREATE TABLE project_envs (env TEXT NOT NULL, project_id TEXT NOT NULL,"
                    + " compose_dir TEXT, PRIMARY KEY (env, project_id))");
        }
        AgentDbProperties props = new AgentDbProperties("", "", "", existing.toString(), 2);
        PlatformDb live = liveBackend();

        DatabaseHotSwapper.Result result = new DatabaseHotSwapper(live).switchToSqlite(props);

        assertTrue(result.ok(), result.message());
        assertEquals("SQLITE", result.toBackend());
        // schema 未被破坏：核心表仍在，且没有因为重放迁移报错
        assertEquals(1L, countTable(live, "run_ledger"));
        assertEquals(1L, countTable(live, "project_envs"));
    }

    @Test
    @DisplayName("liveStatus 报告的是连接池真相，不是配置")
    void liveStatusReadsFromPool() {
        PlatformDb live = liveBackend();
        DatabaseHotSwapper swapper = new DatabaseHotSwapper(live);

        assertEquals("SQLITE", swapper.liveStatus().get("backend"));
    }

    @Test
    @DisplayName("首次切换：swapTo 交回被换下的旧后端，供调用方关闭")
    void swapReturnsPreviousBackend() {
        PlatformDb live = liveBackend();
        PlatformDb next = open("jdbc:sqlite::memory:");

        PlatformDb previous = live.swapTo(next);

        assertNotNull(previous, "首次切换必须把旧的自持后端交回来，否则连接池泄漏");
        // 关掉旧后端之后，新后端必须仍然可用（不能自噬：返回 this 就会关掉新库）
        previous.close();
        // 新后端是干净的：没有 probe 表（旧库才有），用它证明读到的是新库
        assertEquals(0L, countTable(live, "probe"), "换到的是干净后端，不该有 probe 表");
        // 新后端本身仍可执行查询（没被误关）。注意 hbm2ddl.auto=update 会顺手建业务表，
        // 所以这里不能断言"零表"，只验证查询通路是通的。
        assertTrue(countTable(live, "run_ledger") >= 0L, "新后端必须仍可执行查询");
    }
}
