package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 由 {@code deploy_port_leases} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "deploy_port_leases")
@IdClass(DeployPortLeaseEntity.Key.class)
public class DeployPortLeaseEntity {

    @Id
    @Column(name = "project_id", nullable = false, columnDefinition = "text")
    private String projectId;

    @Id
    @Column(name = "env", nullable = false, columnDefinition = "text")
    private String env;

    @Id
    @Column(name = "container_port", nullable = false)
    private Long containerPort;

    @Column(name = "host_port", columnDefinition = "bigint", nullable = false)
    private Long hostPort;

    @Column(name = "run_id", columnDefinition = "text")
    private String runId;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

    /** 复合主键。 */
    public static class Key implements Serializable {
        private String projectId;
        private String env;
        private Long containerPort;

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
            return Objects.equals(projectId, that.projectId) && Objects.equals(env, that.env) && Objects.equals(containerPort, that.containerPort);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, env, containerPort);
        }
    }
}
