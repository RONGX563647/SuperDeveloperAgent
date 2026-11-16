package top.rongx.agent.domain.conversation;

import java.util.Optional;

/**
 * 会话时间线（消息 + 工具结果）持久化。
 *
 * <p>时间线是前端投影出来的结构，以不透明 JSON 文本整体存取，
 * 服务端不解析内容，只负责按会话保存，让换浏览器 / 重启后端后历史仍可恢复。
 */
public interface ConversationTranscriptRepository {

    Optional<String> findTranscript(String conversationId);

    void saveTranscript(String conversationId, String payload);
}
