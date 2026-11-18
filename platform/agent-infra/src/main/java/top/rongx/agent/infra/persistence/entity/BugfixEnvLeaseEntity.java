package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 由 {@code bugfix_env_leases} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "bugfix_env_leases")
@IdClass(BugfixEnvLeaseEntity.Key.class)
public class BugfixEnvLeaseEntity {

    @Id
    @Column(name = "project_id", nullable = false, columnDefinition = "text")
    private String projectId;

    @Id
    @Column(name = "env", nullable = false, columnDefinition = "text")
    private String env;

    @Column(name = "run_id", columnDefinition = "text", nullable = false)
    private String runId;

    @Column(name = "lease_until", columnDefinition = "bigint", nullable = false)
    private Long leaseUntil;

    /** 复合主键。 */
    public static class Key implements Serializable {
        private String projectId;
        private String env;

        public Key() {
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(projectId, that.projectId) && Objects.equals(env, that.env);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, env);
        }
    }
}
