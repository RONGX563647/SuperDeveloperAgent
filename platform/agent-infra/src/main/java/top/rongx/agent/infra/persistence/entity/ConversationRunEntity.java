package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code conversation_runs} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "conversation_runs")
public class ConversationRunEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "project_id", columnDefinition = "text", nullable = false)
    private String projectId;

    @Column(name = "conversation_id", columnDefinition = "text", nullable = false)
    private String conversationId;

    @Column(name = "owner_user_id", columnDefinition = "text", nullable = false)
    private String ownerUserId;

    @Column(name = "goal", columnDefinition = "text", nullable = false)
    private String goal;

    @Column(name = "goal_key", columnDefinition = "text", nullable = false)
    private String goalKey;

    @Column(name = "status", columnDefinition = "text", nullable = false)
    private String status;

    @Column(name = "result", columnDefinition = "text default ''", nullable = false)
    private String result;

    @Column(name = "error", columnDefinition = "text default ''", nullable = false)
    private String error;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

    @Column(name = "started_at", columnDefinition = "bigint default 0", nullable = false)
    private Long startedAt;

    @Column(name = "ended_at", columnDefinition = "bigint default 0", nullable = false)
    private Long endedAt;

}
