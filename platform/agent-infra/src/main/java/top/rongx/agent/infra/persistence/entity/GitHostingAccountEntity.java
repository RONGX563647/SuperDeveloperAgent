package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code git_hosting_accounts} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "git_hosting_accounts")
public class GitHostingAccountEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "user_id", columnDefinition = "text", nullable = false)
    private String userId;

    @Column(name = "provider", columnDefinition = "text", nullable = false)
    private String provider;

    @Column(name = "host", columnDefinition = "text", nullable = false)
    private String host;

    @Column(name = "username", columnDefinition = "text")
    private String username;

    @Column(name = "token_enc", columnDefinition = "text", nullable = false)
    private String tokenEnc;

    @Column(name = "token_iv", columnDefinition = "text", nullable = false)
    private String tokenIv;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

}
