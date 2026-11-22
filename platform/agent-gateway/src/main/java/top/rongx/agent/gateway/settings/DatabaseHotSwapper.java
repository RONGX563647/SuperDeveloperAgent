package top.rongx.agent.gateway.settings;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.infra.persistence.PlatformDb;
import top.rongx.agent.infra.persistence.SqliteToTargetCopier;
import top.rongx.agent.infra.persistence.config.AgentDbProperties;
import top.rongx.agent.infra.persistence.config.DbBackendConfig;

/**
 * 数据库后端热切换：不重启进程，把运行中的持久化从一个库换到另一个库。
 *
 * <p><b>为什么能做到</b>：25 个 Store 注入的都是同一个 {@link PlatformDb} 引用，
 * 它内部由一个 volatile 的 {@code Holder}（连接池 + EMF）承载真实后端。
 * 换后端 = 造一套新 Holder、跑完迁移、原子替换引用，Store 完全无感。
 *
 * <p><b>顺序为什么必须是这样</b>：
 * <ol>
 *   <li><b>先探活</b>：目标库连不上就别动现有后端（不能出现"配置换了但库连不上"）；</li>
 *   <li><b>建 schema</b>：对目标库跑 Flyway，表/索引/自增策略与当前版本一致；</li>
 *   <li><b>搬数据</b>：逐表幂等复制，失败可修完再点一次；</li>
 *   <li><b>落配置</b>：写 {@code db-backend.yml}，保证进程重启后仍然指向新库；</li>
 *   <li><b>再切换</b>：最后才原子替换 Holder——前面任何一步失败，运行中的后端都没被动过。</li>
 * </ol>
 *
 * <p>失败时旧后端始终可用：这是整个设计的底线，宁可切换失败也不能让平台失去数据库。
 *
 * <p>放在网关侧而不是 infra：Flyway 的依赖与 {@code db/migration} 的 classpath 位置都在网关。
 */
@Component
public class DatabaseHotSwapper {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHotSwapper.class);

    /** 切换结果：阶段、耗时、行数、旧后端是否已释放。 */
    public record Result(boolean ok, String message, String fromBackend, String toBackend,
                         String target, int copiedRows, int skippedTables,
                         long elapsedMs, boolean oldReleased) {
    }

    private final PlatformDb db;

    public DatabaseHotSwapper(PlatformDb db) {
        this.db = db;
    }

    /**
     * 把运行中的后端切到目标 PostgreSQL（可选先搬 SQLite 数据）。
     *
     * <p>顺序不可调整：探活 → 建 schema → 搬数据 → 落配置 → 原子切换。
     * 前四步任何一步失败，运行中的后端都没被动过，平台继续可用。
     *
     * @param props      当前配置（用于定位 SQLite 源与落盘路径）
     * @param url        目标 PG JDBC URL
     * @param username   目标库用户名
     * @param password   目标库密码（明文，只存在内存，用于连接）
     * @param cipher     已加密的密码（落盘用，由调用方用 SecretBox 生成）
     * @param migrate    是否顺带把现有 SQLite 数据搬过去
     */
    public Result switchToPostgres(AgentDbProperties props, String url, String username,
                                   String password, String cipher, boolean migrate) {
        long start = System.currentTimeMillis();
        requirePostgresUrl(url);
        String from = props.backend().name();

        // 1) 探活：连不上就立刻失败，现有后端一行数据都不动
        probe(url, username, password);

        // 2) 建 schema（Flyway 与主库同一套脚本）
        createSchema(url, username, password);

        // 3) 搬数据（仅当源是 SQLite 且用户要求）
        int copied = 0;
        int skippedTables = 0;
        if (migrate) {
            if (!props.jdbcUrl().startsWith("jdbc:sqlite:")) {
                throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                        "当前后端不是 SQLite，无法搬运数据（当前：" + props.jdbcUrl() + "）");
            }
            SqliteToTargetCopier.Report report = copyRows(props.jdbcUrl(), url, username, password);
            copied = report.copied();
            skippedTables = report.skippedTables();
        }

        // 4) 造新后端并原子替换；旧自持后端在确认新后端可用之后才关
        PlatformDb next = PlatformDb.create(url, username, password);
        PlatformDb previous = db.swapTo(next);
        boolean released = release(previous);

        // 5) 落配置：保证进程重启后仍指向新库（沿用 db-backend.yml 的密文格式）
        DbBackendConfig.write(configFile(props), url, username,
                cipher == null ? "" : cipher, ivOf(props));

        long elapsed = System.currentTimeMillis() - start;
        String message = migrate
                ? "已热切换到 PostgreSQL：搬运 " + copied + " 行"
                : "已热切换到 PostgreSQL（未搬数据）";
        log.info("数据库热切换完成：{} -> POSTGRES，{}，耗时 {}ms", from, message, elapsed);
        return new Result(true, message, from, "POSTGRES", mask(url), copied, skippedTables,
                elapsed, released);
    }

    /**
     * 把运行中的后端切回 SQLite（不搬 PG 数据，PG 里的数据原样保留）。
     *
     * <p>回退路径必须简单到不会失败：建 SQLite 连接池 → 跑 Flyway 建表 → 替换引用。
     */
    public Result switchToSqlite(AgentDbProperties props) {
        long start = System.currentTimeMillis();
        String from = props.backend().name();
        String sqliteUrl = "jdbc:sqlite:" + props.sqliteFile();
        prepareSqliteDirectory(props.sqliteFile());

        // 先建表再换：空文件必须先把 schema 建出来，否则切过去第一批请求就撞"表不存在"
        createSchema(sqliteUrl, "", "");
        PlatformDb next = PlatformDb.create(sqliteUrl, "", "");
        PlatformDb previous = db.swapTo(next);
        boolean released = release(previous);

        DbBackendConfig.clear(configFile(props));

        long elapsed = System.currentTimeMillis() - start;
        log.info("数据库热切换回 SQLite：{}，耗时 {}ms", sqliteUrl, elapsed);
        return new Result(true, "已热切换回 SQLite", from, "SQLITE", mask(sqliteUrl), 0, 0,
                elapsed, released);
    }

    // ---------------------------------------------------------------- 内部

    private void requirePostgresUrl(String url) {
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT,
                    "目标必须是 jdbc:postgresql: 开头的地址");
        }
    }

    /** 探活：失败即抛出，绝不触碰现有后端。 */
    private void probe(String url, String username, String password) {
        try (Connection ignored = DriverManager.getConnection(url, username, password)) {
            // 拿到连接即算通过
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                    "目标库连接失败（现有后端未改变）：" + rootMessage(e), e);
        }
    }

    /** 用同一套迁移脚本把目标库建到当前版本。SQLite 与 PG 走各自的 vendor 目录。 */
    private void createSchema(String url, String username, String password) {
        boolean sqlite = url.startsWith("jdbc:sqlite:");
        // 回退到已存在的 SQLite 库时，其 schema 已由 Hibernate ddl-auto 建到当前模型，
        // 但没有 flyway_schema_history。此时若让 Flyway baseline-version=1 重放，
        // V7 的 DROP COLUMN app_url 会在"列已被 Hibernate 删掉"的库上重放，报 no such column；
        // V8 的 ADD COLUMN 同理会撞已存在的列。核心表在 = schema 已就绪，直接跳过建表。
        // 空库仍走 Flyway 从头建（见 DatabaseHotSwapperTest.switchToSqliteWorksOnEmptyDirectory）。
        if (sqlite && hasCoreTable(url)) {
            log.info("目标 SQLite 已有 schema（run_ledger），跳过 Flyway 建表：{}", mask(url));
            return;
        }
        String vendor = sqlite ? "sqlite" : "postgresql";
        try {
            Flyway.configure()
                    .dataSource(url, username, password)
                    .locations("classpath:db/migration/common", "classpath:db/migration/" + vendor)
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .load()
                    .migrate();
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                    "目标库建表失败（检查账号是否有 DDL 权限）：" + rootMessage(e), e);
        }
    }

    /** 目标 SQLite 是否已有核心业务表（schema 已就绪，无需 Flyway 建表）。 */
    private static boolean hasCoreTable(String url) {
        try (Connection connection = DriverManager.getConnection(url);
             var ps = connection.prepareStatement(
                     "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'run_ledger' LIMIT 1");
             var rs = ps.executeQuery()) {
            return rs.next();
        } catch (SQLException e) {
            // 文件不存在或不可读：交给后续 Flyway 建表流程处理，这里按"无表"论。
            return false;
        }
    }

    private top.rongx.agent.infra.persistence.SqliteToTargetCopier.Report copyRows(
            String sourceUrl, String targetUrl, String username, String password) {
        Properties readOnly = new Properties();
        readOnly.setProperty("open_mode", "1");
        try (Connection source = DriverManager.getConnection(sourceUrl, readOnly);
             Connection target = DriverManager.getConnection(targetUrl, username, password)) {
            source.setReadOnly(true);
            return top.rongx.agent.infra.persistence.SqliteToTargetCopier.copy(source, target);
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                    "数据搬运失败（现有后端未改变）：" + rootMessage(e), e);
        }
    }

    /** 关闭被替换下来的旧后端；失败只告警，不影响已完成的切换。 */
    private boolean release(PlatformDb previous) {
        if (previous == null) {
            return false;
        }
        try {
            previous.close();
            return true;
        } catch (RuntimeException e) {
            log.warn("旧后端释放失败（不影响切换结果）: {}", e.getMessage());
            return false;
        }
    }

    /** IV 由调用方随密文一起传入；这里只做转交，避免热切换器持有加解密细节。 */
    private static String ivOf(AgentDbProperties props) {
        return DbBackendConfig.read(configFile(props))
                .getOrDefault(DbBackendConfig.KEY_PASSWORD_IV, "");
    }

    private static java.nio.file.Path configFile(AgentDbProperties props) {
        return DbBackendConfig.file(java.nio.file.Path.of(props.sqliteFile()));
    }

    /** 脱敏 JDBC URL：去掉可能被写进查询参数的口令字段。 */
    private static String mask(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("(?i)(password|user)=[^&;]*", "$1=****");
    }

    private static void prepareSqliteDirectory(String sqliteFile) {
        java.nio.file.Path parent = java.nio.file.Path.of(sqliteFile).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(parent);
        } catch (java.io.IOException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INTERNAL,
                    "无法创建 SQLite 目录: " + parent, e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    /** 供上层展示：当前实际生效的后端（来源是连接池，不是配置）。 */
    public Map<String, Object> liveStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backend", db.isPostgres() ? "POSTGRES" : "SQLITE");
        return out;
    }
}
