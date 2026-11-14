package top.rongx.agent.governance;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * governance.db 的连接约定。
 *
 * <p>变更单、确认令牌、补丁登记共用同一个 SQLite 文件，且都发生在请求链路里。
 * 这里统一设置 {@code busy_timeout}（并发写入时等待而不是立刻抛 "database is locked"）
 * 与 WAL（读写不互相阻塞）。governance 模块不能依赖 infra 的连接池，故保留每次打开，
 * 但把必须的 PRAGMA 收敛到一处，避免三个 Store 各写一份、漏掉某一份。
 */
final class GovernanceDb {

    private GovernanceDb() {
    }

    static Connection open(Path dbFile) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 连接已不可用，关闭失败无补救动作
            }
            throw e;
        }
        return connection;
    }
}
