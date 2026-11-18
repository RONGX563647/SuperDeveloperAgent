package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code projects} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "projects")
public class ProjectEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "name", columnDefinition = "text", nullable = false)
    private String name;

    @Column(name = "repo_url", columnDefinition = "text")
    private String repoUrl;

    @Column(name = "repo_type", columnDefinition = "text")
    private String repoType;

    @Column(name = "type", columnDefinition = "text")
    private String type;

    @Column(name = "port_start", columnDefinition = "bigint")
    private Long portStart;

    @Column(name = "port_end", columnDefinition = "bigint")
    private Long portEnd;

    @Column(name = "source", columnDefinition = "text", nullable = false)
    private String source;

    @Column(name = "local_path", columnDefinition = "text")
    private String localPath;

    @Column(name = "storage_mode", columnDefinition = "text default 'MANAGED'", nullable = false)
    private String storageMode;

    @Column(name = "created_at", columnDefinition = "bigint", nullable = false)
    private Long createdAt;

}
