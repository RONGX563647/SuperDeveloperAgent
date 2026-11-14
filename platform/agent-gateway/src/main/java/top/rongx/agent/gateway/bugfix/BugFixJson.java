package top.rongx.agent.gateway.bugfix;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 结构化结果的序列化/反序列化。
 *
 * <p>阶段结果由 AgentScope 的结构化输出通道（JSON Schema）直接给出强类型对象，
 * 这里只负责把对象落库为 JSON 工件，以及把工件读回强类型对象做后续阶段的输入，
 * 不再从自然语言正文里“猜”JSON。
 */
@Component
public class BugFixJson {

    private final ObjectMapper mapper;

    public BugFixJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 把强类型结构化结果序列化为落库工件（analysisJson 等）。 */
    public String toJson(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("结构化结果序列化失败：" + e.getMessage(), e);
        }
    }

    /** 从落库的 JSON 工件读回强类型结果；格式不符返回 empty，由调用方决定如何降级。 */
    public <T> Optional<T> read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(mapper.readValue(json, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
