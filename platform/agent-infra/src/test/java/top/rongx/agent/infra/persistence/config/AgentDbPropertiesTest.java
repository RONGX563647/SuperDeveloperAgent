package top.rongx.agent.infra.persistence.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentDbPropertiesTest {

    @Test
    @DisplayName("未配置数据源时回落 SQLite 默认单文件")
    void defaultsToSqlite() {
        AgentDbProperties props = new AgentDbProperties("", "", "", "", null, null);

        assertEquals(DbBackend.SQLITE, props.backend());
        assertEquals("jdbc:sqlite:./data/workspaces/_platform/platform.db", props.jdbcUrl());
        assertEquals(8, props.poolSize());
    }

    @Test
    @DisplayName("配置 PostgreSQL URL 时切到 PG")
    void postgresUrlSelectsPostgres() {
        AgentDbProperties props = new AgentDbProperties(
                "jdbc:postgresql://127.0.0.1:5432/agent", "agent", "secret", "", 12, false);

        assertTrue(props.isPostgres());
        assertEquals("jdbc:postgresql://127.0.0.1:5432/agent", props.jdbcUrl());
        assertEquals(12, props.poolSize());
    }

    @Test
    @DisplayName("不支持的 JDBC URL 必须显式失败，不能静默回落")
    void rejectsUnknownJdbcUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentDbProperties("jdbc:mysql://127.0.0.1/db", "", "", "", 8, false));
    }
}
