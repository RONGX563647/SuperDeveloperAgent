package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 由 {@code project_envs} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "project_envs")
@IdClass(ProjectEnvEntity.Key.class)
public class ProjectEnvEntity {

    @Id
    @Column(name = "project_id", nullable = false, columnDefinition = "text")
    private String projectId;

    @Id
    @Column(name = "env", nullable = false, columnDefinition = "text")
    private String env;

    @Column(name = "app_url", columnDefinition = "text")
    private String appUrl;

    @Column(name = "compose_dir", columnDefinition = "text")
    private String composeDir;

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
