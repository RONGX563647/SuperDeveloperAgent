package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 由 {@code project_env_overrides} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "project_env_overrides")
@IdClass(ProjectEnvOverrideEntity.Key.class)
public class ProjectEnvOverrideEntity {

    @Id
    @Column(name = "project_id", nullable = false, columnDefinition = "text")
    private String projectId;

    @Id
    @Column(name = "env", nullable = false, columnDefinition = "text")
    private String env;

    @Id
    @Column(name = "var_key", nullable = false, columnDefinition = "text")
    private String varKey;

    @Column(name = "value_enc", columnDefinition = "text", nullable = false)
    private String valueEnc;

    @Column(name = "value_iv", columnDefinition = "text", nullable = false)
    private String valueIv;

    @Column(name = "is_secret", columnDefinition = "bigint default 1", nullable = false)
    private Long isSecret;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

    @Column(name = "value_kind", columnDefinition = "text default 'INLINE'", nullable = false)
    private String valueKind;

    @Column(name = "secret_ref", columnDefinition = "text")
    private String secretRef;

    /** 复合主键。 */
    public static class Key implements Serializable {
        private String projectId;
        private String env;
        private String varKey;

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
            return Objects.equals(projectId, that.projectId) && Objects.equals(env, that.env) && Objects.equals(varKey, that.varKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(projectId, env, varKey);
        }
    }
}
