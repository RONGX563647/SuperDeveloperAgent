package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code bugfix_leases} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "bugfix_leases")
public class BugfixLeaseEntity {

    @Id
    @Column(name = "run_id", nullable = false, columnDefinition = "text")
    private String runId;

    @Column(name = "owner", columnDefinition = "text", nullable = false)
    private String owner;

    @Column(name = "lease_until", columnDefinition = "bigint", nullable = false)
    private Long leaseUntil;

}
