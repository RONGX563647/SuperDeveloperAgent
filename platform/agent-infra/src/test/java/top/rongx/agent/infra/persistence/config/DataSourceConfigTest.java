package top.rongx.agent.infra.persistence.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataSourceConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("SQLite 模式自动创建目录并返回可用连接")
    void sqliteCreatesDatabaseFile() throws Exception {
        Path db = tempDir.resolve("nested/platform.db");
        AgentDbProperties props = new AgentDbProperties("", "", "", db.toString(), 2, false);

        DataSource dataSource = new DataSourceConfig().dataSource(props);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE smoke (id TEXT PRIMARY KEY)");
            statement.execute("INSERT INTO smoke (id) VALUES ('ok')");
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM smoke")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) == 1);
            }
        } finally {
            ((HikariDataSource) dataSource).close();
        }

        assertTrue(Files.isRegularFile(db));
    }
}
