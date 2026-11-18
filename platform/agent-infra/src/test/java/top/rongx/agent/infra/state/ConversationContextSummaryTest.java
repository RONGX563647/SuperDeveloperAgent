package top.rongx.agent.infra.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 上下文裁剪的摘要折叠：旧历史不能静默消失，关键标识要跨轮保留。 */
class ConversationContextSummaryTest {

    @Test
    @DisplayName("被裁掉的旧历史折叠成一条摘要，并保留 runId")
    void foldsDroppedHistoryAndKeepsIdentifiers() {
        List<Msg> messages = longConversation();

        AgentState trimmed = ConversationContextTrimmer.trim(state(messages), 1_500, 6, 4_000);

        Msg compact = trimmed.getContext().get(1);
        assertEquals("compaction", compact.getName(), "摘要必须紧跟在系统提示之后");
        assertEquals(MsgRole.USER, compact.getRole());
        assertEquals(Boolean.TRUE, compact.getMetadata().get(Msg.METADATA_SYNTHETIC));
        assertTrue(compact.getTextContent().contains("runId=abc12345"),
                "被折叠的工具结果里的 runId 必须保留");
        assertTrue(compact.getTextContent().startsWith("<compacted-history>"));
    }

    @Test
    @DisplayName("摘要长度不超过 summary-chars 预算")
    void summaryRespectsBudget() {
        List<Msg> messages = longConversation();

        AgentState trimmed = ConversationContextTrimmer.trim(state(messages), 1_500, 6, 600);

        Msg compact = trimmed.getContext().get(1);
        assertEquals("compaction", compact.getName());
        assertTrue(compact.getTextContent().length() <= 600,
                "摘要超预算会重新把上下文撑大：" + compact.getTextContent().length());
    }

    @Test
    @DisplayName("再次裁剪合并旧摘要：始终只有一条，旧事实不丢")
    void secondTrimMergesIntoExistingSummary() {
        AgentState once = ConversationContextTrimmer.trim(state(longConversation()), 1_500, 6, 4_000);
        AgentState twice = ConversationContextTrimmer.trim(once, 1_500, 6, 4_000);

        assertEquals(1, compactions(twice), "摘要消息不能叠加");
        assertTrue(compactionText(twice).contains("runId=abc12345"),
                "合并后仍要能查到最早的关键标识");
    }

    @Test
    @DisplayName("summary-chars=0 时退回直接丢弃，不生成摘要")
    void zeroSummaryFallsBackToPlainTrim() {
        List<Msg> messages = longConversation();

        AgentState trimmed = ConversationContextTrimmer.trim(state(messages), 1_500, 6, 0);

        assertEquals(0, compactions(trimmed));
        assertTrue(trimmed.getContext().size() < messages.size(), "仍然要裁剪");
    }

    private static List<Msg> longConversation() {
        List<Msg> messages = new ArrayList<>();
        messages.add(msg(MsgRole.SYSTEM, "sys"));
        messages.add(msg(MsgRole.USER, "最早的问题"));
        messages.add(tool("read_run_log", "构建失败 runId=abc12345 " + "log ".repeat(40)));
        for (int i = 0; i < 40; i++) {
            messages.add(msg(MsgRole.USER, "问题 " + i + " " + "x".repeat(100)));
            messages.add(msg(MsgRole.ASSISTANT, "答案 " + i));
            messages.add(tool("grep", "命中 " + i + " " + "y".repeat(100)));
        }
        return messages;
    }

    private static long compactions(AgentState state) {
        return state.getContext().stream()
                .filter(message -> "compaction".equals(message.getName()))
                .count();
    }

    private static String compactionText(AgentState state) {
        return state.getContext().stream()
                .filter(message -> "compaction".equals(message.getName()))
                .findFirst()
                .map(Msg::getTextContent)
                .orElse("");
    }

    private static AgentState state(List<Msg> context) {
        return AgentState.builder()
                .sessionId("s1")
                .userId("u1")
                .context(new ArrayList<>(context))
                .build();
    }

    private static Msg msg(MsgRole role, String text) {
        return Msg.builderForRole(role).textContent(text).build();
    }

    private static Msg tool(String name, String text) {
        return Msg.builderForRole(MsgRole.TOOL)
                .content(new ToolResultBlock("call-" + name, name,
                        TextBlock.builder().text(text).build()))
                .build();
    }
}
