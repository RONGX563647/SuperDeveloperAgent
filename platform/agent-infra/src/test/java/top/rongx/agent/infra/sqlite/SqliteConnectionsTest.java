package top.rongx.agent.infra.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 连接归还后可以继续复用，同一文件不会被重复打开成大量连接。 */
class SqliteConnectionsTest {

    @TempDir
    Path dir;

    @Test
    void reusedConnectionKeepsDataAndIsUsable() throws Exception {
        Path db = dir.resolve("pool.db");
        try (Connection connection = SqliteConnections.get(db);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
            statement.execute("INSERT INTO t (name) VALUES ('a')");
        }

        try (Connection connection = SqliteConnections.get(db);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM t")) {
            assertFalse(connection.isClosed());
            rows.next();
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void closingTwiceDoesNotExhaustThePool() throws Exception {
        Path db = dir.resolve("double-close.db");
        Connection connection = SqliteConnections.get(db);
        connection.close();
        connection.close();

        // 重复 close 不能多释放许可；这里只验证还能继续借到连接
        try (Connection again = SqliteConnections.get(db)) {
            assertFalse(again.isClosed());
        }
    }
}
