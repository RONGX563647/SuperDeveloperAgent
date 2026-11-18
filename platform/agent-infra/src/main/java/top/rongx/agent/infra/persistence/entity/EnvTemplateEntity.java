package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code env_templates} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "env_templates")
public class EnvTemplateEntity {

    @Id
    @Column(name = "env", nullable = false, columnDefinition = "text")
    private String env;

    @Column(name = "network", columnDefinition = "text")
    private String network;

    @Column(name = "default_target", columnDefinition = "text default 'docker'", nullable = false)
    private String defaultTarget;

    @Column(name = "default_port", columnDefinition = "bigint default 8080", nullable = false)
    private Long defaultPort;

    @Column(name = "health_path", columnDefinition = "text default '/healthz'", nullable = false)
    private String healthPath;

    @Column(name = "runtime_mode", columnDefinition = "text")
    private String runtimeMode;

    @Column(name = "namespace", columnDefinition = "text")
    private String namespace;

    @Column(name = "server_id", columnDefinition = "text")
    private String serverId;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

}
