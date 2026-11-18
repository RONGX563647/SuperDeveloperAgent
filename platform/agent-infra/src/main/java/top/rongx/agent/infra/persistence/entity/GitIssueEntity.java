package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code git_issues} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "git_issues")
public class GitIssueEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "code", columnDefinition = "text default ''", nullable = false)
    private String code;

    @Column(name = "user_id", columnDefinition = "text", nullable = false)
    private String userId;

    @Column(name = "project_id", columnDefinition = "text")
    private String projectId;

    @Column(name = "provider", columnDefinition = "text", nullable = false)
    private String provider;

    @Column(name = "repo", columnDefinition = "text", nullable = false)
    private String repo;

    @Column(name = "issue_number", columnDefinition = "text", nullable = false)
    private String issueNumber;

    @Column(name = "title", columnDefinition = "text", nullable = false)
    private String title;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "state", columnDefinition = "text", nullable = false)
    private String state;

    @Column(name = "kind", columnDefinition = "text default 'BUG'", nullable = false)
    private String kind;

    @Column(name = "priority", columnDefinition = "text default 'P2'", nullable = false)
    private String priority;

    @Column(name = "labels", columnDefinition = "text")
    private String labels;

    @Column(name = "url", columnDefinition = "text")
    private String url;

    @Column(name = "source", columnDefinition = "text", nullable = false)
    private String source;

    @Column(name = "sync_status", columnDefinition = "text", nullable = false)
    private String syncStatus;

    @Column(name = "sync_error", columnDefinition = "text")
    private String syncError;

    @Column(name = "assignee", columnDefinition = "text")
    private String assignee;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

    @Column(name = "version", columnDefinition = "text default ''", nullable = false)
    private String version;

}
