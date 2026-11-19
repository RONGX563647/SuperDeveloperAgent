package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 由 {@code conversation_messages} 的建表语句映射而来，schema 由 Hibernate 统一生成。 */
@Entity
@Table(name = "conversation_messages")
public class ConversationMessageEntity {

    @Id
    @Column(name = "conversation_id", nullable = false, columnDefinition = "text")
    private String conversationId;

    @Column(name = "payload", columnDefinition = "text", nullable = false)
    private String payload;

    @Column(name = "updated_at", columnDefinition = "bigint", nullable = false)
    private Long updatedAt;

}
