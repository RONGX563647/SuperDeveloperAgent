package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code model_connections} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "model_connections")
public class ModelConnectionEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "name", columnDefinition = "text", nullable = false)
    private String name;

    @Column(name = "provider", columnDefinition = "text", nullable = false)
    private String provider;

    @Column(name = "base_url", columnDefinition = "text", nullable = false)
    private String baseUrl;

    @Column(name = "api_key_enc", columnDefinition = "text")
    private String apiKeyEnc;

    @Column(name = "api_key_iv", columnDefinition = "text")
    private String apiKeyIv;

    @Column(name = "model_name", columnDefinition = "text", nullable = false)
    private String modelName;

    @Column(name = "temperature", columnDefinition = "double precision default 0.2", nullable = false)
    private Double temperature;

    @Column(name = "max_iters", columnDefinition = "bigint default 8", nullable = false)
    private Long maxIters;

    @Column(name = "enabled", columnDefinition = "bigint default 1", nullable = false)
    private Long enabled;

    @Column(name = "is_default", columnDefinition = "bigint default 0", nullable = false)
    private Long isDefault;

    @Column(name = "last_test_at", columnDefinition = "bigint")
    private Long lastTestAt;

    @Column(name = "last_test_ok", columnDefinition = "bigint")
    private Long lastTestOk;

    @Column(name = "last_test_msg", columnDefinition = "text")
    private String lastTestMsg;

    @Column(name = "last_test_latency_ms", columnDefinition = "bigint")
    private Long lastTestLatencyMs;

    @Column(name = "updated_by", columnDefinition = "text")
    private String updatedBy;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

}
