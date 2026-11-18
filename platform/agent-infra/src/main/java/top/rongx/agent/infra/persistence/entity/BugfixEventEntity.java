package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;

/** 由 {@code bugfix_events} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "bugfix_events")
public class BugfixEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "run_id", columnDefinition = "text", nullable = false)
    private String runId;

    @Column(name = "stage", columnDefinition = "text", nullable = false)
    private String stage;

    @Column(name = "level", columnDefinition = "text", nullable = false)
    private String level;

    @Column(name = "message", columnDefinition = "text", nullable = false)
    private String message;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

}
