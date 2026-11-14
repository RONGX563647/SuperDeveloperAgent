package top.rongx.agent.gateway.bugfix.structured;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * 分析阶段的结构化输出（由 JSON Schema 约束，模型不能自由发挥格式）。
 *
 * <p>字段可空，由阶段做语义校验：缺失根因/证据或置信度过低时停到人工处理，
 * 不猜默认值继续跑。
 */
public record AnalyzeOutput(
        @JsonPropertyDescription("Bug 影响的版本或版本范围，例如 1.4.x / v2.0.0 之后")
        String version,
        @JsonPropertyDescription("相关提交范围，尽量给出起止 commit 或时间范围")
        String commitRange,
        @JsonPropertyDescription("最可能的根因，必须结合具体代码路径说明")
        String rootCause,
        @JsonPropertyDescription("可执行的复现步骤，按顺序排列")
        List<String> reproSteps,
        @JsonPropertyDescription("最可能需要修改的仓库相对路径（不要绝对路径）")
        List<String> targetFiles,
        @JsonPropertyDescription("分析置信度，0 到 1 之间的小数")
        Double confidence) {
}
