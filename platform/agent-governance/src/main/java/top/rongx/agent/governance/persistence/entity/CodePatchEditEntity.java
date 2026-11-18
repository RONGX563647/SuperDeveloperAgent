package top.rongx.agent.governance.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 由 {@code code_patch_edits} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "code_patch_edits")
@IdClass(CodePatchEditEntity.Key.class)
public class CodePatchEditEntity {

    @Id
    @Column(name = "patch_id", nullable = false, columnDefinition = "text")
    private String patchId;

    @Id
    @Column(name = "seq", nullable = false)
    private Long seq;

    @Column(name = "path", columnDefinition = "text", nullable = false)
    private String path;

    @Column(name = "content", columnDefinition = "text", nullable = false)
    private String content;

    /** 复合主键。 */
    public static class Key implements Serializable {
        private String patchId;
        private Long seq;

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
            return Objects.equals(patchId, that.patchId) && Objects.equals(seq, that.seq);
        }

        @Override
        public int hashCode() {
            return Objects.hash(patchId, seq);
        }
    }
}
