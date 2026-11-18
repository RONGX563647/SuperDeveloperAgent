package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code git_oauth_apps} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "git_oauth_apps")
public class GitOauthAppEntity {

    @Id
    @Column(name = "provider", nullable = false, columnDefinition = "text")
    private String provider;

    @Column(name = "client_id", columnDefinition = "text", nullable = false)
    private String clientId;

    @Column(name = "secret_enc", columnDefinition = "text", nullable = false)
    private String secretEnc;

    @Column(name = "secret_iv", columnDefinition = "text", nullable = false)
    private String secretIv;

    @Column(name = "redirect_uri", columnDefinition = "text", nullable = false)
    private String redirectUri;

    @Column(name = "scopes", columnDefinition = "text", nullable = false)
    private String scopes;

    @Column(name = "enabled", columnDefinition = "bigint", nullable = false)
    private Long enabled;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

}
