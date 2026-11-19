package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 持久化层自检与版本标记；同时作为 ORM 双库冒烟测试的最小实体。 */
@Entity
@Table(name = "platform_meta")
public class PlatformMetaEntity {

    @Id
    @Column(name = "meta_key", length = 128, nullable = false)
    private String key;

    @Column(name = "meta_value", columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    protected PlatformMetaEntity() {
    }

    public PlatformMetaEntity(String key, String value, long updatedAt) {
        this.key = key;
        this.value = value;
        this.updatedAt = updatedAt;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
