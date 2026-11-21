package top.rongx.agent.gateway.web;

import top.rongx.agent.domain.error.Errors;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static top.rongx.agent.gateway.web.RequestBodies.str;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.gateway.settings.DatabaseHotSwapper;
import top.rongx.agent.gateway.settings.SqliteToPostgresMigrator;
import top.rongx.agent.infra.persistence.PlatformDb;
import top.rongx.agent.infra.persistence.config.AgentDbProperties;
import top.rongx.agent.infra.persistence.config.DbBackendConfig;
import top.rongx.agent.infra.security.SecretBox;

/**
 * 设置页「数据库」分区：查看当前后端、测试 PostgreSQL 连通性、<b>热切换</b>后端。
 *
 * <p><b>热切换</b>：{@link DatabaseHotSwapper} 会探活 → 建 schema → 搬数据 → 落配置 →
 * 原子替换 {@link PlatformDb} 的内部后端，全程不重启进程。切换成功后本页立即显示
 * 新后端；失败时旧后端保持可用，配置也不会被改动。
 *
 * <p><b>安全</b>：密码只写不读（状态接口只回 {@code hasPassword}）；落盘前用
 * {@link SecretBox} AES-256-GCM 加密；搬数据是只读源 + 幂等目标，可重复执行。
 */
@RestController
@RequestMapping("/api/v1/settings/database")
public class DatabaseSettingsController {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSettingsController.class);

    private final AgentDbProperties properties;
    private final SecretBox secretBox;
    private final DatabaseHotSwapper swapper;
    private final SqliteToPostgresMigrator migrator;
    private final PlatformDb db;
    private final CurrentUser currentUser;

    public DatabaseSettingsController(AgentDbProperties properties, SecretBox secretBox,
                                      DatabaseHotSwapper swapper, SqliteToPostgresMigrator migrator,
                                      PlatformDb db, CurrentUser currentUser) {
        this.properties = properties;
        this.secretBox = secretBox;
        this.swapper = swapper;
        this.migrator = migrator;
        this.db = db;
        this.currentUser = currentUser;
    }

    /**
     * 当前数据库后端与配置状态。
     *
     * @return backend（实际生效，取自连接池）、jdbcUrl（已脱敏）、配置里保存的目标等
     */
    @GetMapping
    public Map<String, Object> status() {
        currentUser.requireSettingsRead();
        Map<String, String> saved = DbBackendConfig.read(configFile());
        Map<String, Object> out = new LinkedHashMap<>();
        // backend 取自实际连接池而不是配置：热切换之后配置仍是启动时那套，只有连接池知道真相
        out.put("backend", db.isPostgres() ? "POSTGRES" : "SQLITE");
        out.put("configuredBackend", properties.backend().name());
        out.put("jdbcUrl", maskUrl(properties.jdbcUrl()));
        out.put("sqliteFile", properties.sqliteFile());
        out.put("poolSize", properties.poolSize());
        out.put("configFile", configFile().toString());
        out.put("savedUrl", saved.getOrDefault(DbBackendConfig.KEY_URL, ""));
        out.put("savedUsername", saved.getOrDefault(DbBackendConfig.KEY_USERNAME, ""));
        out.put("savedTarget", !saved.getOrDefault(DbBackendConfig.KEY_URL, "").isBlank());
        out.put("hasPassword", !saved.getOrDefault(DbBackendConfig.KEY_PASSWORD_CIPHER, "").isBlank());
        out.put("migratable", !db.isPostgres());
        out.put("hotReload", true);
        return out;
    }

    /**
     * 测试目标 PostgreSQL 连通性（切换前验证）。
     *
     * @param body url / username / password；password 留空时沿用已保存的密文
     * @return ok、latencyMs、serverVersion、message
     */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody Map<String, Object> body) {
        currentUser.requireSettingsWrite();
        String url = requirePostgresUrl(body);
        String username = str(body, "username");
        String password = resolvePassword(body);
        long start = System.nanoTime();
        Map<String, Object> out = new LinkedHashMap<>();
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                url, username, password)) {
            String version = connection.getMetaData().getDatabaseProductVersion();
            out.put("ok", true);
            out.put("serverVersion", version);
            out.put("message", "连接正常");
            log.info("PostgreSQL 连通性测试通过：{}", maskUrl(url));
        } catch (Exception e) {
            // 连接失败必须回成"可读原因"而不是 500：用户在填表阶段就要看到哪一项不对
            out.put("ok", false);
            out.put("serverVersion", null);
            out.put("message", "连接失败：" + Errors.rootMessage(e));
        }
        out.put("latencyMs", (System.nanoTime() - start) / 1_000_000L);
        return out;
    }

    /**
     * 热切换到目标 PostgreSQL（可选顺带搬运当前 SQLite 数据）。
     *
     * <p>切换成功后无需重启：连接池与 EMF 已原子替换，后续请求立即走新库。
     *
     * @param body url / username / password（留空沿用密文）/ migrate（是否搬数据，默认 true）
     * @return ok、message、fromBackend/toBackend、copiedRows、elapsedMs、restartRequired=false
     */
    @PostMapping("/switch")
    public Map<String, Object> switchToPostgres(@RequestBody Map<String, Object> body) {
        currentUser.requireSettingsWrite();
        String url = requirePostgresUrl(body);
        String username = str(body, "username");
        String password = resolvePassword(body);
        boolean migrate = !"false".equalsIgnoreCase(str(body, "migrate"));

        SecretBox.Sealed sealed = secretBox.encrypt(password == null ? "" : password);
        DatabaseHotSwapper.Result result = swapper.switchToPostgres(
                properties, url, username, password, sealed.cipherB64(), migrate);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("message", result.message());
        out.put("fromBackend", result.fromBackend());
        out.put("toBackend", result.toBackend());
        out.put("target", result.target());
        out.put("copiedRows", result.copiedRows());
        out.put("skippedTables", result.skippedTables());
        out.put("elapsedMs", result.elapsedMs());
        out.put("restartRequired", false);
        return out;
    }

    /**
     * 一键迁移：只搬数据、不切换后端（先把数据搬过去，稍后再决定何时切）。
     *
     * @param body url / username / password（留空沿用密文）
     * @return ok、message、copiedRows、tables、skippedTables、details[]、elapsedMs
     */
    @PostMapping("/migrate")
    public Map<String, Object> migrate(@RequestBody Map<String, Object> body) {
        currentUser.requireSettingsWrite();
        String url = requirePostgresUrl(body);
        String username = str(body, "username");
        String password = resolvePassword(body);
        // 用 switchToPostgres 的数据搬运阶段，但不切换：migrate=true + 立即回切代价太大，
        // 因此这里保留"只搬不切"的语义——切不切由 /switch 决定。
        top.rongx.agent.gateway.settings.SqliteToPostgresMigrator.Result result =
                migrator.migrate(properties, url, username, password);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("message", result.message());
        out.put("source", maskUrl(result.source()));
        out.put("target", maskUrl(result.target()));
        out.put("tables", result.tables());
        out.put("copiedRows", result.copiedRows());
        out.put("skippedTables", result.skippedTables());
        out.put("details", result.details());
        out.put("elapsedMs", result.elapsedMs());
        return out;
    }

    /**
     * 保存数据库后端配置（仅落盘，不切换）。
     *
     * <p>用于"我先配置好，等合适的时间再切"。日常直接调 {@code /switch} 即可，
     * 它会把落盘一起做掉。
     *
     * @param body url / username / password
     * @return ok、configFile、message
     */
    @PostMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        currentUser.requireSettingsWrite();
        String url = requirePostgresUrl(body);
        String username = str(body, "username");
        String password = resolvePassword(body);
        SecretBox.Sealed sealed = secretBox.encrypt(password == null ? "" : password);
        DbBackendConfig.write(configFile(), url, username, sealed.cipherB64(), sealed.ivB64());
        log.info("数据库后端配置已保存（未切换）：{}", maskUrl(url));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("configFile", configFile().toString());
        out.put("message", "配置已保存；点「热切换」立即生效，或重启后生效");
        return out;
    }

    /**
     * 热切换回 SQLite（PostgreSQL 里的数据原样保留，不清空）。
     *
     * @return ok、message、fromBackend/toBackend、elapsedMs
     */
    @DeleteMapping
    public Map<String, Object> switchToSqlite() {
        currentUser.requireSettingsWrite();
        DatabaseHotSwapper.Result result = swapper.switchToSqlite(properties);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("message", result.message());
        out.put("fromBackend", result.fromBackend());
        out.put("toBackend", result.toBackend());
        out.put("elapsedMs", result.elapsedMs());
        out.put("restartRequired", false);
        return out;
    }

    // ---------------------------------------------------------------- 内部

    private Path configFile() {
        return DbBackendConfig.file(Path.of(properties.sqliteFile()));
    }

    private static String requirePostgresUrl(Map<String, Object> body) {
        String url = str(body, "url");
        if (url == null || url.isBlank()) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "url 必填");
        }
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT,
                    "url 必须是 jdbc:postgresql: 开头");
        }
        return url.trim();
    }

    /**
     * 取密码：显式传了新密码就用新的；否则读已保存的密文解密。
     *
     * <p>这样"只改用户名"或"再切一次"不必每次重输密码，同时保证明文不回流前端。
     */
    private String resolvePassword(Map<String, Object> body) {
        String password = str(body, "password");
        if (password != null && !password.isEmpty()) {
            return password;
        }
        Map<String, String> saved = DbBackendConfig.read(configFile());
        String cipher = saved.get(DbBackendConfig.KEY_PASSWORD_CIPHER);
        String iv = saved.get(DbBackendConfig.KEY_PASSWORD_IV);
        if (cipher == null || iv == null || cipher.isBlank() || iv.isBlank()) {
            return "";
        }
        return secretBox.decrypt(cipher, iv);
    }

    /** 脱敏 JDBC URL：去掉可能被写进查询参数的口令字段。 */
    private static String maskUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("(?i)(password|user)=[^&;]*", "$1=****");
    }


}
