package top.rongx.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 服务端会话时间线：只追加的事件流水，seq 为读取游标。 */
@Entity
@Table(name = "conversation_timeline")
public class ConversationTimelineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seq")
    private Long seq;

    @Column(name = "conversation_id", nullable = false, columnDefinition = "text")
    private String conversationId;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "role", length = 32)
    private String role;

    @Column(name = "text", columnDefinition = "text")
    private String text;

    @Column(name = "tool", length = 128)
    private String tool;

    @Column(name = "tool_call_id", length = 128)
    private String toolCallId;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    protected ConversationTimelineEntity() {
    }

    public ConversationTimelineEntity(String conversationId, String kind, String role, String text,
                                      String tool, String toolCallId, long createdAt) {
        this.conversationId = conversationId;
        this.kind = kind;
        this.role = role;
        this.text = text;
        this.tool = tool;
        this.toolCallId = toolCallId;
        this.createdAt = createdAt;
    }

    public Long getSeq() {
        return seq;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getKind() {
        return kind;
    }

    public String getRole() {
        return role;
    }

    public String getText() {
        return text;
    }

    public String getTool() {
        return tool;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
