package top.rongx.agent.infra.sqlite;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SQLite 连接复用。
 *
 * <p>之前每个 Store 的每个方法都 {@code DriverManager.getConnection} 一次：对话与 BugFix
 * 轮询并发上来以后，反复打开/关闭文件会放大 fd 与锁竞争。这里按数据库文件维护一个小连接池，
 * 关闭包装连接时归还而不是真的关闭。
 *
 * <p>注意：包装连接不是线程安全的，和普通 JDBC 连接一样，只能在同一个线程/事务内使用；
 * 池的上限由 {@link #MAX_CONNECTIONS_PER_DB} 控制。
 */
public final class SqliteConnections {

    // WAL 下读不互斥，但写仍是单写者；8 个连接足以覆盖工作区聚合查询与登录链路，
    // 又不会让 SQLite 的写锁排队过长（更深排队的连接会等待，而不是报 database is locked）。
    private static final int MAX_CONNECTIONS_PER_DB = 8;

    private static final Map<String, Pool> POOLS = new ConcurrentHashMap<>();

    private SqliteConnections() {
    }

    /** 借一个连接；用完 {@code close()} 即归还。 */
    public static Connection get(Path dbFile) throws SQLException {
        String key = dbFile.toAbsolutePath().normalize().toString();
        return POOLS.computeIfAbsent(key, ignored -> new Pool(key)).borrow();
    }

    /**
     * 释放某个库的全部空闲连接并移除连接池。
     *
     * <p>连接池会一直持有 SQLite 文件句柄；Windows 上这会让临时目录（测试 @TempDir、
     * 或已下线的工作区）无法删除。销毁工作区、或测试收尾时显式调用即可。
     * 仅关闭空闲连接：仍在借出中的连接由其 close() 归还后自行关闭真实连接。
     */
    public static void closePool(Path dbFile) {
        Pool pool = POOLS.remove(dbFile.toAbsolutePath().normalize().toString());
        if (pool != null) {
            pool.shutdownPool();
        }
    }

    /**
     * 关闭全部连接池（测试收尾 / 优雅退出用）。
     *
     * <p>每个 Store 按自己的 db 文件建池，指望二十多个测试类各自记得调用
     * {@link #closePool(Path)} 不现实；Windows 上只要残留一个池，{@code @TempDir}
     * 就删不掉 {@code .db/-shm/-wal}，表现为一整批
     * {@code JUnit Failed to close extension context}。统一入口由测试扩展在每个
     * 用例结束后调用，无需改动各测试类。
     */
    public static void closeAllPools() {
        for (String key : POOLS.keySet().toArray(new String[0])) {
            Pool pool = POOLS.remove(key);
            if (pool != null) {
                pool.shutdownPool();
            }
        }
    }

    private static final class Pool {

        private final String url;
        private final LinkedBlockingQueue<Connection> idle = new LinkedBlockingQueue<>();
        private final Semaphore permits = new Semaphore(MAX_CONNECTIONS_PER_DB);
        private final AtomicBoolean shutdown = new AtomicBoolean();

        private Pool(String absolutePath) {
            this.url = "jdbc:sqlite:" + absolutePath;
        }

        /** 关闭池：标志置位后归还的连接直接真关闭，避免句柄留在已移除的池里。 */
        private void shutdownPool() {
            shutdown.set(true);
            Connection idleConnection;
            while ((idleConnection = idle.poll()) != null) {
                closeQuietly(idleConnection);
            }
        }

        private Connection borrow() throws SQLException {
            permits.acquireUninterruptibly();
            Connection real = idle.poll();
            if (real != null) {
                try {
                    if (real.isClosed()) {
                        real = null;
                    }
                } catch (SQLException e) {
                    real = null;
                }
            }
            if (real == null) {
                try {
                    real = create();
                } catch (SQLException e) {
                    permits.release();
                    throw e;
                }
            }
            return wrap(real);
        }

        private Connection create() throws SQLException {
            Connection connection = DriverManager.getConnection(url);
            try (Statement statement = connection.createStatement()) {
                // WAL 是库级设置；busy_timeout 是连接级设置，每次新建都要重设
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA foreign_keys=ON");
            } catch (SQLException e) {
                closeQuietly(connection);
                throw e;
            }
            return connection;
        }

        private Connection wrap(Connection real) {
            AtomicBoolean returned = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(
                    SqliteConnections.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("close".equals(name)) {
                            if (returned.compareAndSet(false, true)) {
                                release(real);
                            }
                            return null;
                        }
                        if ("isClosed".equals(name)) {
                            return returned.get();
                        }
                        if ("equals".equals(name)) {
                            return proxy == args[0];
                        }
                        if ("hashCode".equals(name)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("toString".equals(name)) {
                            return "PooledSqliteConnection(" + url + ")";
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        /** 归还前清理未提交事务；坏连接直接丢弃。 */
        private void release(Connection connection) {
            try {
                if (connection.isClosed()) {
                    permits.release();
                    return;
                }
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                closeQuietly(connection);
                permits.release();
                return;
            }
            if (shutdown.get() || !idle.offer(connection)) {
                closeQuietly(connection);
            }
            permits.release();
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 连接已经坏了，关闭失败没有补救动作
        }
    }
}
