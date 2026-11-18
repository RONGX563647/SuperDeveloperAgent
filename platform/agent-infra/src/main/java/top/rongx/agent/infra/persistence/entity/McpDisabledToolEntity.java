package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 由 {@code mcp_disabled_tools} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "mcp_disabled_tools")
@IdClass(McpDisabledToolEntity.Key.class)
public class McpDisabledToolEntity {

    @Id
    @Column(name = "server_id", nullable = false, columnDefinition = "text")
    private String serverId;

    @Id
    @Column(name = "tool_name", nullable = false, columnDefinition = "text")
    private String toolName;

    /** 复合主键。 */
    public static class Key implements Serializable {
        private String serverId;
        private String toolName;

        public Key() {
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(serverId, that.serverId) && Objects.equals(toolName, that.toolName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(serverId, toolName);
        }
    }
}
