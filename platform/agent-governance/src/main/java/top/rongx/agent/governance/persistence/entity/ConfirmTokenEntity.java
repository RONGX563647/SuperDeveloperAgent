package top.rongx.agent.governance.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code confirm_tokens} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "confirm_tokens")
public class ConfirmTokenEntity {

    @Id
    @Column(name = "token", nullable = false, columnDefinition = "text")
    private String token;

    @Column(name = "user_id", columnDefinition = "text", nullable = false)
    private String userId;

    @Column(name = "change_id", columnDefinition = "text", nullable = false)
    private String changeId;

    @Column(name = "plan_hash", columnDefinition = "text", nullable = false)
    private String planHash;

    @Column(name = "expires_at", columnDefinition = "bigint", nullable = false)
    private Long expiresAt;

    @Column(name = "used", columnDefinition = "bigint default 0", nullable = false)
    private Long used;

}
