package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code git_issue_events} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "git_issue_events")
public class GitIssueEventEntity {

    @Id
    @Column(name = "event_key", nullable = false, columnDefinition = "text")
    private String eventKey;

    @Column(name = "received_at", columnDefinition = "bigint", nullable = false)
    private Long receivedAt;

}
