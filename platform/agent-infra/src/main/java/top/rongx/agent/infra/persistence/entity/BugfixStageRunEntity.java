package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;

/** 由 {@code bugfix_stage_runs} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "bugfix_stage_runs")
public class BugfixStageRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "run_id", columnDefinition = "text", nullable = false)
    private String runId;

    @Column(name = "stage", columnDefinition = "text", nullable = false)
    private String stage;

    @Column(name = "attempt", columnDefinition = "bigint default 1", nullable = false)
    private Long attempt;

    @Column(name = "status", columnDefinition = "text", nullable = false)
    private String status;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    @Column(name = "artifact", columnDefinition = "text")
    private String artifact;

    @Column(name = "started_at", columnDefinition = "bigint", nullable = false)
    private Long startedAt;

    @Column(name = "ended_at", columnDefinition = "bigint default 0", nullable = false)
    private Long endedAt;

    @Column(name = "duration_ms", columnDefinition = "bigint default 0", nullable = false)
    private Long durationMs;

}
