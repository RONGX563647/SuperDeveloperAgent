package top.rongx.agent.governance;

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
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * governance 侧的统一持久化入口。
 *
 * <p>架构约束要求 governance 只依赖 domain，因此不能复用 infra 的
 * {@code PlatformDb}；这里用同一套 Hibernate/JPA 方式装配，数据库、schema、
 * 事务与方言都交给 ORM，模块内不再手写连接池与 PRAGMA。
 */
public final class GovernanceDb implements AutoCloseable {

    private static final Map<String, GovernanceDb> STANDALONE = new ConcurrentHashMap<>();

    private final EntityManagerFactory entityManagerFactory;
    private final HikariDataSource dataSource;
    private final boolean owned;

    private GovernanceDb(EntityManagerFactory entityManagerFactory,
                         HikariDataSource dataSource,
                         boolean owned) {
        this.entityManagerFactory = entityManagerFactory;
        this.dataSource = dataSource;
        this.owned = owned;
    }

    /** 应用内：复用容器里的 EMF。 */
    public static GovernanceDb managed(EntityManagerFactory entityManagerFactory) {
        return new GovernanceDb(entityManagerFactory, null, false);
    }

    /** 测试/独立进程：按 SQLite 文件复用一套 EMF。 */
    public static GovernanceDb sqlite(Path dbFile) {
        Path file = dbFile.toAbsolutePath().normalize();
        return STANDALONE.computeIfAbsent("jdbc:sqlite:" + file, GovernanceDb::createSqlite);
    }

    public <T> T read(Function<EntityManager, T> work) {
        try (EntityManager em = entityManagerFactory.createEntityManager()) {
            return work.apply(em);
        }
    }

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

    public void run(Consumer<EntityManager> work) {
        inTx(em -> {
            work.accept(em);
            return null;
        });
    }

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

    public static <T> Optional<T> first(EntityManager em, String sql, Object... args) {
        Bound bound = bind(sql, args);
        List<?> rows = apply(bound, em.createNativeQuery(bound.sql())).getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable((T) rows.get(0));
    }

    public static long count(EntityManager em, String sql, Object... args) {
        Object value = first(em, sql, args).orElse(0L);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public static void closeAll() {
        for (String key : STANDALONE.keySet().toArray(new String[0])) {
            GovernanceDb db = STANDALONE.remove(key);
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
        entityManagerFactory.close();
        if (dataSource != null) {
            dataSource.close();
        }
    }

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

    private static GovernanceDb createSqlite(String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("governance-standalone-sqlite");
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(10_000);
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("busy_timeout", "5000");
        HikariDataSource dataSource = new HikariDataSource(config);
        try {
            return new GovernanceDb(buildEntityManagerFactory(dataSource), dataSource, true);
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
    }

    private static EntityManagerFactory buildEntityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("top.rongx.agent.governance");
        factory.setPersistenceProvider(new HibernatePersistenceProvider());
        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setDatabasePlatform("org.hibernate.community.dialect.SQLiteDialect");
        factory.setJpaVendorAdapter(adapter);
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.hbm2ddl.auto", "update");
        properties.put("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
        properties.put("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        properties.put("hibernate.jdbc.batch_size", "50");
        factory.setJpaPropertyMap(properties);
        factory.afterPropertiesSet();
        return factory.getObject();
    }
}
