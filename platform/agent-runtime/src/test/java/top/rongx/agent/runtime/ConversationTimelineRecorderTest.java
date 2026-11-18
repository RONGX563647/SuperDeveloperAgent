package top.rongx.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.conversation.ConversationTimelineRepository;
import top.rongx.agent.domain.conversation.TimelineEntry;

/** 服务端时间线记录：只追加、正文合并、工具事件各一条、收尾幂等。 */
class ConversationTimelineRecorderTest {

    private final FakeTimeline repository = new FakeTimeline();
    private final ConversationTimelineRecorder recorder = new ConversationTimelineRecorder(repository);

    @Test
    @DisplayName("一轮对话落成：用户 → 工具调用 → 工具结果 → 合并后的正文")
    void recordsTurnAsAppendOnlyEntries() {
        ConversationTimelineRecorder.Recorder rec = recorder.begin("s-1");
        rec.user("修一下构建");
        rec.onEvent(new ToolCallStartEvent("reply-1", "call-1", "read_file"));
        rec.onEvent(new ToolResultEndEvent("reply-1", "call-1", "read_file",
                ToolResultState.SUCCESS));
        rec.onEvent(new TextBlockDeltaEvent("reply-1", "b-1", "构建"));
        rec.onEvent(new TextBlockDeltaEvent("reply-1", "b-1", "已修复"));
        rec.finish("");

        assertEquals(List.of("user", "tool_call", "tool_result", "assistant"),
                repository.rows.stream().map(TimelineEntry::kind).toList());
        TimelineEntry user = repository.rows.get(0);
        assertEquals("修一下构建", user.text());
        assertEquals("read_file", repository.rows.get(1).tool());
        assertEquals("call-1", repository.rows.get(2).toolCallId());
        // 流式增量必须合并成一条正文，否则时间线会被 token 打爆
        assertEquals("构建已修复", repository.rows.get(3).text());
    }

    @Test
    @DisplayName("收尾与中断说明幂等：重复 finish 不重复写，异常也留痕")
    void finishIsIdempotentAndRecordsFailure() {
        ConversationTimelineRecorder.Recorder rec = recorder.begin("s-2");
        rec.user("跑一下");
        rec.finish("模型超时");
        rec.finish("");

        long assistants = repository.rows.stream()
                .filter(e -> "assistant".equals(e.kind())).count();
        assertEquals(1, assistants, "重复 finish 不应再追加："
                + repository.rows.stream().map(TimelineEntry::text).toList());
        assertTrue(repository.rows.get(1).text().contains("模型超时"), "中断原因要落盘");
    }

    @Test
    @DisplayName("空白会话不写库：没有 sessionId 的请求不应产生孤儿记录")
    void blankConversationIsIgnored() {
        ConversationTimelineRecorder.Recorder rec = recorder.begin(" ");
        rec.user("hi");
        rec.finish("");

        assertTrue(repository.rows.isEmpty(), "无会话 id 时不应落盘：" + repository.rows);
        assertFalse(repository.failed, "也不应把空会话当成错误");
    }

    /** 内存时间线仓库：记录写入内容，模拟持久层。 */
    private static final class FakeTimeline implements ConversationTimelineRepository {
        private final List<TimelineEntry> rows = new ArrayList<>();
        private boolean failed;

        @Override
        public long append(String conversationId, String kind, String role, String text,
                           String tool, String toolCallId) {
            long seq = rows.size() + 1;
            rows.add(new TimelineEntry(seq, conversationId, kind, role, text, tool, toolCallId,
                    System.currentTimeMillis()));
            return seq;
        }

        @Override
        public List<TimelineEntry> entries(String conversationId, long afterSeq, int limit) {
            return rows.stream().filter(e -> e.seq() > afterSeq).limit(limit).toList();
        }

        @Override
        public void deleteByConversation(String conversationId) {
            rows.removeIf(e -> e.conversationId().equals(conversationId));
        }
    }
}
