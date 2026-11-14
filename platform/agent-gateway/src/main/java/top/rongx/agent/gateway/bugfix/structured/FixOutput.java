package top.rongx.agent.gateway.bugfix.structured;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * 修复阶段的结构化输出。
 *
 * <p>两种提交方式二选一：{@code edits} 精确片段替换（优先），{@code files} 整文件内容。
 * 二者同时出现或路径重复会被校验器拒绝，避免“修一半”或互相覆盖。
 */
public record FixOutput(
        @JsonPropertyDescription("本次修复的简短说明，会写入 PR 描述")
        String summary,
        @JsonPropertyDescription("精确片段替换列表；old 必须与文件内容完全一致且唯一")
        List<Edit> edits,
        @JsonPropertyDescription("整文件重写列表；仅用于新建文件或小文件")
        List<FileOutput> files) {

    /** 精确片段替换。 */
    public record Edit(
            @JsonPropertyDescription("仓库相对路径")
            String path,
            @JsonPropertyDescription("待替换的原片段，必须与文件内容完全一致")
            @JsonProperty("old") String oldText,
            @JsonPropertyDescription("替换后的片段；留空表示删除该片段")
            @JsonProperty("new") String newText) {
    }

    /** 整文件内容。 */
    public record FileOutput(
            @JsonPropertyDescription("仓库相对路径")
            String path,
            @JsonPropertyDescription("文件完整内容")
            String content) {
    }
}
