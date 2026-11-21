package top.rongx.agent.infra.persistence;

import top.rongx.agent.domain.error.Errors;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQLite 源库 → 目标库（PostgreSQL 或另一 SQLite）的逐表行复制。
 *
 * <p>用途：设置页把当前 SQLite 数据搬到 PostgreSQL（热切换的数据搬运阶段）。
 * 本类只做"两个已建好 schema 的库之间搬行"，schema 由 Flyway 负责建。
 *
 * <p><b>语义</b>：只复制两边都存在的表与列（以目标库列为准，交集为复制集合），
 * 逐行 {@code INSERT ... ON CONFLICT DO NOTHING}，因此可重复执行且不会覆盖目标库已有行。
 * 单表失败不中断整体，记录到报告里供前端逐表展示。
 *
 * <p>SQL 全为静态拼接（表名/列名来自库元数据，非用户输入），值一律走占位符绑定。
 */
public final class SqliteToTargetCopier {

    private static final int BATCH_SIZE = 500;

    private SqliteToTargetCopier() {
    }

    /** 单表复制结果。 */
    public record TableResult(String table, int copied, int sourceRows, String skipped) {
        public boolean ok() {
            return skipped == null;
        }
    }

    /** 整体报告：逐表结果 + 合计。 */
    public record Report(List<TableResult> tables, int copied, int sourceRows) {

        public int skippedTables() {
            return (int) tables.stream().filter(t -> !t.ok()).count();
        }

        /** 有表被跳过时算部分成功：数据没全过去，前端要显式提示。 */
        public boolean complete() {
            return skippedTables() == 0;
        }
    }

    /**
     * 复制全部公共表。
     *
     * @param source SQLite 连接（读）
     * @param target 目标库连接（写）
     */
    public static Report copy(Connection source, Connection target) throws SQLException {
        List<TableResult> results = new ArrayList<>();
        int copiedTotal = 0;
        int sourceTotal = 0;
        for (String table : tables(source)) {
            try {
                List<String> columns = commonColumns(source, target, table);
                if (columns.isEmpty()) {
                    results.add(new TableResult(table, 0, 0, "目标库无同名表或无公共列，已跳过"));
                    continue;
                }
                int sourceRows = count(source, table);
                // 新增行数用"复制前后目标表行数差"来算，而不是累加批量结果：
                // {@code ON CONFLICT DO NOTHING} 命中的行在部分驱动/并发情形下返回
                // {@code SUCCESS_NO_INFO}(-2) 而非 0，按 -2 计 1 会把"没插入"报成"插入了"，
                // 让幂等校验（第二次复制必须为 0）在机器负载高时随机失败。行数差是精确值。
                int before = count(target, table);
                copyRows(source, target, table, columns);
                int copied = Math.max(0, count(target, table) - before);
                // 表是带显式 id 搬过去的：PostgreSQL 不会因此推进 identity 序列，
                // 不拨正的话应用下一次自增插入就会撞主键（token_usage_pkey 就是这么炸的）。
                IdentitySequenceSync.sync(target, table);
                copiedTotal += copied;
                sourceTotal += sourceRows;
                results.add(new TableResult(table, copied, sourceRows, null));
            } catch (SQLException e) {
                results.add(new TableResult(table, 0, 0, "复制失败：" + Errors.rootMessage(e)));
            }
        }
        return new Report(List.copyOf(results), copiedTotal, sourceTotal);
    }

    /** SQLite 源库里的用户表（排除 sqlite 内部表与迁移历史）。 */
    static List<String> tables(Connection source) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT name FROM sqlite_master WHERE type = 'table'"
                + " AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history' ORDER BY name";
        try (Statement statement = source.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    /**
     * 目标库列 ∩ 源库列。
     *
     * <p>以目标库为准：PG schema 由 Flyway 建好，多出来的列（默认值/可空）不参与复制；
     * 源库多出来的历史列不会被带过去。
     */
    static List<String> commonColumns(Connection source, Connection target,
                                      String table) throws SQLException {
        List<String> targetColumns = columns(target, table);
        if (targetColumns.isEmpty()) {
            return List.of();
        }
        List<String> sourceColumns = columns(source, table);
        List<String> shared = new ArrayList<>();
        for (String column : targetColumns) {
            if (sourceColumns.contains(column)) {
                shared.add(column);
            }
        }
        return shared;
    }

    /**
     * 批量写入一张表的全部源行。
     *
     * <p>不返回行数：批量结果的"新增行数"不可信（见 {@link #copy} 的说明），
     * 需要计数时由调用方用目标表行数差计算。
     */
    private static void copyRows(Connection source, Connection target, String table,
                                 List<String> columns) throws SQLException {
        String list = String.join(", ", columns);
        String placeholders = String.join(",", Collections.nCopies(columns.size(), "?"));
        String insert = "INSERT INTO " + table + " (" + list + ") VALUES (" + placeholders + ")"
                + " ON CONFLICT DO NOTHING";
        try (Statement select = source.createStatement();
             ResultSet rows = select.executeQuery(
                     "SELECT " + list + " FROM " + table + " LIMIT 1000000");
             PreparedStatement write = target.prepareStatement(insert)) {
            int pending = 0;
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
                if (++pending >= BATCH_SIZE) {
                    write.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                write.executeBatch();
            }
        }
    }

    private static int count(Connection connection, String table) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    /**
     * 列名：SQLite 用 {@code pragma_table_info}，其它库用 {@code information_schema.columns}。
     *
     * <p>不依赖驱动 catalog/schema 差异——PG 的 public schema 与 SQLite 的 main 都覆盖到。
     */
    static List<String> columns(Connection connection, String table) throws SQLException {
        boolean sqlite = productName(connection).contains("sqlite");
        String sql = sqlite
                ? "SELECT name FROM pragma_table_info(?) ORDER BY cid"
                : "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = current_schema()"
                        + " AND lower(table_name) = lower(?) ORDER BY ordinal_position";
        Set<String> columns = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ArrayList<>(columns);
    }

    private static String productName(Connection connection) throws SQLException {
        String name = connection.getMetaData().getDatabaseProductName();
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /** 异常根因的简短描述：批处理失败时常被包两层，前端只显示最内层。 */
}
