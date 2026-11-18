package top.rongx.agent.infra.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.infra.persistence.config.AgentDbProperties;

/**
 * 旧版多文件 SQLite → 统一库的导入器。
 *
 * <p>改造前平台按域拆成 8 个 {@code .db} 文件；改造后统一到 platform.db（或 PostgreSQL）。
 *
 * <p><b>为什么不在启动时自动跑</b>：启动期静默改数据是不可控副作用——日志里出现
 * 「新增 N 行」而用户毫无预期，也无从选择目标库、看不到逐表明细、更无法在导入前
 * 核对源文件。因此启动链路<b>只做只读巡检</b>（{@link #inspect()} 不写任何数据），
 * 真正的导入由设置页「旧库导入」显式触发（{@link #importAll(List)}）。
 *
 * <p><b>执行语义（一次性）</b>：每个旧库文件导入完成后，在统一库 {@code platform_meta}
 * 写入 {@code legacy-import:<文件名>} 标记；后续触发见到标记即跳过该文件，
 * 无论当前后端是 SQLite 还是 PostgreSQL。标记与数据在同一连接内写入，
 * 因此「导入了但没标记」只可能发生在进程崩溃窗口，此时下次按幂等
 * {@code INSERT OR IGNORE / ON CONFLICT DO NOTHING} 重放一遍也不会产生重复行。
 *
 * <p>导入不覆盖统一库已有数据（逐表 OR IGNORE / DO NOTHING）。确需重放时，
 * 调用方传 {@code force=true} 或删除对应的 {@code platform_meta} 行。
 *
 * <p>SQLite 目标库走 {@code ATTACH} 原地复制；PostgreSQL 目标库通过 SQLite JDBC
 * 读出后批量写入。
 */
public class LegacySqliteImporter {

    private static final Logger log = LoggerFactory.getLogger(LegacySqliteImporter.class);

    /** 旧版分域库文件名；顺序即导入顺序。 */
    public static final List<String> LEGACY_FILES = List.of(
            "projects.db", "automation.db", "conversations.db", "run_ledger.db",
            "token_usage.db", "users.db", "governance.db", "rate_limit.db");

    /** platform_meta 标记前缀：legacy-import:<旧库文件名> -> 导入行数。 */
    private static final String MARKER_PREFIX = "legacy-import:";

    private final PlatformDb db;
    private final AgentDbProperties properties;

    public LegacySqliteImporter(PlatformDb db, AgentDbProperties properties) {
        this.db = db;
        this.properties = properties;
    }

    /** 单个旧库的巡检/导入结果。 */
    public record FileState(String file, long sizeBytes, boolean imported, int importedRows,
                            boolean present) {
    }

    /** 一次巡检报告：旧库目录、逐文件状态、是否可导入。 */
    public record Inspection(Path directory, List<FileState> files, String backend) {

        /** 至少有一个现存文件尚未导入，才值得让用户点「开始导入」。 */
        public boolean pending() {
            return files.stream().anyMatch(f -> f.present() && !f.imported());
        }
    }

    /** 单个文件的导入结果：行数、错误、是否跳过。 */
    public record FileResult(String file, int copied, String error, boolean skipped) {
        public boolean ok() {
            return error == null;
        }
    }

    /** 一次导入的汇总。 */
    public record Report(List<FileResult> files, int copied, int imported, int skipped,
                         int failed, Path directory) {
    }

    /**
     * 只读巡检：报告每个旧库文件是否存在、是否已有完成标记、标记里记的行数。
     *
     * <p>不写任何数据、不建表、不改标记——设置页打开即可安全调用，也可以在
     * 决定是否导入之前用来核对「源库还在不在、有多大、上次导过多少」。
     *
     * @return 巡检报告；旧库目录不存在时 {@code directory} 为 null、{@code files} 为空
     */
    public Inspection inspect() {
        Path directory = legacyDirectory();
        List<FileState> states = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return new Inspection(directory, List.of(), backendName());
        }
        for (String fileName : LEGACY_FILES) {
            Path legacy = directory.resolve(fileName);
            if (!Files.isRegularFile(legacy)) {
                continue;
            }
            long size;
            try {
                size = Files.size(legacy);
            } catch (RuntimeException | java.io.IOException e) {
                size = 0L;
            }
            String marker = markerOf(fileName);
            states.add(new FileState(fileName, size, marker != null,
                    marker == null ? 0 : parseInt(marker), true));
        }
        return new Inspection(directory, List.copyOf(states), backendName());
    }

    /**
     * 显式执行旧库导入（设置页「开始导入」按钮）。
     *
     * <p>语义与一次性导入一致：逐文件比对完成标记，已标记的跳过；导入成功后写标记。
     * 单个文件失败不影响其余文件，失败原因回传到报告里供前端逐条展示。
     *
     * @param force 为 true 时忽略完成标记强制重放（仍是 INSERT OR IGNORE 幂等，不产生重复行）
     */
    public Report importAll(boolean force) {
        Path directory = legacyDirectory();
        if (directory == null || !Files.isDirectory(directory)) {
            log.info("旧库导入跳过：未找到旧库目录 {}", directory);
            return new Report(List.of(), 0, 0, 0, 0, directory);
        }
        List<FileResult> results = new ArrayList<>();
        int copied = 0;
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        for (String fileName : LEGACY_FILES) {
            Path legacy = directory.resolve(fileName);
            if (!Files.isRegularFile(legacy)) {
                continue;
            }
            if (!force && alreadyImported(fileName)) {
                skipped++;
                results.add(new FileResult(fileName, 0, null, true));
                continue;
            }
            FileResult result = importOne(legacy, fileName);
            results.add(result);
            if (result.ok()) {
                imported++;
                copied += result.copied();
            } else {
                failed++;
            }
        }
        log.info("旧库导入完成：目录 {}，导入 {} 个文件 / {} 行，跳过 {}，失败 {}",
                directory, imported, copied, skipped, failed);
        return new Report(List.copyOf(results), copied, imported, skipped, failed, directory);
    }

    /** 标记是否存在（读取失败视为未导入，宁可重放也不漏导）。 */
    private boolean alreadyImported(String fileName) {
        return markerOf(fileName) != null;
    }

    /** 读完成标记；不存在或读取失败返回 null。 */
    private String markerOf(String fileName) {
        try {
            return db.read(em -> PlatformDb.first(em,
                    "SELECT meta_value FROM platform_meta WHERE meta_key = ?",
                    MARKER_PREFIX + fileName)).map(String::valueOf).orElse(null);
        } catch (RuntimeException e) {
            log.warn("读取旧库导入标记失败（将视为未导入）: {} - {}", fileName, e.getMessage());
            return null;
        }
    }

    /** 导入单个旧库并写完成标记；数据与标记同连接提交。 */
    private FileResult importOne(Path legacy, String fileName) {
        try (Connection target = db.dataSource().getConnection()) {
            boolean postgres = properties.isPostgres();
            int copied = postgres
                    ? copyFromSqlite(legacy, target)
                    : copyAttached(target, legacy);
            markImported(target, fileName, copied);
            log.info("旧库导入完成: {} -> {} 新增 {} 行{}",
                    fileName, postgres ? "PostgreSQL" : "统一库", copied,
                    copied == 0 ? "（旧库为空或行已在统一库中）" : "");
            return new FileResult(fileName, copied, null, false);
        } catch (SQLException e) {
            // 不写标记：下次触发会自动重放该文件
            log.warn("旧库导入失败（下次可重试 {}）: {}", fileName, e.getMessage());
            return new FileResult(fileName, 0, rootMessage(e), false);
        }
    }

    private void markImported(Connection target, String fileName, int copied) throws SQLException {
        String sql = "INSERT INTO platform_meta (meta_key, meta_value, updated_at) VALUES (?,?,?)"
                + " ON CONFLICT(meta_key) DO NOTHING";
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            statement.setString(1, MARKER_PREFIX + fileName);
            statement.setString(2, String.valueOf(copied));
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    /** 旧库目录：随统一库位置推导（SQLite 取同目录，PG 取 sqlite-file 所在目录）。 */
    Path legacyDirectory() {
        String url = properties.jdbcUrl();
        String file;
        if (url.startsWith("jdbc:sqlite:")) {
            String raw = url.substring("jdbc:sqlite:".length());
            int query = raw.indexOf('?');
            file = query >= 0 ? raw.substring(0, query) : raw;
        } else {
            // PostgreSQL 模式下旧库仍在 sqlite-file 指定的目录（默认工作区 _platform）
            file = properties.sqliteFile();
        }
        if (file == null || file.isBlank() || file.startsWith(":memory:")) {
            return null;
        }
        return Path.of(file).toAbsolutePath().normalize().getParent();
    }

    private String backendName() {
        return properties.isPostgres() ? "POSTGRES" : "SQLITE";
    }

    /** SQLite 目标：ATTACH 旧库原地复制，返回新增行数。 */
    private int copyAttached(Connection target, Path legacy) throws SQLException {
        String alias = "legacy";
        int copied = 0;
        int skippedTables = 0;
        try (Statement statement = target.createStatement()) {
            statement.execute("ATTACH DATABASE '" + legacy.toString().replace("'", "''")
                    + "' AS " + alias);
            for (String table : tables(target, alias)) {
                try {
                    List<String> columns = commonColumns(target, "main." + table, alias + "." + table);
                    if (columns.isEmpty()) {
                        skippedTables++;
                        log.warn("旧表 {} 在统一库中无同名表或公共列，已跳过（数据未导入）: {}",
                                table, legacy.getFileName());
                        continue;
                    }
                    String list = String.join(", ", columns);
                    copied += statement.executeUpdate("INSERT OR IGNORE INTO main." + table
                            + " (" + list + ") SELECT " + list + " FROM " + alias + "." + table);
                } catch (SQLException e) {
                    skippedTables++;
                    log.warn("跳过旧表 {}: {}", table, e.getMessage());
                }
            }
            statement.execute("DETACH DATABASE " + alias);
        }
        return copied;
    }

    /** PostgreSQL 目标：JDBC 读旧库批量写入，返回新增行数。 */
    private int copyFromSqlite(Path legacy, Connection target) throws SQLException {
        try (Connection source = java.sql.DriverManager.getConnection("jdbc:sqlite:" + legacy)) {
            int copied = 0;
            for (String table : tables(source, "main")) {
                try {
                    List<String> shared = commonColumns(target, table, table);
                    shared.retainAll(columns(source, "main." + table));
                    if (shared.isEmpty()) {
                        continue;
                    }
                    copied += copyRows(source, target, table, shared);
                } catch (SQLException e) {
                    log.warn("跳过旧表 {}: {}", table, e.getMessage());
                }
            }
            return copied;
        }
    }

    private int copyRows(Connection source, Connection target, String table,
                         List<String> columns) throws SQLException {
        String list = String.join(", ", columns);
        String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
        String insert = "INSERT INTO " + table + " (" + list + ") VALUES (" + placeholders + ")"
                + " ON CONFLICT DO NOTHING";
        try (Statement select = source.createStatement();
             ResultSet rows = select.executeQuery("SELECT " + list + " FROM " + table);
             PreparedStatement write = target.prepareStatement(insert)) {
            int copied = 0;
            while (rows.next()) {
                for (int i = 1; i <= columns.size(); i++) {
                    Object value = rows.getObject(i);
                    if (value == null) {
                        write.setNull(i, Types.NULL);
                    } else {
                        write.setObject(i, value);
                    }
                }
                write.addBatch();
                if (++copied % 500 == 0) {
                    write.executeBatch();
                }
            }
            write.executeBatch();
            return copied;
        }
    }

    /** SQLite 专用：列出某个库（main 或 ATTACH 别名）里的用户表。 */
    private static List<String> tables(Connection connection, String alias) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT name FROM " + alias
                + ".sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    /**
     * 取表的列名：SQLite 用 {@code pragma_table_info}（支持 ATTACH 的别名库），
     * PostgreSQL 用 {@code information_schema.columns}；不依赖驱动的 catalog/schema 差异。
     */
    private static List<String> columns(Connection connection, String qualifiedTable) throws SQLException {
        int dot = qualifiedTable.indexOf('.');
        String schema = dot < 0 ? "main" : qualifiedTable.substring(0, dot);
        String table = dot < 0 ? qualifiedTable : qualifiedTable.substring(dot + 1);
        boolean sqlite = connection.getMetaData().getDatabaseProductName()
                .toLowerCase(Locale.ROOT).contains("sqlite");
        String sql = sqlite
                ? "SELECT name FROM pragma_table_info(?, ?) ORDER BY cid"
                : "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = current_schema()"
                        + " AND lower(table_name) = lower(?) ORDER BY ordinal_position";
        Set<String> columns = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            if (sqlite) {
                statement.setString(2, schema);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ArrayList<>(columns);
    }

    private static List<String> commonColumns(Connection connection, String targetTable,
                                              String sourceTable) throws SQLException {
        List<String> columns = new ArrayList<>(columns(connection, targetTable));
        columns.retainAll(columns(connection, sourceTable));
        return columns;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 异常根因的简短描述：前端逐条展示失败原因。 */
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
