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

    /**
     * 用同一套迁移脚本把目标库建到当前版本。SQLite 与 PG 走各自的 vendor 目录。
     *
     * <p>「非空库但缺 flyway_schema_history」的处理：自持后端（{@link PlatformDb#create}）
     * 由 Hibernate {@code hbm2ddl.auto=update} 直接把实体建/补成最新列——那套库<b>没有
     * Flyway 版本记录</b>。切回它时若按 baseline=1 补历史脚本，V5 的
     * {@code ALTER TABLE ADD COLUMN} 会对已存在的列重放而报 duplicate column。
     * 这类库的 schema 实际已在最新版本，把 baseline 直接打到当前最高迁移版本，
     * 让 Flyway 只补记录、不重放脚本。
     */
    private void createSchema(String url, String username, String password) {
        boolean sqlite = url.startsWith("jdbc:sqlite:");
        String vendor = sqlite ? "sqlite" : "postgresql";
        try {
            Flyway.configure()
                    .dataSource(url, username, password)
                    .locations("classpath:db/migration/common", "classpath:db/migration/" + vendor)
                    .baselineOnMigrate(true)
                    .baselineVersion(latestMigrationVersion(vendor))
                    .load()
                    .migrate();
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                    "目标库建表失败（检查账号是否有 DDL 权限）：" + rootMessage(e), e);
        }
    }

    /**
     * classpath 里该 vendor 相关迁移的最高版本号（common + vendor 两处目录）。
     *
     * <p>空库场景：没有任何用户表 → baseline-on-migrate 不生效，从 V1 全量建到最新，
     * 语义不变。已有表场景：无论表是老版本脚本建的还是 Hibernate update 补出来的，
     * 都视为已到最新——历史脚本不可安全重放（V2 自增表、V5 加列都会撞已有结构），
     * 缺列由 Hibernate update 安全网兜底（与 {@code DataSourceConfig} 的约定一致）。
     *
     * <p>资源可能在目录（本地 IDE / 解包运行）也可能在 agent-infra 的 jar 里
     * （Maven 依赖 classpath，CI 就是这种），两种协议都要扫。
     */
    private static String latestMigrationVersion(String vendor) {
        try {
            ClassLoader loader = DatabaseHotSwapper.class.getClassLoader();
            int latest = scanMigrationVersions(loader.getResources("db/migration/common"));
            latest = Math.max(latest,
                    scanMigrationVersions(loader.getResources("db/migration/" + vendor)));
            // 扫不到（理论不可达）按 1 兜底：等价于修复前的行为
            return String.valueOf(Math.max(latest, 1));
        } catch (java.io.IOException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INTERNAL,
                    "扫描迁移脚本版本失败：" + rootMessage(e), e);
        }
    }

    /**
     * 扫一个 classpath 根（目录或 jar 内目录）下所有 {@code V&lt;n&gt;__*.sql} 的最大 n。
     *
     * <p>失败只告警不抛：单个根扫不进去时其余根（common 目录）通常仍能给出可用版本；
     * 全部失败由调用方兜底到 1。
     */
    private static int scanMigrationVersions(java.util.Enumeration<java.net.URL> roots) {
        int latest = 0;
        while (roots.hasMoreElements()) {
            java.net.URL root = roots.nextElement();
            String protocol = root.getProtocol();
            if ("file".equalsIgnoreCase(protocol)) {
                latest = Math.max(latest, scanDirectoryQuietly(root));
            } else if ("jar".equalsIgnoreCase(protocol)) {
                latest = Math.max(latest, scanJarQuietly(root));
            }
        }
        return latest;
    }

    private static int scanDirectoryQuietly(java.net.URL root) {
        try (var paths = java.nio.file.Files.list(java.nio.file.Path.of(root.toURI()))) {
            int max = 0;
            for (java.nio.file.Path p : paths.toList()) {
                max = Math.max(max, migrationVersionOf(p.getFileName().toString()));
            }
            return max;
        } catch (java.io.IOException | java.net.URISyntaxException | RuntimeException e) {
            log.warn("扫描迁移目录失败（{}）：{}", root, e.getMessage());
            return 0;
        }
    }

    private static int scanJarQuietly(java.net.URL root) {
        String url = root.toString();
        int bang = url.lastIndexOf("!/");
        if (bang < 0) {
            return 0;
        }
        String entryPrefix = url.substring(bang + 2);
        String jarPath = url.substring("jar:".length(), bang);
        if (jarPath.startsWith("file:")) {
            jarPath = java.nio.file.Path.of(java.net.URI.create(jarPath)).toString();
        }
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath)) {
            int max = 0;
            for (java.util.jar.JarEntry entry : java.util.Collections.list(jar.entries())) {
                String name = entry.getName();
                if (!name.startsWith(entryPrefix + "/")) {
                    continue;
                }
                String rest = name.substring(entryPrefix.length() + 1);
                if (!rest.isEmpty() && !rest.contains("/")) {
                    max = Math.max(max, migrationVersionOf(rest));
                }
            }
            return max;
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("扫描迁移 jar 失败（{}）：{}", jarPath, e.getMessage());
            return 0;
        }
    }

    /** {@code V9__worker_role_and_trigger.sql} → 9；不匹配返回 0。 */
    private static int migrationVersionOf(String fileName) {
        var matcher = java.util.regex.Pattern.compile("^V(\\d+)__").matcher(fileName);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
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
