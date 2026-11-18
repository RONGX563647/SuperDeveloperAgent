package top.rongx.agent.gateway.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import top.rongx.agent.gateway.settings.SqliteToPostgresMigrator;
import top.rongx.agent.infra.persistence.LegacySqliteImporter;
import top.rongx.agent.infra.persistence.config.AgentDbProperties;
import top.rongx.agent.infra.persistence.config.DbBackendConfig;
import top.rongx.agent.infra.security.SecretBox;

/**
 * 设置页「数据库」分区：查看当前后端、测试 PostgreSQL 连通性、一键迁移 SQLite 数据、
 * 切换后端，以及<b>显式触发</b>旧版多文件 SQLite 的导入。
 *
 * <p><b>切换为什么需要重启</b>：连接池与方言在进程启动时按 {@code agent.db.*} 装配，
 * 运行中改值不会重建。这里只负责把配置加密落盘（{@code db-backend.yml}），
 * 下次启动由 {@code AgentApplication} 注入生效——返回 {@code restartRequired=true} 让前端明说，
 * 避免用户以为"点完就已经在用 PG 了"。
 *
 * <p><b>旧库导入为什么在这里</b>：改造前的 8 个分域 {@code .db} 曾由启动链路自动导入，
 * 但启动期静默写数据既无预期也不可选；现在启动只做只读巡检，导入必须由用户在本页
 * 点「开始导入」——可先看清源文件大小与上次导入行数，再决定是否执行。
 *
 * <p><b>安全</b>：密码只写不读（状态接口只回 {@code hasPassword}）；落盘前用 {@link SecretBox}
 * AES-256-GCM 加密；迁移是只读源 + 幂等目标，可重复执行。
 */
@RestController
@RequestMapping("/api/v1/settings/database")
public class DatabaseSettingsController {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSettingsController.class);

    private final AgentDbProperties properties;
    private final SecretBox secretBox;
    private final SqliteToPostgresMigrator migrator;
    private final LegacySqliteImporter legacyImporter;
    private final CurrentUser currentUser;

    public DatabaseSettingsController(AgentDbProperties properties, SecretBox secretBox,
                                      SqliteToPostgresMigrator migrator,
                                      LegacySqliteImporter legacyImporter, CurrentUser currentUser) {
        this.properties = properties;
        this.secretBox = secretBox;
        this.migrator = migrator;
        this.legacyImporter = legacyImporter;
        this.currentUser = currentUser;
    }

    /**
     * 当前数据库后端与配置状态。
     *
     * @return backend（SQLITE/POSTGRES）、jdbcUrl（已脱敏）、sqliteFile、配置文件路径与是否已保存 PG 配置
     */
    @GetMapping
    public Map<String, Object> status() {
        requireRead();
        Map<String, String> saved = DbBackendConfig.read(configFile());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backend", properties.backend().name());
        out.put("jdbcUrl", maskUrl(properties.jdbcUrl()));
        out.put("sqliteFile", properties.sqliteFile());
        out.put("poolSize", properties.poolSize());
        out.put("configFile", configFile().toString());
        out.put("savedUrl", saved.getOrDefault(DbBackendConfig.KEY_URL, ""));
        out.put("savedUsername", saved.getOrDefault(DbBackendConfig.KEY_USERNAME, ""));
        out.put("savedTarget", !saved.getOrDefault(DbBackendConfig.KEY_URL, "").isBlank());
        out.put("hasPassword", !saved.getOrDefault(DbBackendConfig.KEY_PASSWORD_CIPHER, "").isBlank());
        out.put("migratable", !properties.isPostgres());
        out.put("legacyImportEnabled", properties.importLegacy());
        return out;
    }

    /**
     * 旧库巡检（只读）：列出改造前 8 个分域 {@code .db} 的存在情况、大小、上次导入行数。
     *
     * <p>不写任何数据，页面打开即可调用；用户据此判断"源库还在不在、还要不要导入"。
     *
     * @return directory、backend、pending、files[]（file/sizeBytes/imported/importedRows）
     */
    @GetMapping("/legacy")
    public Map<String, Object> legacyStatus() {
        requireRead();
        LegacySqliteImporter.Inspection inspection = legacyImporter.inspect();
        List<Map<String, Object>> files = new ArrayList<>();
        for (LegacySqliteImporter.FileState state : inspection.files()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", state.file());
            row.put("sizeBytes", state.sizeBytes());
            row.put("imported", state.imported());
            row.put("importedRows", state.importedRows());
            files.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("directory", inspection.directory() == null ? "" : inspection.directory().toString());
        out.put("backend", inspection.backend());
        out.put("pending", inspection.pending());
        out.put("enabled", properties.importLegacy());
        out.put("files", files);
        return out;
    }

    /**
     * 执行旧库导入（用户显式触发；曾是启动期自动行为，现已移出启动链路）。
     *
     * @param body force=true 时忽略完成标记强制重放（仍幂等，不产生重复行）
     * @return ok、copiedRows、imported/skipped/failed 计数、files[] 逐文件结果
     */
    @PostMapping("/legacy/import")
    public Map<String, Object> legacyImport(@RequestBody(required = false) Map<String, Object> body) {
        requireWrite();
        if (!properties.importLegacy()) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                    "旧库导入已被配置关闭（agent.db.import-legacy=false）");
        }
        boolean force = Boolean.parseBoolean(str(body, "force"));
        LegacySqliteImporter.Report report = legacyImporter.importAll(force);
        List<Map<String, Object>> files = new ArrayList<>();
        for (LegacySqliteImporter.FileResult result : report.files()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", result.file());
            row.put("copied", result.copied());
            row.put("skipped", result.skipped());
            row.put("ok", result.ok());
            row.put("error", result.error());
            files.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", report.failed() == 0);
        out.put("directory", report.directory() == null ? "" : report.directory().toString());
        out.put("copiedRows", report.copied());
        out.put("imported", report.imported());
        out.put("skippedFiles", report.skipped());
        out.put("failedFiles", report.failed());
        out.put("files", files);
        out.put("message", report.failed() == 0
                ? "旧库导入完成：导入 " + report.imported() + " 个文件 / " + report.copied() + " 行，"
                        + "跳过 " + report.skipped() + " 个已导入文件"
                : "部分文件导入失败：" + report.failed() + " 个，详见逐文件结果");
        log.info("设置页触发旧库导入：force={}，导入 {} 行，失败 {} 个文件",
                force, report.copied(), report.failed());
        return out;
    }

    /**
     * 测试目标 PostgreSQL 连通性（保存/迁移前验证）。
     *
     * @param body url / username / password；password 留空时沿用已保存的密文
     * @return ok、latencyMs、serverVersion、message
     */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody Map<String, Object> body) {
        requireWrite();
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
            out.put("message", "连接失败：" + rootMessage(e));
        }
        out.put("latencyMs", (System.nanoTime() - start) / 1_000_000L);
        return out;
    }

    /**
     * 一键迁移：把当前 SQLite 数据搬进目标 PostgreSQL（建表 + 逐表复制，幂等可重复）。
     *
     * @param body url / username / password（password 留空沿用已保存密文）
     * @return ok、message、copiedRows、tables、skippedTables、details[]、elapsedMs
     */
    @PostMapping("/migrate")
    public Map<String, Object> migrate(@RequestBody Map<String, Object> body) {
        requireWrite();
        String url = requirePostgresUrl(body);
        String username = str(body, "username");
        String password = resolvePassword(body);
        SqliteToPostgresMigrator.Result result =
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
     * 保存数据库后端配置（下次启动生效）。
     *
     * @param body url / username / password
     * @return restartRequired=true 与保存位置
     */
    @PostMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        requireWrite();
        String url = requirePostgresUrl(body);
        String username = str(body, "username");
        String password = resolvePassword(body);
        SecretBox.Sealed sealed = secretBox.encrypt(password == null ? "" : password);
        DbBackendConfig.write(configFile(), url, username, sealed.cipherB64(), sealed.ivB64());
        log.info("数据库后端配置已保存（重启后生效）：{}", maskUrl(url));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("restartRequired", true);
        out.put("configFile", configFile().toString());
        out.put("message", "已保存，重启平台后使用 PostgreSQL");
        return out;
    }

    /** 清除数据库后端配置，回到 SQLite（下次启动生效）。 */
    @DeleteMapping
    public Map<String, Object> reset() {
        requireWrite();
        DbBackendConfig.clear(configFile());
        log.info("数据库后端配置已清除，下次启动回到 SQLite");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("restartRequired", true);
        out.put("message", "已清除，重启平台后回到 SQLite");
        return out;
    }

    // ---------------------------------------------------------------- 内部

    private Path configFile() {
        return DbBackendConfig.file(Path.of(properties.sqliteFile()));
    }

    private void requireRead() {
        currentUser.checkPermission("settings:read");
    }

    private void requireWrite() {
        currentUser.checkPermission("settings:write");
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
     * <p>这样"只改用户名"或"再迁移一次"不必每次重输密码，同时保证明文不回流前端。
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

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
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
}
