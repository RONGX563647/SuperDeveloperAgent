package top.rongx.agent.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.CustomEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AgentScope 事件 → 前端 DTO。
 *
 * <p>前端只依赖这里定义的稳定 wire type（{@code text.delta} / {@code tool.started} ...），
 * 不再依赖 AgentScope 的类名或枚举顺序。上游新增事件时统一落到
 * {@code agent.<enum>} 兜底命名，前端忽略未知类型即可，升级 AgentScope 不会打断渲染。
 */
@Component
public class AgentEventMapper {

    /** 协议版本：前端据此区分旧版类名协议与稳定事件协议。 */
    private static final int PROTOCOL_VERSION = 2;

    private final ObjectMapper objectMapper;
    /** 当前生效的模型名：用量事件本身不带模型名，前端需要它判断是否为 deepseek 系列。 */
    private final String modelName;

    public AgentEventMapper(ObjectMapper objectMapper,
                            @Value("${agent.model.model:}") String modelName) {
        this.objectMapper = objectMapper;
        this.modelName = modelName;
    }

    /** 转成 {@code {type, version, payload}}；序列化失败时退化为文本。 */
    public Map<String, Object> toDto(AgentEvent event) {
        return toDto(event, modelName);
    }

    /**
     * 同上，但由调用方给出本次实际使用的模型名（会话级选择）。
     *
     * <p>不能用配置里的静态模型名：同一个网关下不同会话可以选择不同模型，
     * 用量事件必须标实际模型，前端才能正确区分（例如 DeepSeek 计费）。
     */
    public Map<String, Object> toDto(AgentEvent event, String activeModelName) {
        Map<String, Object> dto = new LinkedHashMap<>();
        AgentEventType type = event.getType();
        // 选项卡片：ask_user 工具由运行时中间件翻译成 CustomEvent，这里换成稳定 wire type，
        // 前端只认 ask.requested 与归一化后的 payload（question / options / multiple / allowText）
        if (type == AgentEventType.CUSTOM && event instanceof CustomEvent custom
                && "ask_user".equals(custom.getName())) {
            dto.put("type", "ask.requested");
            dto.put("version", PROTOCOL_VERSION);
            dto.put("payload", new LinkedHashMap<>(custom.getValue()));
            return dto;
        }
        dto.put("type", wireType(type));
        dto.put("version", PROTOCOL_VERSION);
        try {
            Map<String, Object> payload = objectMapper.convertValue(event, Map.class);
            String effective = activeModelName == null || activeModelName.isBlank()
                    ? modelName : activeModelName;
            if (type == AgentEventType.MODEL_CALL_END
                    && effective != null && !effective.isBlank()) {
                payload.put("model", effective);
            }
            // 工具名在 AgentScope 里叫 toolCallName，稳定协议统一为 name，前端无需感知上游字段名
            Object toolName = payload.get("toolCallName");
            if (toolName != null
                    && (type == AgentEventType.TOOL_CALL_START || type == AgentEventType.TOOL_RESULT_END)) {
                payload.put("name", toolName);
            }
            dto.put("payload", payload);
        } catch (IllegalArgumentException e) {
            dto.put("payload", Map.of("text", String.valueOf(event)));
        }
        return dto;
    }

    /** 稳定 wire type：与前端 reducer 的契约，新增映射只需改这里。 */
    static String wireType(AgentEventType type) {
        return switch (type) {
            case AGENT_START -> "run.started";
            case AGENT_END -> "run.completed";
            case AGENT_RESULT -> "run.result";
            case MODEL_CALL_START -> "model.call.started";
            case MODEL_CALL_END -> "model.usage";
            case TEXT_BLOCK_START -> "text.start";
            case TEXT_BLOCK_DELTA -> "text.delta";
            case TEXT_BLOCK_END -> "text.end";
            case THINKING_BLOCK_START -> "assistant.thinking.start";
            case THINKING_BLOCK_DELTA -> "assistant.thinking.delta";
            case THINKING_BLOCK_END -> "assistant.thinking.end";
            case TOOL_CALL_START -> "tool.started";
            case TOOL_CALL_DELTA, TOOL_CALL_END -> "tool.arguments";
            case TOOL_RESULT_START -> "tool.result.start";
            case TOOL_RESULT_TEXT_DELTA -> "tool.result.delta";
            case TOOL_RESULT_DATA_DELTA -> "tool.result.data";
            case TOOL_RESULT_END -> "tool.completed";
            case REQUIRE_USER_CONFIRM -> "confirmation.required";
            case REQUIRE_EXTERNAL_EXECUTION -> "external.required";
            case USER_CONFIRM_RESULT -> "confirmation.result";
            case EXTERNAL_EXECUTION_RESULT -> "external.result";
            case EXCEED_MAX_ITERS -> "run.max_iters";
            case ALL_TOOLS_DENIED -> "tools.denied";
            case HINT_BLOCK -> "hint";
            default -> "agent." + type.name().toLowerCase(Locale.ROOT);
        };
    }
}
