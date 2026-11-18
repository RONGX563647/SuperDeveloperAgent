package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code bugfix_policy} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "bugfix_policy")
public class BugfixPolicyEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "enabled", columnDefinition = "bigint default 0", nullable = false)
    private Long enabled;

    @Column(name = "auto_deploy", columnDefinition = "bigint default 1", nullable = false)
    private Long autoDeploy;

    @Column(name = "auto_create_pr", columnDefinition = "bigint default 1", nullable = false)
    private Long autoCreatePr;

    @Column(name = "test_env", columnDefinition = "text default 'test'", nullable = false)
    private String testEnv;

    @Column(name = "binding_id", columnDefinition = "text default ''", nullable = false)
    private String bindingId;

    @Column(name = "max_attempts", columnDefinition = "bigint default 2", nullable = false)
    private Long maxAttempts;

    @Column(name = "base_url", columnDefinition = "text default ''", nullable = false)
    private String baseUrl;

    @Column(name = "build_command", columnDefinition = "text default ''", nullable = false)
    private String buildCommand;

    @Column(name = "test_command", columnDefinition = "text default ''", nullable = false)
    private String testCommand;

    @Column(name = "updated_at", columnDefinition = "bigint default 0", nullable = false)
    private Long updatedAt;

}
