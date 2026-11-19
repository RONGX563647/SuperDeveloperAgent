package top.rongx.agent.gateway.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code stream_lease} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "stream_lease")
public class StreamLeaseEntity {

    @Id
    @Column(name = "token", nullable = false, columnDefinition = "text")
    private String token;

    @Column(name = "expires_at", columnDefinition = "bigint", nullable = false)
    private Long expiresAt;

}
