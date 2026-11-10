package top.rongx.agent.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 事件 → 前端 DTO。
 *
 * <p>骨架实现用反射拿类名 + Jackson 尽力序列化；正式版应改为按事件类型映射为稳定的
 * {@code type} 枚举（见 AgentScope 的 32 类事件），避免前端依赖内部类名。
 */
@Component
public class AgentEventMapper {

    private final ObjectMapper objectMapper;
    /** 当前生效的模型名：用量事件本身不带模型名，前端需要它判断是否为 deepseek 系列。 */
    private final String modelName;

    public AgentEventMapper(ObjectMapper objectMapper,
                            @Value("${agent.model.model:}") String modelName) {
        this.objectMapper = objectMapper;
        this.modelName = modelName;
    }

    /** 转成 {@code {type, payload}}；序列化失败时退化为文本。 */
    public Map<String, Object> toDto(AgentEvent event) {
        // 1. type 取事件简单类名（骨架实现；正式版应映射为稳定枚举）
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("type", event.getClass().getSimpleName());
        // 2. 尽力序列化 payload；失败则退化为文本，绝不抛错中断流
        try {
            Map<String, Object> payload = objectMapper.convertValue(event, Map.class);
            // 3. 模型调用结束事件补上模型名，供前端本地算价（仅展示用途，不影响模型调用）
            if (event instanceof ModelCallEndEvent && modelName != null && !modelName.isBlank()) {
                payload.put("model", modelName);
            }
            dto.put("payload", payload);
        } catch (IllegalArgumentException e) {
            dto.put("payload", Map.of("text", String.valueOf(event)));
        }
        return dto;
    }
}
