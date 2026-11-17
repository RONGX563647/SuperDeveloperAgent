package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 稳定事件协议：前端只依赖 {@code type} 字符串，不再依赖 AgentScope 类名。
 */
class AgentEventMapperTest {

    private final AgentEventMapper mapper =
            new AgentEventMapper(new ObjectMapper(), "deepseek-chat");

    @Test
    void textDeltaUsesStableWireType() {
        Map<String, Object> dto = mapper.toDto(new TextBlockDeltaEvent("reply-1", "block-1", "hi"));

        assertEquals("text.delta", dto.get("type"));
        assertEquals(2, dto.get("version"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) dto.get("payload");
        assertEquals("hi", payload.get("delta"));
        assertEquals("reply-1", payload.get("replyId"));
    }

    @Test
    void toolStartExposesNameInsteadOfUpstreamField() {
        Map<String, Object> dto = mapper.toDto(new ToolCallStartEvent("reply-1", "call-1", "grep"));

        assertEquals("tool.started", dto.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) dto.get("payload");
        assertEquals("grep", payload.get("name"));
        // 旧字段 toolCallName 保留兼容，前端只读 name
    }

    @Test
    void toolResultEndCarriesName() {
        Map<String, Object> dto = mapper.toDto(new ToolResultEndEvent(
                "reply-1", "call-1", "read_file", ToolResultState.SUCCESS));

        assertEquals("tool.completed", dto.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) dto.get("payload");
        assertEquals("read_file", payload.get("name"));
        assertEquals("success", String.valueOf(payload.get("state")).toLowerCase());
    }

    @Test
    void toolCallDeltaNormalizesArgumentsToArgs() {
        Map<String, Object> dto = mapper.toDto(
                new ToolCallDeltaEvent("reply-1", "call-1", "create_git_issue", "{\"title\":"));

        assertEquals("tool.arguments", dto.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) dto.get("payload");
        assertEquals("{\"title\":", payload.get("args"));
        assertEquals("call-1", payload.get("toolCallId"));
    }

    @Test
    void askUserCustomEventMapsToAskRequested() {
        Map<String, Object> dto = mapper.toDto(new CustomEvent("ask_user",
                Map.of("question", "要发布到哪个环境？",
                        "options", List.of(Map.of("label", "test"), Map.of("label", "prod")),
                        "allowText", true)));

        assertEquals("ask.requested", dto.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) dto.get("payload");
        assertEquals("要发布到哪个环境？", payload.get("question"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) payload.get("options");
        assertEquals(2, options.size());
        assertEquals("test", options.get(0).get("label"));
    }

    @Test
    void unknownTypesFallBackToPrefixedEnumName() {
        assertTrue(AgentEventMapper.wireType(AgentEventType.HINT_BLOCK).equals("hint"));
        assertTrue(AgentEventMapper.wireType(AgentEventType.SUBAGENT_EXPOSED)
                .startsWith("agent."));
        assertTrue(AgentEventMapper.wireType(AgentEventType.CUSTOM).startsWith("agent."));
    }

    @Test
    void agentLifecycleTypesAreStable() {
        assertEquals("run.completed", AgentEventMapper.wireType(AgentEventType.AGENT_END));
        assertEquals("model.usage", AgentEventMapper.wireType(AgentEventType.MODEL_CALL_END));
        assertEquals("tool.arguments", AgentEventMapper.wireType(AgentEventType.TOOL_CALL_DELTA));
        assertEquals("confirmation.required",
                AgentEventMapper.wireType(AgentEventType.REQUIRE_USER_CONFIRM));
    }
}
