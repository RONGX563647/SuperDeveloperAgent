package top.rongx.agent.domain.conversation;

/**
 * 服务端会话时间线的一条记录。
 *
 * <p>与前端上传的整包 transcript 不同：这里由服务端按事件追加，用户输入、助手正文、
 * 工具调用、工具结果各占一条，重启后可继续按 {@code seq} 增量读取。
 *
 * @param seq            会话内单调递增的序号（游标）
 * @param conversationId 会话 id
 * @param kind           user / assistant / tool_call / tool_result
 * @param role           条目归属：user / assistant / tool
 * @param text           正文或工具结果（已截断，避免撑爆存储）
 * @param tool           工具名（非工具条目为空）
 * @param toolCallId     工具调用 id（非工具条目为空）
 * @param at             写入时间（毫秒）
 */
public record TimelineEntry(long seq, String conversationId, String kind, String role,
                            String text, String tool, String toolCallId, long at) {
}
