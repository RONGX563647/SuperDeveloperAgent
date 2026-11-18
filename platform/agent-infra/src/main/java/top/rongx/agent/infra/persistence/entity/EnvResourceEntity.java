package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code env_resources} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "env_resources")
public class EnvResourceEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "env", columnDefinition = "text", nullable = false)
    private String env;

    @Column(name = "kind", columnDefinition = "text", nullable = false)
    private String kind;

    @Column(name = "name", columnDefinition = "text", nullable = false)
    private String name;

    @Column(name = "host", columnDefinition = "text", nullable = false)
    private String host;

    @Column(name = "port", columnDefinition = "bigint", nullable = false)
    private Long port;

    @Column(name = "container_port", columnDefinition = "bigint default 0", nullable = false)
    private Long containerPort;

    @Column(name = "internal_host", columnDefinition = "text")
    private String internalHost;

    @Column(name = "database_name", columnDefinition = "text")
    private String databaseName;

    @Column(name = "username", columnDefinition = "text")
    private String username;

    @Column(name = "password_enc", columnDefinition = "text")
    private String passwordEnc;

    @Column(name = "password_iv", columnDefinition = "text")
    private String passwordIv;

    @Column(name = "network", columnDefinition = "text")
    private String network;

    @Column(name = "server_id", columnDefinition = "text")
    private String serverId;

    @Column(name = "source", columnDefinition = "text")
    private String source;

    @Column(name = "running", columnDefinition = "bigint default 0", nullable = false)
    private Long running;

    @Column(name = "last_check_at", columnDefinition = "bigint default 0", nullable = false)
    private Long lastCheckAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

}
