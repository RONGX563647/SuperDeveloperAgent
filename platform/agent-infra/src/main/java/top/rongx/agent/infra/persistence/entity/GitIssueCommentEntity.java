package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code git_issue_comments} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "git_issue_comments")
public class GitIssueCommentEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "issue_id", columnDefinition = "text", nullable = false)
    private String issueId;

    @Column(name = "author", columnDefinition = "text")
    private String author;

    @Column(name = "body", columnDefinition = "text", nullable = false)
    private String body;

    @Column(name = "source", columnDefinition = "text", nullable = false)
    private String source;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

}
