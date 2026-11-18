package top.rongx.agent.infra.persistence.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.infra.persistence.LegacySqliteImporter;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 双库装配：默认 SQLite，配置 {@code agent.db.url} 后切 PostgreSQL。
 *
 * <p>两库使用同一套 JPA 实体，schema 由 Hibernate {@code hbm2ddl.auto=update}
 * 在启动时生成/补齐。SQLite 没有官方 Flyway 模块，为避免两套 DDL 漂移，
 * PG 也不再单独维护迁移脚本。
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(AgentDbProperties props) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(props.isPostgres() ? "agent-postgres" : "agent-sqlite");
        config.setJdbcUrl(props.jdbcUrl());
        config.setConnectionTimeout(10_000);
        config.setMaximumPoolSize(props.poolSize());

        if (props.isPostgres()) {
            config.setUsername(props.username());
            config.setPassword(props.password());
            config.addDataSourceProperty("reWriteBatchedInserts", "true");
            config.addDataSourceProperty("ApplicationName", "agent-platform");
        } else {
            prepareSqliteDirectory(props.jdbcUrl());
            config.setMaximumPoolSize(Math.min(props.poolSize(), 4));
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("busy_timeout", "5000");
            config.addDataSourceProperty("foreign_keys", "on");
        }
        return new HikariDataSource(config);
    }

    @Bean
    public HibernatePropertiesCustomizer hibernateProperties(AgentDbProperties props) {
        return properties -> {
            properties.put("hibernate.dialect", props.isPostgres()
                    ? "org.hibernate.dialect.PostgreSQLDialect"
                    : "org.hibernate.community.dialect.SQLiteDialect");
            properties.put("hibernate.hbm2ddl.auto", "update");
        };
    }

    /** 统一持久化入口：Store 通过它拿事务与原生查询，不再各自打开连接。 */
    @Bean
    public PlatformDb platformDb(DataSource dataSource, EntityManagerFactory entityManagerFactory) {
        return PlatformDb.managed(dataSource, entityManagerFactory);
    }

    /** 显式开关 {@code agent.db.import-legacy=true} 时，把旧的多文件 SQLite 导入统一库。 */
    @Bean(initMethod = "run")
    public LegacySqliteImporter legacySqliteImporter(PlatformDb platformDb, AgentDbProperties props) {
        return new LegacySqliteImporter(platformDb, props);
    }

    private static void prepareSqliteDirectory(String jdbcUrl) {
        String raw = jdbcUrl.substring("jdbc:sqlite:".length());
        int query = raw.indexOf('?');
        String file = query >= 0 ? raw.substring(0, query) : raw;
        if (file.isBlank() || file.startsWith(":memory:") || file.startsWith("file:")) {
            return;
        }
        Path parent = Path.of(file).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INTERNAL,
                    "无法创建 SQLite 数据目录: " + parent, e);
        }
    }
}
