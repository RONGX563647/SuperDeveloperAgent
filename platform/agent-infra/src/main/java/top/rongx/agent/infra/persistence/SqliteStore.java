package top.rongx.agent.infra.persistence;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;

/**
 * 平台 SQLite 存储的公共骨架（模板方法）。
 *
 * <p>四个 Store 此前各自复制了「定位 projects.db / 建表 / open / nullableLong /
 * newId / 事务提交回滚」这套样板。这里只固化真正相同的步骤，SQL 仍留在子类，
 * 避免抽象成「反射拼 SQL」的泛型仓储。
 */
public abstract class SqliteStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dbFile;

    protected SqliteStore(String workspaceRoot, String schema, String schemaLabel) {
        this(Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("projects.db"), schema, schemaLabel);
    }

    protected SqliteStore(Path dbFile, String schema, String schemaLabel) {
        this.dbFile = dbFile.toAbsolutePath().normalize();
        try {
            Path parent = this.dbFile.getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法创建项目存储目录：" + e.getMessage(), e);
        }
        initSchema(schema, schemaLabel);
    }

    protected final Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    protected final Path dbFile() {
        return dbFile;
    }

    /**
     * 建表后的补列 / 补种数据钩子。默认无操作。
     *
     * <p>注意：此时子类实例字段尚未赋值，实现里不要引用子类字段。
     */
    protected void afterSchema(Statement statement) throws SQLException {
        // 默认没有额外迁移
    }

    private void initSchema(String schema, String schemaLabel) {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            for (String ddl : schema.split(";")) {
                if (!ddl.isBlank()) {
                    st.executeUpdate(ddl);
                }
            }
            afterSchema(st);
        } catch (SQLException e) {
            throw new IllegalStateException(schemaLabel + " 表初始化失败：" + e.getMessage(), e);
        }
    }

    /** 事务模板：自动提交 → 提交 / 回滚 → 恢复 autoCommit。 */
    protected final void inTransaction(String operation, TxWork work) {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                work.run(conn);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(operation + "失败：" + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    protected interface TxWork {
        void run(Connection conn) throws SQLException;
    }

    protected static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    protected static void setNullableLong(PreparedStatement st, int index, Long value, long fallback)
            throws SQLException {
        st.setLong(index, value == null ? fallback : value);
    }

    protected static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 生成 8 位随机十六进制短 ID，如 {@code srv-1a2b3c4d}。 */
    protected static String newId(String prefix) {
        byte[] raw = new byte[4];
        RANDOM.nextBytes(raw);
        return prefix + "-" + HexFormat.of().formatHex(raw);
    }
}
