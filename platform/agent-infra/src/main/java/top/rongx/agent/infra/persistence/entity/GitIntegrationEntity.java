package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code git_integrations} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "git_integrations")
public class GitIntegrationEntity {

    @Id
    @Column(name = "provider", nullable = false, columnDefinition = "text")
    private String provider;

    @Column(name = "owner_user_id", columnDefinition = "text")
    private String ownerUserId;

    @Column(name = "enabled", columnDefinition = "bigint", nullable = false)
    private Long enabled;

    @Column(name = "repo_whitelist", columnDefinition = "text")
    private String repoWhitelist;

    @Column(name = "secret_enc", columnDefinition = "text")
    private String secretEnc;

    @Column(name = "secret_iv", columnDefinition = "text")
    private String secretIv;

    @Column(name = "auto_create_task", columnDefinition = "bigint", nullable = false)
    private Long autoCreateTask;

    @Column(name = "sync_direction", columnDefinition = "text", nullable = false)
    private String syncDirection;

    @Column(name = "conflict_policy", columnDefinition = "text", nullable = false)
    private String conflictPolicy;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

}
