package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code secrets} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "secrets")
public class SecretEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "project_id", columnDefinition = "text", nullable = false)
    private String projectId;

    @Column(name = "env", columnDefinition = "text", nullable = false)
    private String env;

    @Column(name = "name", columnDefinition = "text", nullable = false)
    private String name;

    @Column(name = "value_enc", columnDefinition = "text", nullable = false)
    private String valueEnc;

    @Column(name = "value_iv", columnDefinition = "text", nullable = false)
    private String valueIv;

    @Column(name = "version", columnDefinition = "bigint default 1", nullable = false)
    private Long version;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

    @Column(name = "rotated_at", columnDefinition = "bigint default 0", nullable = false)
    private Long rotatedAt;

}
