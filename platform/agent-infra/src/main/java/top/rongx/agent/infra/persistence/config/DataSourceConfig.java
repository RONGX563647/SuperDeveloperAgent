package top.rongx.agent.infra.persistence.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 双库装配：默认 SQLite，配置 {@code agent.db.url} 后切 PostgreSQL。
 *
 * <p>PG 的 schema 由 Flyway 管理；SQLite 没有官方 Flyway 模块，由 Hibernate
 * 根据实体生成/升级（本地单机场景）。两边的实体定义保持同一套。
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

        HikariDataSource dataSource = new HikariDataSource(config);
        if (props.isPostgres()) {
            try {
                migratePostgres(dataSource);
            } catch (RuntimeException e) {
                dataSource.close();
                throw e;
            }
        }
        return dataSource;
    }

    @Bean
    public HibernatePropertiesCustomizer hibernateProperties(AgentDbProperties props) {
        return properties -> {
            if (props.isPostgres()) {
                properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
                properties.put("hibernate.hbm2ddl.auto", "none");
            } else {
                properties.put("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
                properties.put("hibernate.hbm2ddl.auto", "update");
            }
        };
    }

    private static void migratePostgres(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/postgresql")
                .load()
                .migrate();
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
            throw new IllegalStateException("无法创建 SQLite 数据目录: " + parent, e);
        }
    }
}
