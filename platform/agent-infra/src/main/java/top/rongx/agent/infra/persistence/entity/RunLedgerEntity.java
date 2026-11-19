package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code run_ledger} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "run_ledger")
public class RunLedgerEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "kind", columnDefinition = "text", nullable = false)
    private String kind;

    @Column(name = "project_id", columnDefinition = "text", nullable = false)
    private String projectId;

    @Column(name = "subject_id", columnDefinition = "text default ''", nullable = false)
    private String subjectId;

    @Column(name = "owner_user_id", columnDefinition = "text default ''", nullable = false)
    private String ownerUserId;

    @Column(name = "state", columnDefinition = "text", nullable = false)
    private String state;

    @Column(name = "summary", columnDefinition = "text default ''", nullable = false)
    private String summary;

    @Column(name = "error", columnDefinition = "text default ''", nullable = false)
    private String error;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

    @Column(name = "worker_role", columnDefinition = "text")
    private String workerRole;

    @Column(name = "trigger_ref", columnDefinition = "text")
    private String triggerRef;

}
