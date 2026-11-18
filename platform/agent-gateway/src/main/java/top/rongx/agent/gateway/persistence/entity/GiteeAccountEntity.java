package top.rongx.agent.gateway.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code gitee_accounts} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "gitee_accounts")
public class GiteeAccountEntity {

    @Id
    @Column(name = "user_id", nullable = false, columnDefinition = "text")
    private String userId;

    @Column(name = "gitee_login", columnDefinition = "text", nullable = false)
    private String giteeLogin;

    @Column(name = "token_enc", columnDefinition = "text", nullable = false)
    private String tokenEnc;

    @Column(name = "token_iv", columnDefinition = "text", nullable = false)
    private String tokenIv;

    @Column(name = "scopes", columnDefinition = "text")
    private String scopes;

    @Column(name = "bound_at", columnDefinition = "bigint", nullable = false)
    private Long boundAt;

}
