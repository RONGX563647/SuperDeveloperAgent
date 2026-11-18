package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 由 {@code model_catalog} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "model_catalog")
@IdClass(ModelCatalogEntity.Key.class)
public class ModelCatalogEntity {

    @Id
    @Column(name = "connection_id", nullable = false, columnDefinition = "text")
    private String connectionId;

    @Id
    @Column(name = "model_id", nullable = false, columnDefinition = "text")
    private String modelId;

    @Column(name = "display_name", columnDefinition = "text", nullable = false)
    private String displayName;

    @Column(name = "source", columnDefinition = "text", nullable = false)
    private String source;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

    /** 复合主键。 */
    public static class Key implements Serializable {
        private String connectionId;
        private String modelId;

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
            return Objects.equals(connectionId, that.connectionId) && Objects.equals(modelId, that.modelId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(connectionId, modelId);
        }
    }
}
