package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code bugfix_runs} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "bugfix_runs")
public class BugfixRunEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "issue_id", columnDefinition = "text", nullable = false)
    private String issueId;

    @Column(name = "user_id", columnDefinition = "text", nullable = false)
    private String userId;

    @Column(name = "project_id", columnDefinition = "text", nullable = false)
    private String projectId;

    @Column(name = "provider", columnDefinition = "text")
    private String provider;

    @Column(name = "repo", columnDefinition = "text")
    private String repo;

    @Column(name = "issue_number", columnDefinition = "text")
    private String issueNumber;

    @Column(name = "issue_title", columnDefinition = "text")
    private String issueTitle;

    @Column(name = "issue_body", columnDefinition = "text")
    private String issueBody;

    @Column(name = "branch", columnDefinition = "text")
    private String branch;

    @Column(name = "stage", columnDefinition = "text", nullable = false)
    private String stage;

    @Column(name = "status", columnDefinition = "text", nullable = false)
    private String status;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    @Column(name = "analysis", columnDefinition = "text")
    private String analysis;

    @Column(name = "analysis_json", columnDefinition = "text")
    private String analysisJson;

    @Column(name = "fix_summary", columnDefinition = "text")
    private String fixSummary;

    @Column(name = "base_sha", columnDefinition = "text")
    private String baseSha;

    @Column(name = "fix_sha", columnDefinition = "text")
    private String fixSha;

    @Column(name = "env", columnDefinition = "text")
    private String env;

    @Column(name = "pr_url", columnDefinition = "text")
    private String prUrl;

    @Column(name = "evidence", columnDefinition = "text")
    private String evidence;

    @Column(name = "attempt", columnDefinition = "bigint default 0", nullable = false)
    private Long attempt;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

    @Column(name = "finished_at", columnDefinition = "bigint default 0", nullable = false)
    private Long finishedAt;

}
