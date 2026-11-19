package top.rongx.agent.domain.conversation;

import java.util.List;

/**
 * 服务端会话时间线仓库：只追加、按游标读取。
 *
 * <p>为什么独立于 {@link ConversationTranscriptRepository}：整包 transcript 由前端
 * 覆盖式上传（前端渲染模型的镜像），而这里记录的是服务端真实发生的事件流水，
 * 用于重启/换端后回放、以及后续的断线续传。两者互不覆盖。
 */
public interface ConversationTimelineRepository {

    /**
     * 追加一条时间线记录。
     *
     * @return 本条记录的序号（会话内单调递增）
     */
    long append(String conversationId, String kind, String role, String text, String tool,
                String toolCallId);

    /** 按 seq 游标增量读取；{@code afterSeq <= 0} 表示从头读。 */
    List<TimelineEntry> entries(String conversationId, long afterSeq, int limit);

    /** 会话删除时清空时间线。 */
    void deleteByConversation(String conversationId);
}
