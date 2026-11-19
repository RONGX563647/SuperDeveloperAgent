package top.rongx.agent.gateway.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.infra.persistence.PlatformDb;
import top.rongx.agent.infra.persistence.config.AgentDbProperties;
import top.rongx.agent.infra.persistence.config.DbBackendConfig;

/**
 * 热切换的真实端到端验证：SQLite → 真 PostgreSQL。
 *
 * <p><b>为什么需要这一层</b>：{@link DatabaseHotSwapperTest} 用 SQLite 覆盖了各条失败
 * 路径，但"探活成功 → Flyway 建 PG schema → 搬行 → 原子替换 → 新后端可读"这条主链路
 * 只有真 PG 能证明。方言、identity 列、{@code ON CONFLICT} 的 PG 语义都在这一段，
 * 纯单测测不到。
 *
 * <p><b>没有 PG 时自动跳过</b>：通过 {@code AGENT_TEST_PG_URL} 提供目标库
 * （如 {@code jdbc:postgresql://127.0.0.1:55432/agent_platform}），
 * 用户名/密码读 {@code AGENT_TEST_PG_USER} / {@code AGENT_TEST_PG_PASSWORD}。
 * CI 与本地默认都不设，于是这组用例 skip 而不是 fail——不让环境依赖把主干卡红。
 *
 * <p><b>为什么用 run_ledger 而不是自建表</b>：搬运器的契约是「只在目标库已有同名表之间
 * 搬行」，目标库缺表时记为"跳过"而非报错（schema 归 Flyway，搬运器不建表）。
 * 自建探针表永远搬不过去，用它断言等于测错对象。
 */
class DatabaseHotSwapperPostgresIT {

    private static final String PG_URL = System.getenv("AGENT_TEST_PG_URL");
    private static final String PG_USER = orDefault(System.getenv("AGENT_TEST_PG_USER"), "agent");
    private static final String PG_PASSWORD =
            orDefault(System.getenv("AGENT_TEST_PG_PASSWORD"), "verify123");

    private static final String PROBE_ID = "it-e2e";

    /**
     * 源 SQLite 放在 {@code java.io.tmpdir} 下的独立目录，<b>不用 {@code @TempDir}</b>。
     *
     * <p>热切换会把当前后端换下，那个被换下的连接池在测试里拿不到引用（{@code swapTo}
     * 的返回值被 swapper 自己消费了）。落在 {@code @TempDir} 里时 JUnit 收尾删目录会撞上
     * 仍在占用的 {@code -wal}，报 {@code Failed to close extension context}——
     * 那会把"功能其实通过了"的用例错判成失败。用固定目录并自行清理更可控。
     */
    private final Path workDir = Path.of(System.getProperty("java.io.tmpdir"),
            "agent-pg-it-" + java.util.UUID.randomUUID());

    private PlatformDb live;

    @AfterEach
    void cleanup() {
        releaseAll();
    }

    @AfterAll
    static void done() {
        PlatformDb.closeAll();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void assumePostgres() {
        Assumptions.assumeTrue(PG_URL != null && !PG_URL.isBlank(),
                "未设置 AGENT_TEST_PG_URL，跳过真 PG 端到端验证");
    }

    /** 造一个带标记数据的 SQLite 源库（用平台自带的 run_ledger 表）。 */
    private PlatformDb sourceSqlite() throws java.io.IOException {
        Files.createDirectories(workDir);
        Path file = workDir.resolve("source.db");
        PlatformDb db = PlatformDb.create("jdbc:sqlite:" + file, "", "");
        db.run(em -> PlatformDb.update(em,
                "INSERT INTO run_ledger (id, kind, project_id, subject_id, owner_user_id,"
                        + " state, summary, error, created_at, updated_at)"
                        + " VALUES ('" + PROBE_ID + "','CHAT','p1','','u1','SUCCEEDED',"
                        + "'e2e 标记','',1,2)"));
        return db;
    }

    private AgentDbProperties sqliteProps() {
        return new AgentDbProperties("", "", "", workDir.resolve("source.db").toString(), 2);
    }

    /**
     * 清掉目标 PG 里的标记行，保证用例可重复执行。
     *
     * <p>首次运行或刚换过库时 {@code run_ledger} 还不存在（schema 要等切换过程里的
     * Flyway 才建出来），此时没有行可清，直接返回即可——不能因此把用例判失败。
     */
    private void clearProbeRow() throws Exception {
        try (Connection connection = DriverManager.getConnection(PG_URL, PG_USER, PG_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM run_ledger WHERE id = '" + PROBE_ID + "'");
        } catch (java.sql.SQLException e) {
            if (!isMissingTable(e)) {
                throw e;
            }
            // 表还没建：没有残留行，无需清理
        }
    }

    /** 目标表不存在（SQLSTATE 42P01/42S02，或驱动只在 message 里体现）。 */
    private static boolean isMissingTable(java.sql.SQLException error) {
        String state = error.getSQLState();
        if (state != null && (state.startsWith("42P01") || state.startsWith("42S02"))) {
            return true;
        }
        String message = error.getMessage();
        return message != null && message.contains("does not exist");
    }

    private long sourceRows() {
        long rows = live.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM run_ledger WHERE id = ?", PROBE_ID));
        return rows;
    }

    @Test
    @DisplayName("真 PG 全链路：探活 → 建 schema → 搬数据 → 替换后端 → 新后端可读")
    void fullSwitchAgainstRealPostgres() throws Exception {
        assumePostgres();
        live = sourceSqlite();
        assertEquals(1L, sourceRows(), "源 SQLite 先要有标记数据");
        clearProbeRow();

        DatabaseHotSwapper.Result result = new DatabaseHotSwapper(live).switchToPostgres(
                sqliteProps(), PG_URL, PG_USER, PG_PASSWORD, "cipher", true);

        assertTrue(result.ok(), result.message());
        assertEquals("POSTGRES", result.toBackend());
        assertEquals(0, result.skippedTables(), "目标库 schema 齐备时不该有表被跳过");

        // 同一 PlatformDb 引用现在读的是 PG：方言与连接都已换过
        assertTrue(live.isPostgres(), "切换后连接池必须是 PG");
        assertEquals(1L, sourceRows(), "搬到 PG 的数据必须能被业务读到");

        // Flyway 建的平台表也要在（证明 schema 阶段真的跑了 PG 版脚本）
        long platformTables = live.read(em -> PlatformDb.count(em,
                "SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE table_schema = current_schema() AND table_name = 'platform_meta'"));
        assertEquals(1L, platformTables, "PG schema 必须由 Flyway 建出");

        // 配置已落盘，重启后仍指向 PG
        Path configFile = DbBackendConfig.file(Path.of(sqliteProps().sqliteFile()));
        assertTrue(Files.exists(configFile), "切换必须写 db-backend.yml，否则重启丢配置");
        assertEquals(PG_URL, DbBackendConfig.read(configFile).get(DbBackendConfig.KEY_URL),
                "配置里的 URL 应是目标 PG");
    }

    @Test
    @DisplayName("已是 PG 时再切：搬数据被拒且不改动现有数据")
    void switchRejectedWhenSourceIsNotSqlite() throws Exception {
        assumePostgres();
        live = sourceSqlite();
        clearProbeRow();
        DatabaseHotSwapper swapper = new DatabaseHotSwapper(live);
        swapper.switchToPostgres(sqliteProps(), PG_URL, PG_USER, PG_PASSWORD, "cipher", true);

        // 现在源已经是 PG：再要求搬数据必须被明确拒绝
        AgentDbProperties pgProps = new AgentDbProperties(PG_URL, PG_USER, PG_PASSWORD, "", 2);
        EnvironmentException error = assertThrows(EnvironmentException.class,
                () -> swapper.switchToPostgres(pgProps, PG_URL, PG_USER, PG_PASSWORD,
                        "cipher", true));
        assertTrue(error.getMessage().contains("不是 SQLite"), error.getMessage());

        assertEquals(1L, sourceRows(), "被拒绝的切换不得改动数据");
    }

    @Test
    @DisplayName("探活失败时不写配置：目标库不存在也绝不留下半套配置")
    void badTargetLeavesNoConfigAgainstRealPostgres() throws Exception {
        assumePostgres();
        live = sourceSqlite();
        AgentDbProperties props = sqliteProps();
        Path configFile = DbBackendConfig.file(Path.of(props.sqliteFile()));
        Files.deleteIfExists(configFile);

        String badUrl = PG_URL.replaceAll("/[^/]*$", "/does_not_exist_db");
        assertThrows(EnvironmentException.class,
                () -> new DatabaseHotSwapper(live).switchToPostgres(
                        props, badUrl, PG_USER, PG_PASSWORD, "cipher", true));

        assertFalse(Files.exists(configFile), "探活失败绝不许写配置");
        assertEquals(1L, sourceRows(), "原 SQLite 后端必须仍可用");
    }

    @Test
    @DisplayName("切回 SQLite：PG 里的数据原样保留")
    void switchBackKeepsPostgresData() throws Exception {
        assumePostgres();
        live = sourceSqlite();
        clearProbeRow();
        DatabaseHotSwapper swapper = new DatabaseHotSwapper(live);
        swapper.switchToPostgres(sqliteProps(), PG_URL, PG_USER, PG_PASSWORD, "cipher", true);

        DatabaseHotSwapper.Result back = swapper.switchToSqlite(sqliteProps());
        assertTrue(back.ok());
        assertFalse(live.isPostgres(), "切回后连接池必须是 SQLite");

        // PG 侧数据没被清。用独立的短连接查，避开 live 已切回 SQLite 的事实
        try (Connection connection = DriverManager.getConnection(PG_URL, PG_USER, PG_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM run_ledger WHERE id = '" + PROBE_ID + "'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "切回 SQLite 不应删除 PG 里的数据");
        }
        // 显式释放两套后端：切换过程中 live 换了 holder，被换下的连接池不再有引用，
        // 不关掉会在 Windows 上锁住临时目录（Failed to close extension context）
        releaseAll();
    }

    /** 关掉 live 当前持有的后端，并清理自管的临时目录。 */
    private void releaseAll() {
        if (live != null) {
            try {
                live.close();
            } catch (RuntimeException ignored) {
                // 已关闭则忽略
            }
            live = null;
        }
        PlatformDb.closeAll();
        deleteQuietly(workDir);
    }

    /** 递归删目录，失败只告警：残留临时文件不该让用例失败。 */
    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // 被占用就留着，交给系统清理
                }
            });
        } catch (java.io.IOException ignored) {
            // 同上
        }
    }
}
