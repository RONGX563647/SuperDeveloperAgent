package top.rongx.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** ask_user 工具调用 → ask.requested 事件（选项卡片的数据源）。 */
class AskUserMiddlewareTest {

    private final AskUserMiddleware middleware = new AskUserMiddleware();

    @Test
    void askUserCallEmitsCardBeforeToolRuns() {
        ToolUseBlock call = new ToolUseBlock("call-1", "ask_user", Map.of(
                "question", "要发布到哪个环境？",
                "options", List.of("test", "prod"),
                "allow_text", false));

        List<AgentEvent> events = middleware
                .onActing(null, null, new ActingInput(List.of(call)),
                        input -> Flux.<AgentEvent>empty())
                .collectList()
                .block();

        assertNotNull(events);
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof CustomEvent);
        CustomEvent event = (CustomEvent) events.get(0);
        assertEquals("ask_user", event.getName());
        assertEquals("要发布到哪个环境？", event.getValue().get("question"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options =
                (List<Map<String, Object>>) event.getValue().get("options");
        assertEquals(2, options.size());
        assertEquals("test", options.get(0).get("label"));
        // 单选：不下发 multiple；显式 false 的 allow_text 转成不出现 allowText
        assertEquals(null, event.getValue().get("multiple"));
        assertEquals(null, event.getValue().get("allowText"));
    }

    @Test
    void optionsAsObjectsKeepDetail() {
        ToolUseBlock call = new ToolUseBlock("call-2", "ask_user", Map.of(
                "question", "怎么处理？",
                "options", List.of(Map.of("label", "直接修", "detail", "风险较高")),
                "multiple", true));

        List<AgentEvent> events = middleware
                .onActing(null, null, new ActingInput(List.of(call)),
                        input -> Flux.<AgentEvent>empty())
                .collectList()
                .block();

        assertNotNull(events);
        assertEquals(1, events.size());
        CustomEvent event = (CustomEvent) events.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options =
                (List<Map<String, Object>>) event.getValue().get("options");
        assertEquals("直接修", options.get(0).get("label"));
        assertEquals("风险较高", options.get(0).get("detail"));
        assertEquals(Boolean.TRUE, event.getValue().get("multiple"));
    }

    @Test
    void allowTextIsOnByDefault() {
        // 选项覆盖不到的答案不应该逼用户二选一：缺省 allowText=true 由前端渲染输入框
        ToolUseBlock call = new ToolUseBlock("call-4", "ask_user", Map.of(
                "question", "继续吗？",
                "options", List.of("继续", "先停")));

        List<AgentEvent> events = middleware
                .onActing(null, null, new ActingInput(List.of(call)),
                        input -> Flux.<AgentEvent>empty())
                .collectList()
                .block();

        assertNotNull(events);
        assertEquals(1, events.size());
        CustomEvent event = (CustomEvent) events.get(0);
        assertEquals(Boolean.TRUE, event.getValue().get("allowText"));
    }

    @Test
    void incompleteAskDoesNotEmit() {
        // 没有问题：不产生卡片（模型会收到工具的 err 文本）
        ToolUseBlock call = new ToolUseBlock("call-3", "ask_user",
                Map.of("options", List.of("a", "b")));

        List<AgentEvent> events = middleware
                .onActing(null, null, new ActingInput(List.of(call)),
                        input -> Flux.<AgentEvent>empty())
                .collectList()
                .block();

        assertNotNull(events);
        assertTrue(events.isEmpty());
    }
}
