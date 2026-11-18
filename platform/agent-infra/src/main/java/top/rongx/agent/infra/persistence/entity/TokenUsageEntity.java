package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;

/** 由 {@code token_usage} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "token_usage")
public class TokenUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "session_id", columnDefinition = "text", nullable = false)
    private String sessionId;

    @Column(name = "project_id", columnDefinition = "text")
    private String projectId;

    @Column(name = "user_id", columnDefinition = "text")
    private String userId;

    @Column(name = "connection_id", columnDefinition = "text")
    private String connectionId;

    @Column(name = "model_name", columnDefinition = "text")
    private String modelName;

    @Column(name = "input_tokens", columnDefinition = "bigint default 0", nullable = false)
    private Long inputTokens;

    @Column(name = "cached_tokens", columnDefinition = "bigint default 0", nullable = false)
    private Long cachedTokens;

    @Column(name = "output_tokens", columnDefinition = "bigint default 0", nullable = false)
    private Long outputTokens;

    @Column(name = "total_tokens", columnDefinition = "bigint default 0", nullable = false)
    private Long totalTokens;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

}
