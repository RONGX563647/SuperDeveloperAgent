package top.rongx.agent.infra.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.infra.persistence.config.AgentDbProperties;

/**
 * 旧版多文件 SQLite → 统一库的一次性导入。
 *
 * <p>改造前平台按域拆成 8 个 {@code .db} 文件；改造后统一到 platform.db（或 PostgreSQL）。
 * 只有显式打开 {@code agent.db.import-legacy=true} 才执行，且逐表 {@code ON CONFLICT DO NOTHING}，
 * 不会覆盖统一库里已有的数据。SQLite 目标库走 {@code ATTACH} 原地复制；PostgreSQL
 * 目标库通过 SQLite JDBC 读出后批量写入。
 */
public class LegacySqliteImporter {

    private static final Logger log = LoggerFactory.getLogger(LegacySqliteImporter.class);

    private static final List<String> LEGACY_FILES = List.of(
            "projects.db", "automation.db", "conversations.db", "run_ledger.db",
            "token_usage.db", "users.db", "governance.db", "rate_limit.db");

    private final PlatformDb db;
    private final AgentDbProperties properties;

    public LegacySqliteImporter(PlatformDb db, AgentDbProperties properties) {
        this.db = db;
        this.properties = properties;
    }

    /** Spring 初始化阶段调用；关闭导入时直接返回。 */
    public void run() {
        if (!properties.importLegacy()) {
            return;
        }
        Path directory = legacyDirectory();
        if (directory == null || !Files.isDirectory(directory)) {
            log.info("未找到旧库目录，跳过导入: {}", directory);
            return;
        }
        if (properties.isPostgres()) {
            importIntoPostgres(directory);
        } else {
            importIntoNewSqlite(directory);
        }
    }

    private Path legacyDirectory() {
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

    private void importIntoNewSqlite(Path directory) {
        int totalCopied = 0;
        try (Connection target = db.dataSource().getConnection()) {
            for (String fileName : LEGACY_FILES) {
                Path legacy = directory.resolve(fileName);
                if (!Files.isRegularFile(legacy)) {
                    continue;
                }
                totalCopied += copyAttached(target, legacy);
            }
        } catch (SQLException e) {
            log.warn("旧库导入失败（不影响启动）: {}", e.getMessage());
        }
        // 导入写入的数据同样先进 WAL；立即 checkpoint 让主文件自包含，
        // 否则"导入成功"后只拷 .db 仍会丢掉刚导入的行（与本次 bug 同源）。
        if (totalCopied > 0 && !properties.isPostgres()) {
            try {
                SqliteWalCheckpointer.Checkpoint cp = SqliteWalCheckpointer.checkpoint(db.dataSource());
                log.info("旧库导入后 checkpoint：busy={} 合并={} 页", cp.busy(), cp.checkpointed());
            } catch (SQLException e) {
                log.warn("旧库导入后 checkpoint 失败: {}", e.getMessage());
            }
        }
    }

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
                        // 不再静默跳过：明确告警，避免"导入完成"却少了一整张表的数据。
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
        log.info("旧库导入完成: {} -> 新增 {} 行{}", legacy.getFileName(), copied,
                skippedTables == 0 ? "" : "，跳过 " + skippedTables + " 张表");
        return copied;
    }

    private void importIntoPostgres(Path directory) {
        try (Connection target = db.dataSource().getConnection()) {
            for (String fileName : LEGACY_FILES) {
                Path legacy = directory.resolve(fileName);
                if (!Files.isRegularFile(legacy)) {
                    continue;
                }
                copyFromSqlite(legacy, target);
            }
        } catch (SQLException e) {
            log.warn("旧库导入 PostgreSQL 失败（不影响启动）: {}", e.getMessage());
        }
    }

    private void copyFromSqlite(Path legacy, Connection target) throws SQLException {
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
            log.info("旧库导入 PostgreSQL 完成: {} -> {} 行", legacy.getFileName(), copied);
        }
    }

    private int copyRows(Connection source, Connection target, String table,
                         List<String> columns) throws SQLException {
        String list = String.join(", ", columns);
        String placeholders = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
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
                .toLowerCase(java.util.Locale.ROOT).contains("sqlite");
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
                    columns.add(rs.getString(1).toLowerCase(java.util.Locale.ROOT));
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
}
