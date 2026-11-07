package top.rongx.agent.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import java.util.LinkedHashMap;
import java.util.Map;
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

    public AgentEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 转成 {@code {type, payload}}；序列化失败时退化为文本。 */
    public Map<String, Object> toDto(AgentEvent event) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("type", event.getClass().getSimpleName());
        try {
            dto.put("payload", objectMapper.convertValue(event, Map.class));
        } catch (IllegalArgumentException e) {
            dto.put("payload", Map.of("text", String.valueOf(event)));
        }
        return dto;
    }
}
