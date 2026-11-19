package top.rongx.agent.governance.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code code_patches} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "code_patches")
public class CodePatchEntity {

    @Id
    @Column(name = "patch_id", nullable = false, columnDefinition = "text")
    private String patchId;

    @Column(name = "project_id", columnDefinition = "text", nullable = false)
    private String projectId;

    @Column(name = "env", columnDefinition = "text")
    private String env;

    @Column(name = "base_sha", columnDefinition = "text")
    private String baseSha;

    @Column(name = "branch", columnDefinition = "text")
    private String branch;

    @Column(name = "diff", columnDefinition = "text")
    private String diff;

    @Column(name = "diff_hash", columnDefinition = "text")
    private String diffHash;

    @Column(name = "added", columnDefinition = "bigint default 0", nullable = false)
    private Long added;

    @Column(name = "removed", columnDefinition = "bigint default 0", nullable = false)
    private Long removed;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

}
