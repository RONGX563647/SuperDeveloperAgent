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
import top.rongx.agent.domain.error.PlatformException;

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
            // 工具入参增量：上游字段名在不同版本间是 delta / args / arguments，
            // 稳定协议统一为 args，前端只累积这一个字段，不再猜字段名
            if (type == AgentEventType.TOOL_CALL_DELTA) {
                Object args = firstNonNull(payload, "delta", "args", "arguments", "input",
                        "toolArguments");
                if (args != null) {
                    payload.put("args", args);
                }
            }
            // 工具调用/结果事件同样要能拿到入参：
            // 插件类工具与 MCP 工具（workspace_* / mcp_*）走的不是 TOOL_CALL_DELTA 那条路，
            // 只在 delta 上补 args 会让它们在轨迹里永远是空参数。
            if (type == AgentEventType.TOOL_CALL_START || type == AgentEventType.TOOL_RESULT_END) {
                normalizeToolArgs(payload);
            }
            dto.put("payload", payload);
        } catch (PlatformException | IllegalArgumentException e) {
            dto.put("payload", Map.of("text", String.valueOf(event)));
        }
        return dto;
    }

    /**
     * 工具入参归一化：把各家来源的字段统一成 {@code args} + {@code argsUnavailable}。
     *
     * <p>契约（前端 reducer 与轨迹页都依赖它）：
     * <ul>
     *   <li>有入参 → {@code args} 为原始字符串/对象，{@code argsUnavailable=false}；</li>
     *   <li>确实没有入参（无参工具）→ {@code args=null}，{@code argsUnavailable=false}；</li>
     *   <li>拿不到入参（上游没带、被裁剪）→ {@code args=null}，{@code argsUnavailable=true}。</li>
     * </ul>
     * 第三种必须与第二种区分开：否则"空参数"和"参数丢失"在轨迹里长得一样，
     * 调试时无法判断是工具真的无参，还是映射漏了。
     */
    private static void normalizeToolArgs(Map<String, Object> payload) {
        Object args = firstNonNull(payload, "args", "arguments", "input", "toolArguments", "delta");
        if (args != null) {
            payload.put("args", args);
            payload.put("argsUnavailable", Boolean.FALSE);
            return;
        }
        // 无参工具与"参数丢失"都表现为没有 args；前者由工具 schema 决定，
        // 这里只能如实标记为「不可用」并给出提示，不伪造一个空对象。
        payload.put("args", null);
        payload.put("argsUnavailable", Boolean.TRUE);
    }

    /** 按顺序取第一个非空字段（上游字段名在版本间会变）。 */
    private static Object firstNonNull(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
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
