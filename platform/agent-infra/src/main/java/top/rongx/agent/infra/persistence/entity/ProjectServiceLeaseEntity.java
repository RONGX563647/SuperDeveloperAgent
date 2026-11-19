package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 由 {@code project_service_leases} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "project_service_leases")
@IdClass(ProjectServiceLeaseEntity.Key.class)
public class ProjectServiceLeaseEntity {

    @Id
    @Column(name = "project_id", nullable = false, columnDefinition = "text")
    private String projectId;

    @Id
    @Column(name = "env", nullable = false, columnDefinition = "text")
    private String env;

    @Id
    @Column(name = "service", nullable = false, columnDefinition = "text")
    private String service;

    @Column(name = "host_port", columnDefinition = "bigint", nullable = false)
    private Long hostPort;

    @Column(name = "status", columnDefinition = "text default 'STOPPED'", nullable = false)
    private String status;

    @Column(name = "pid", columnDefinition = "bigint default 0", nullable = false)
    private Long pid;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

    /** 复合主键。 */
    public static class Key implements Serializable {
        private String projectId;
        private String env;
        private String service;

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
            return Objects.equals(projectId, that.projectId) && Objects.equals(env, that.env) && Objects.equals(service, that.service);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, env, service);
        }
    }
}
