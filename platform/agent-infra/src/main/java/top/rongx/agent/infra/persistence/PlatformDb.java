package top.rongx.agent.infra.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Query;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    private final DataSource dataSource;
    private final EntityManagerFactory entityManagerFactory;
    private final boolean owned;

    private PlatformDb(DataSource dataSource, EntityManagerFactory entityManagerFactory, boolean owned) {
        this.dataSource = dataSource;
        this.entityManagerFactory = entityManagerFactory;
        this.owned = owned;
    }

    /** 应用内：包装 Spring 容器里的连接池与 EMF，不负责关闭。 */
    public static PlatformDb managed(DataSource dataSource, EntityManagerFactory entityManagerFactory) {
        return new PlatformDb(dataSource, entityManagerFactory, false);
    }

    /** 测试/独立进程：按 SQLite 文件复用一套 EMF。 */
    public static PlatformDb sqlite(Path dbFile) {
        Path file = dbFile.toAbsolutePath().normalize();
        String key = "jdbc:sqlite:" + file;
        return STANDALONE.computeIfAbsent(key, PlatformDb::createSqlite);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public EntityManagerFactory entityManagerFactory() {
        return entityManagerFactory;
    }

    /** 只读查询：不开启事务，查询结束立即归还连接。 */
    public <T> T read(Function<EntityManager, T> work) {
        try (EntityManager em = entityManagerFactory.createEntityManager()) {
            return work.apply(em);
        }
    }

    /** 写事务：异常回滚，正常提交。 */
    public <T> T inTx(Function<EntityManager, T> work) {
        EntityManager em = entityManagerFactory.createEntityManager();
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
        try {
            entityManagerFactory.close();
        } catch (RuntimeException e) {
            log.warn("关闭 EMF 失败: {}", e.getMessage());
        }
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }

    private static PlatformDb createSqlite(String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("agent-standalone-sqlite");
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(10_000);
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("busy_timeout", "5000");
        config.addDataSourceProperty("foreign_keys", "on");
        HikariDataSource dataSource = new HikariDataSource(config);
        try {
            return new PlatformDb(dataSource, buildEntityManagerFactory(dataSource,
                    "org.hibernate.community.dialect.SQLiteDialect", "top.rongx.agent"), true);
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
