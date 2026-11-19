package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code mcp_servers} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "mcp_servers")
public class McpServerEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "name", columnDefinition = "text", nullable = false)
    private String name;

    @Column(name = "transport", columnDefinition = "text", nullable = false)
    private String transport;

    @Column(name = "command", columnDefinition = "text")
    private String command;

    @Column(name = "args", columnDefinition = "text")
    private String args;

    @Column(name = "url", columnDefinition = "text")
    private String url;

    @Column(name = "headers_enc", columnDefinition = "text")
    private String headersEnc;

    @Column(name = "headers_iv", columnDefinition = "text")
    private String headersIv;

    @Column(name = "enabled", columnDefinition = "bigint default 1", nullable = false)
    private Long enabled;

    @Column(name = "updated_by", columnDefinition = "text")
    private String updatedBy;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

}
