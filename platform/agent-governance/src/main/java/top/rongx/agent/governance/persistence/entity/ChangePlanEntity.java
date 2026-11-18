package top.rongx.agent.governance.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code change_plans} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "change_plans")
public class ChangePlanEntity {

    @Id
    @Column(name = "change_id", nullable = false, columnDefinition = "text")
    private String changeId;

    @Column(name = "project_id", columnDefinition = "text", nullable = false)
    private String projectId;

    @Column(name = "env", columnDefinition = "text", nullable = false)
    private String env;

    @Column(name = "kind", columnDefinition = "text", nullable = false)
    private String kind;

    @Column(name = "plan_hash", columnDefinition = "text", nullable = false)
    private String planHash;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "image_digest", columnDefinition = "text")
    private String imageDigest;

    @Column(name = "frontend_dist_hash", columnDefinition = "text")
    private String frontendDistHash;

    @Column(name = "high_risk", columnDefinition = "bigint default 0", nullable = false)
    private Long highRisk;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

    @Column(name = "status", columnDefinition = "text default 'PENDING'", nullable = false)
    private String status;

}
