package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code deploy_servers} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "deploy_servers")
public class DeployServerEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "text")
    private String id;

    @Column(name = "name", columnDefinition = "text", nullable = false)
    private String name;

    @Column(name = "host", columnDefinition = "text", nullable = false)
    private String host;

    @Column(name = "port", columnDefinition = "bigint default 22", nullable = false)
    private Long port;

    @Column(name = "ssh_user", columnDefinition = "text", nullable = false)
    private String sshUser;

    @Column(name = "auth_type", columnDefinition = "text", nullable = false)
    private String authType;

    @Column(name = "key_path", columnDefinition = "text")
    private String keyPath;

    @Column(name = "secret_enc", columnDefinition = "text")
    private String secretEnc;

    @Column(name = "secret_iv", columnDefinition = "text")
    private String secretIv;

    @Column(name = "remote_dir", columnDefinition = "text default '/opt/agent/deploy'", nullable = false)
    private String remoteDir;

    @Column(name = "enabled", columnDefinition = "bigint default 1", nullable = false)
    private Long enabled;

    @Column(name = "is_default", columnDefinition = "bigint default 0", nullable = false)
    private Long isDefault;

    @Column(name = "last_test_at", columnDefinition = "bigint")
    private Long lastTestAt;

    @Column(name = "last_test_ok", columnDefinition = "bigint")
    private Long lastTestOk;

    @Column(name = "last_test_msg", columnDefinition = "text")
    private String lastTestMsg;

    @Column(name = "updated_by", columnDefinition = "text")
    private String updatedBy;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

}
