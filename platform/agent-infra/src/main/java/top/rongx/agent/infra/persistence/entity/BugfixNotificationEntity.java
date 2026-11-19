package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;

/** 由 {@code bugfix_notifications} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "bugfix_notifications")
public class BugfixNotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "run_id", columnDefinition = "text", nullable = false)
    private String runId;

    @Column(name = "message", columnDefinition = "text", nullable = false)
    private String message;

    @Column(name = "status", columnDefinition = "text default 'PENDING'", nullable = false)
    private String status;

    @Column(name = "attempts", columnDefinition = "bigint default 0", nullable = false)
    private Long attempts;

    @Column(name = "next_attempt_at", columnDefinition = "bigint default 0", nullable = false)
    private Long nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

}
