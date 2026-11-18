package top.rongx.agent.gateway.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code users} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(name = "user_id", nullable = false, columnDefinition = "text")
    private String userId;

    @Column(name = "display_name", columnDefinition = "text", nullable = false)
    private String displayName;

    @Column(name = "bcrypt_hash", columnDefinition = "text", nullable = false)
    private String bcryptHash;

    @Column(name = "role", columnDefinition = "text", nullable = false)
    private String role;

    @Column(name = "projects", columnDefinition = "text", nullable = false)
    private String projects;

}
