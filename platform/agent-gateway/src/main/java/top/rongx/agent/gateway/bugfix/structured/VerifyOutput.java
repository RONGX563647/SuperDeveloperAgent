package top.rongx.agent.gateway.bugfix.structured;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** 验证阶段的结构化输出。 */
public record VerifyOutput(
        @JsonPropertyDescription("重新执行复现步骤后，Bug 是否已解决")
        Boolean fixed,
        @JsonPropertyDescription("实际执行的验证步骤")
        List<String> steps,
        @JsonPropertyDescription("真实观察到的日志、响应或页面状态；不能只写“已修复”")
        String evidence,
        @JsonPropertyDescription("验证结论置信度，0 到 1 之间的小数")
        Double confidence,
        @JsonPropertyDescription("仍然存在的风险或未覆盖的场景")
        String remainingRisk) {
}
