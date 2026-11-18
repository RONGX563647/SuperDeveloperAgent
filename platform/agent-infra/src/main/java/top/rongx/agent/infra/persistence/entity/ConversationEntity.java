package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code conversations} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "conversations")
public class ConversationEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "project_id", columnDefinition = "text", nullable = false)
    private String projectId;

    @Column(name = "owner_user_id", columnDefinition = "text", nullable = false)
    private String ownerUserId;

    @Column(name = "title", columnDefinition = "text", nullable = false)
    private String title;

    @Column(name = "kind", columnDefinition = "text", nullable = false)
    private String kind;

    @Column(name = "role", columnDefinition = "text", nullable = false)
    private String role;

    @Column(name = "env", columnDefinition = "text", nullable = false)
    private String env;

    @Column(name = "worktree", columnDefinition = "text")
    private String worktree;

    @Column(name = "status", columnDefinition = "text", nullable = false)
    private String status;

    @Column(name = "summary", columnDefinition = "text default ''", nullable = false)
    private String summary;

    @Column(name = "pinned", columnDefinition = "bigint default 0", nullable = false)
    private Long pinned;

    @Column(name = "archived", columnDefinition = "bigint default 0", nullable = false)
    private Long archived;

    @Column(name = "parent_conversation_id", columnDefinition = "text")
    private String parentConversationId;

    @Column(name = "last_activity_at", columnDefinition = "bigint", nullable = false)
    private Long lastActivityAt;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

    @Column(name = "model_connection_id", columnDefinition = "text")
    private String modelConnectionId;

    @Column(name = "model_name", columnDefinition = "text")
    private String modelName;

}
