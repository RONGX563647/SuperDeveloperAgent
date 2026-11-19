package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code env_bindings} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "env_bindings")
public class EnvBindingEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "project_id", columnDefinition = "text", nullable = false)
    private String projectId;

    @Column(name = "env", columnDefinition = "text", nullable = false)
    private String env;

    @Column(name = "mapping_json", columnDefinition = "text", nullable = false)
    private String mappingJson;

    @Column(name = "mapping_hash", columnDefinition = "text", nullable = false)
    private String mappingHash;

    @Column(name = "created_by", columnDefinition = "text")
    private String createdBy;

    @Column(name = "confirmed_by", columnDefinition = "text")
    private String confirmedBy;

    @Column(name = "confirmed_at", columnDefinition = "bigint default 0", nullable = false)
    private Long confirmedAt;

    @Column(name = "expires_at", columnDefinition = "bigint default 0", nullable = false)
    private Long expiresAt;

    @Column(name = "status", columnDefinition = "text default 'DRAFT'", nullable = false)
    private String status;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

}
