package top.rongx.agent.infra.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Query;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import top.rongx.agent.domain.workspace.WorkspacePaths;

/**
 * 平台统一的 JPA 持久化入口。
 *
 * <p>应用内由 Spring 提供 {@link DataSource} 与 {@link EntityManagerFactory}（见
 * {@code DataSourceConfig}）；单元测试直接 {@code new XxxStore(临时目录)} 时，
 * 这里按 SQLite 文件惰性构建一套同构的 EMF，保证测试与生产走完全相同的映射与 DDL。
 *
 * <p>SQL 仍保留在各 Store 中（历史实现量大，逐条改 JPQL 风险高于收益），但连接池、
 * 事务、方言与建表全部交给 Hibernate：SQLite/PG 的差异由实体映射和少量可移植写法消化。
 */
public final class PlatformDb implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlatformDb.class);

    private static final Map<String, PlatformDb> STANDALONE = new ConcurrentHashMap<>();

    /**
     * 当前后端（连接池 + EMF）。
     *
     * <p>之所以放进一个可整体替换的持有者，是为了支持<b>热重载</b>：数据库后端从
     * SQLite 切到 PostgreSQL 时不可能只换 JDBC URL——方言、DDL、连接池参数全都不同，
     * 必须重建 EMF。而 25 个 Store 都在构造期注入同一个 {@link PlatformDb}，
     * 让它们感知后端切换既不现实也不安全。折中做法是：<b>引用不变，内容可换</b>，
     * 每次 {@code read/inTx} 都从 volatile 引用取当前后端，切换时原子替换。
     *
     * <p>用不可变 record + volatile 引用，而不是可变字段：读到旧后端的请求最多
     * 把一次查询跑在旧库上（切换瞬间的既有语义），不会读到"EMF 已关但连接池还在"的中间态。
     */
    private volatile Holder holder;
    private final boolean owned;

    /** 当前生效的"自持"后端；只有它需要被关闭，managed 实例为 false。 */
    private final boolean selfManaged;

    /**
     * 上一次通过 {@link #swapTo} 换入的自持实例（初始为 null）。
     *
     * <p>用于在下一次切换时把"上一套换进来的资源"关掉。容器最初给的 managed 后端
     * 不在这里——它由 Spring 管理，进程活多久它活多久，不归热切换器处置。
     */
    private volatile PlatformDb installed;

    /** 一套后端资源：连接池与它对应的 EMF，要么整体生效要么整体替换。 */
    private record Holder(DataSource dataSource, EntityManagerFactory entityManagerFactory) {
    }

    private PlatformDb(Holder holder, boolean owned) {
        this.holder = holder;
        this.owned = owned;
        this.selfManaged = owned;
    }

    /** 应用内：包装 Spring 容器里的连接池与 EMF，不负责关闭。 */
    public static PlatformDb managed(DataSource dataSource, EntityManagerFactory entityManagerFactory) {
        return new PlatformDb(new Holder(dataSource, entityManagerFactory), false);
    }

    /**
     * 创建一套<b>自持</b>的后端资源（热重载用；用完由调用方 {@link #close()}）。
     *
     * @param jdbcUrl  目标 JDBC URL（{@code jdbc:sqlite:} 或 {@code jdbc:postgresql:}）
     * @param username PG 用户名（SQLite 忽略）
     * @param password PG 密码（SQLite 忽略）
     */
    public static PlatformDb create(String jdbcUrl, String username, String password) {
        return jdbcUrl.startsWith("jdbc:postgresql:")
                ? createPostgres(jdbcUrl, username, password)
                : createSqlite(jdbcUrl);
    }

    /** 测试/独立进程：按 SQLite 文件复用一套 EMF。 */
    public static PlatformDb sqlite(Path dbFile) {
        Path file = dbFile.toAbsolutePath().normalize();
        String key = "jdbc:sqlite:" + file;
        return STANDALONE.computeIfAbsent(key, PlatformDb::createSqlite);
    }

    /** 按工作区根目录定位统一库文件（{@code <root>/_platform/platform.db}）。 */
    public static PlatformDb at(Path workspaceRoot) {
        return sqlite(WorkspacePaths.db(workspaceRoot));
    }

    public DataSource dataSource() {
        return holder.dataSource();
    }

    public EntityManagerFactory entityManagerFactory() {
        return holder.entityManagerFactory();
    }

    /** 当前后端是否 PostgreSQL（热重载与状态接口要用）。 */
    public boolean isPostgres() {
        try {
            String product = holder.dataSource().getConnection().getMetaData()
                    .getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 原子换到另一套自持后端资源，返回被替换下来、需要由调用方关闭的旧资源。
     *
     * <p>前提：新后端的 schema 必须已经建好（热切换器先跑 Flyway 再调用这里），
     * 否则换上去的瞬间会有请求撞到"表不存在"。
     *
     * <p>为什么引用不变、只换内容：25 个 Store 都在构造期注入同一个 {@code PlatformDb}，
     * 让它们感知后端切换既不现实也不安全；而 {@code read/inTx} 每次都从 volatile 引用
     * 取当前后端，切换只是一次引用赋值。落在旧后端上的请求最多把一次查询跑在旧库上
     * （切换瞬间的既有语义），不会看到"EMF 已关但连接池还在"的中间态。
     *
     * <p>归属规则：容器最初给的 managed 后端由 Spring 管，永远不在这里被关；
     * 只有"上一次换进来的自持实例"会在本次切换后由调用方关闭——否则每切一次库就泄漏一个连接池。
     *
     * @return 需要关闭的旧自持实例；没有（首次切换、原来跑的是 Spring 管的库）返回 {@code null}
     */
    public synchronized PlatformDb swapTo(PlatformDb next) {
        if (!next.selfManaged) {
            // spec-ignore: R15 内部装配契约：非自持实例调 swapTo 属编程错误，不是用户可预期的业务失败
            throw new IllegalStateException("只能用自持实例（PlatformDb.create）切换后端");
        }
        Holder old = this.holder;
        // 归还给调用方的必须是"独立包装旧资源"的新对象，不能是 this：
        // this 的 holder 马上就要指向新后端，返回 this 会让调用方 close 到新库上。
        PlatformDb previous = null;
        if (this.installed != null) {
            previous = this.installed;
        } else if (this.selfManaged) {
            previous = new PlatformDb(old, true);
        }
        this.holder = next.holder;
        this.installed = next;
        return previous;
    }

    /** 当前是否仍挂着容器最初装配的后端（没被热切换过）。 */
    public boolean isOriginalBackend() {
        return installed == null;
    }

    /** 只读查询：不开启事务，查询结束立即归还连接。 */
    public <T> T read(Function<EntityManager, T> work) {
        try (EntityManager em = holder.entityManagerFactory().createEntityManager()) {
            return work.apply(em);
        }
    }

    /** 写事务：异常回滚，正常提交。 */
    public <T> T inTx(Function<EntityManager, T> work) {
        EntityManager em = holder.entityManagerFactory().createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            T value = work.apply(em);
            tx.commit();
            return value;
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            em.close();
        }
    }

    /** 无返回值的事务。 */
    public void run(Consumer<EntityManager> work) {
        inTx(em -> {
            work.accept(em);
            return null;
        });
    }

    // ------------------------------------------------------------ 原生查询工具

    public static int update(EntityManager em, String sql, Object... args) {
        Bound bound = bind(sql, args);
        return apply(bound, em.createNativeQuery(bound.sql())).executeUpdate();
    }

    public static List<Object[]> rows(EntityManager em, String sql, Object... args) {
        Bound bound = bind(sql, args);
        @SuppressWarnings("unchecked")
        List<Object[]> result = apply(bound, em.createNativeQuery(bound.sql())).getResultList();
        return result;
    }

    public static Optional<Object[]> row(EntityManager em, String sql, Object... args) {
        List<Object[]> rows = rows(em, sql, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 单列首行；没有结果时返回空。 */
    public static <T> Optional<T> first(EntityManager em, String sql, Object... args) {
        Bound bound = bind(sql, args);
        List<?> rows = apply(bound, em.createNativeQuery(bound.sql())).getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable((T) rows.get(0));
    }

    public static long count(EntityManager em, String sql, Object... args) {
        Object value = first(em, sql, args).orElse(0L);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 原生 SQL 的 null 参数处理。
     *
     * <p>Hibernate 无法为 {@code null} 推断原生 SQL 的参数类型；直接绑定会抛
     * "Could not determine type"。这里把值为 null 的占位符替换成 SQL 字面量
     * {@code NULL}，其余参数按顺序绑定。SQL 均为静态文本，不存在字符串内
     * 含 {@code ?} 的情况，替换是安全的。
     */
    private record Bound(String sql, List<Object> params) {
    }

    private static Bound bind(String sql, Object[] args) {
        StringBuilder out = new StringBuilder(sql.length() + 16);
        List<Object> params = new ArrayList<>(args.length);
        int argIndex = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }
            if (c == '?' && !inSingleQuote && !inDoubleQuote) {
                Object value = argIndex < args.length ? args[argIndex] : null;
                argIndex++;
                if (value == null) {
                    out.append("NULL");
                } else {
                    out.append('?');
                    params.add(value);
                }
                continue;
            }
            out.append(c);
        }
        return new Bound(out.toString(), params);
    }

    private static Query apply(Bound bound, Query query) {
        for (int i = 0; i < bound.params().size(); i++) {
            query.setParameter(i + 1, bound.params().get(i));
        }
        return query;
    }

    /** 测试收尾：关闭全部独立 EMF 与连接池，释放 SQLite 文件句柄。 */
    public static void closeAll() {
        for (String key : STANDALONE.keySet().toArray(new String[0])) {
            PlatformDb db = STANDALONE.remove(key);
            if (db != null && db.owned) {
                db.close();
            }
        }
    }

    @Override
    public void close() {
        if (!owned) {
            return;
        }
        Holder current = holder;
        try {
            current.entityManagerFactory().close();
        } catch (RuntimeException e) {
            log.warn("关闭 EMF 失败: {}", e.getMessage());
        }
        if (current.dataSource() instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }

    private static PlatformDb createSqlite(String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("agent-sqlite");
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(10_000);
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("busy_timeout", "5000");
        config.addDataSourceProperty("foreign_keys", "on");
        HikariDataSource dataSource = new HikariDataSource(config);
        try {
            return new PlatformDb(new Holder(dataSource, buildEntityManagerFactory(dataSource,
                    "org.hibernate.community.dialect.SQLiteDialect", "top.rongx.agent")), true);
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
    }

    private static PlatformDb createPostgres(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("agent-postgres");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username == null ? "" : username);
        config.setPassword(password == null ? "" : password);
        config.setMaximumPoolSize(8);
        config.setConnectionTimeout(10_000);
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        config.addDataSourceProperty("ApplicationName", "agent-platform");
        HikariDataSource dataSource = new HikariDataSource(config);
        try {
            return new PlatformDb(new Holder(dataSource, buildEntityManagerFactory(dataSource,
                    "org.hibernate.dialect.PostgreSQLDialect", "top.rongx.agent")), true);
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
    }

    private static EntityManagerFactory buildEntityManagerFactory(DataSource dataSource,
                                                                  String dialect,
                                                                  String scanPackage) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(scanPackage);
        factory.setPersistenceProvider(new HibernatePersistenceProvider());
        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setDatabasePlatform(dialect);
        factory.setJpaVendorAdapter(adapter);
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.hbm2ddl.auto", "update");
        properties.put("hibernate.dialect", dialect);
        properties.put("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        properties.put("hibernate.connection.autocommit", "false");
        properties.put("hibernate.jdbc.batch_size", "50");
        properties.put("hibernate.order_inserts", "true");
        properties.put("hibernate.order_updates", "true");
        factory.setJpaPropertyMap(properties);
        factory.afterPropertiesSet();
        return factory.getObject();
    }
}
