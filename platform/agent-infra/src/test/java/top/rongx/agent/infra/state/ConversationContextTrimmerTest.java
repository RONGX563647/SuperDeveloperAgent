package top.rongx.agent.infra.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 上下文预算：保留系统提示、窗口不以 TOOL 开头、不修改原状态。 */
class ConversationContextTrimmerTest {

    @Test
    @DisplayName("未超预算时原样返回")
    void keepsSmallContext() {
        AgentState state = state(List.of(msg(MsgRole.USER, "hi"), msg(MsgRole.ASSISTANT, "hello")));

        assertSame(state, ConversationContextTrimmer.trim(state, 10_000, 24));
    }

    @Test
    @DisplayName("超预算时保留系统提示与最近消息，且窗口不以 TOOL 开头")
    void trimsOldestAndKeepsSystem() {
        List<Msg> messages = new ArrayList<>();
        messages.add(msg(MsgRole.SYSTEM, "系统提示"));
        for (int i = 0; i < 40; i++) {
            messages.add(msg(MsgRole.USER, "问题 " + i + " " + "x".repeat(100)));
            messages.add(msg(MsgRole.ASSISTANT, "答案 " + i));
            messages.add(msg(MsgRole.TOOL, "工具结果 " + i));
        }
        AgentState state = state(messages);

        AgentState trimmed = ConversationContextTrimmer.trim(state, 1_500, 6);

        assertNotSame(state, trimmed, "不能原地修改运行中的状态");
        assertEquals(messages.size(), state.getContext().size(), "原状态必须保持不变");
        assertEquals(MsgRole.SYSTEM, trimmed.getContext().get(0).getRole());
        assertTrue(trimmed.getContext().size() < messages.size());
        assertTrue(trimmed.getContext().size() >= 7, "系统提示 + 至少最近若干条");
        for (int i = 1; i < trimmed.getContext().size(); i++) {
            if (i == 1) {
                assertTrue(trimmed.getContext().get(i).getRole() != MsgRole.TOOL,
                        "裁剪窗口不能以 TOOL 开头");
            }
        }
    }

    @Test
    @DisplayName("重复裁剪是幂等的")
    void trimIsIdempotent() {
        List<Msg> messages = new ArrayList<>();
        messages.add(msg(MsgRole.SYSTEM, "sys"));
        for (int i = 0; i < 30; i++) {
            messages.add(msg(MsgRole.USER, "u" + i + " " + "y".repeat(200)));
            messages.add(msg(MsgRole.ASSISTANT, "a" + i));
        }
        AgentState once = ConversationContextTrimmer.trim(state(messages), 1_000, 4);
        AgentState twice = ConversationContextTrimmer.trim(once, 1_000, 4);

        assertEquals(once.getContext().size(), twice.getContext().size());
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
}
