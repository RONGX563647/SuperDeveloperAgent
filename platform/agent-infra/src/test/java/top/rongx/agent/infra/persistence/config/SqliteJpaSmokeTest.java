package top.rongx.agent.infra.persistence.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.infra.persistence.entity.PlatformMetaEntity;

/** 验证 Hibernate 7 + SQLite 社区方言能够建表并完成读写。 */
class SqliteJpaSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("SQLite 方言可建表、写入并读取")
    void persistsWithSqliteDialect() {
        Configuration configuration = new Configuration();
        configuration.addAnnotatedClass(PlatformMetaEntity.class);
        configuration.setProperty("hibernate.connection.driver_class", "org.sqlite.JDBC");
        configuration.setProperty("hibernate.connection.url",
                "jdbc:sqlite:" + tempDir.resolve("jpa-smoke.db"));
        configuration.setProperty("hibernate.dialect",
                "org.hibernate.community.dialect.SQLiteDialect");
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.setProperty("hibernate.connection.pool_size", "1");

        try (SessionFactory sessionFactory = configuration.buildSessionFactory();
             Session session = sessionFactory.openSession()) {
            session.beginTransaction();
            session.persist(new PlatformMetaEntity("schema", "v1", 1L));
            session.getTransaction().commit();

            PlatformMetaEntity loaded = session.get(PlatformMetaEntity.class, "schema");
            assertEquals("v1", loaded.getValue());
        }
    }
}
