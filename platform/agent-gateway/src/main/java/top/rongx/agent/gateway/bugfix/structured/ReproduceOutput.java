package top.rongx.agent.gateway.bugfix.structured;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** 复现阶段的结构化输出。 */
public record ReproduceOutput(
        @JsonPropertyDescription("是否在测试环境真实复现了该 Bug")
        Boolean reproduced,
        @JsonPropertyDescription("实际执行的复现步骤")
        List<String> steps,
        @JsonPropertyDescription("真实观察到的日志、响应或页面现象；不能只写“已复现”")
        String evidence,
        @JsonPropertyDescription("复现结论置信度，0 到 1 之间的小数")
        Double confidence) {
}
