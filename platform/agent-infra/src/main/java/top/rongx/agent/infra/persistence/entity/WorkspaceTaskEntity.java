package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code workspace_tasks} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "workspace_tasks")
public class WorkspaceTaskEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "project_id", columnDefinition = "text", nullable = false)
    private String projectId;

    @Column(name = "conversation_id", columnDefinition = "text")
    private String conversationId;

    @Column(name = "goal", columnDefinition = "text", nullable = false)
    private String goal;

    @Column(name = "expected_output", columnDefinition = "text default ''", nullable = false)
    private String expectedOutput;

    @Column(name = "status", columnDefinition = "text", nullable = false)
    private String status;

    @Column(name = "priority", columnDefinition = "bigint", nullable = false)
    private Long priority;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

}
