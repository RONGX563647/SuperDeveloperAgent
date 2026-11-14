package top.rongx.agent.gateway.web;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.infra.sqlite.SqliteConnections;

/**
 * 多实例共享的并发闸门：SQLite 租约表。
 *
 * <p>每个许可是一行 {@code stream_lease(token, expires_at)}：占用时在同一个写事务里
 * 清理过期行、统计、插入；释放时按 token 精确删除。TTL 兜底进程崩溃后残留的租约，
 * 因此不需要额外的后台清理任务。
 *
 * <p>SQLite 的写锁天然串行化多实例的"统计 + 插入"，不会出现同时超额通过。
 * 注意这是平台层的粗粒度闸门，网关层仍建议做一层用户级限流。
 */
public class JdbcAgentStreamLimiter implements AgentStreamLimiter {

    private static final Logger log = LoggerFactory.getLogger(JdbcAgentStreamLimiter.class);

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS stream_lease (
                token      TEXT PRIMARY KEY,
                expires_at INTEGER NOT NULL
            )
            """;

    private final Path dbFile;
    private final int max;
    private final long ttlMillis;

    public JdbcAgentStreamLimiter(Path dbFile, int max, long ttlSeconds) {
        this.dbFile = dbFile;
        this.max = Math.max(1, max);
        this.ttlMillis = Math.max(30, ttlSeconds) * 1000L;
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute(SCHEMA);
        } catch (SQLException e) {
            throw new IllegalStateException("stream_lease 表初始化失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Lease acquireOrThrow() {
        long now = System.currentTimeMillis();
        String token = UUID.randomUUID().toString();
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                purgeExpired(conn, now);
                int used = count(conn);
                if (used >= max) {
                    conn.commit();
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                            "当前并发会话过多（集群上限 " + max + "），请稍后再试");
                }
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO stream_lease(token, expires_at) VALUES (?, ?)")) {
                    insert.setString(1, token);
                    insert.setLong(2, now + ttlMillis);
                    insert.executeUpdate();
                }
                conn.commit();
                return new Lease(token);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("并发许可占用失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void release(Lease lease) {
        if (lease == null || lease.token() == null) {
            return;
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM stream_lease WHERE token = ?")) {
            st.setString(1, lease.token());
            st.executeUpdate();
        } catch (SQLException e) {
            // 释放失败由 TTL 兜底，只记录错误本身，不打印租约内容
            log.warn("并发许可释放失败：{}", e.getMessage());
        }
    }

    @Override
    public int available() {
        long now = System.currentTimeMillis();
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            purgeExpired(conn, now);
            int used = count(conn);
            conn.commit();
            return Math.max(0, max - used);
        } catch (SQLException e) {
            // 诊断接口不应因存储抖动失败
            return 0;
        }
    }

    private void purgeExpired(Connection conn, long now) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "DELETE FROM stream_lease WHERE expires_at <= ?")) {
            st.setLong(1, now);
            st.executeUpdate();
        }
    }

    private int count(Connection conn) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT COUNT(*) FROM stream_lease");
             ResultSet rs = st.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private Connection open() throws SQLException {
        return SqliteConnections.get(dbFile);
    }
}
