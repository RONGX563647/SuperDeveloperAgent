package top.rongx.agent.domain.scaffold;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚手架产物：把"从仓库结构推导出的流水线脚本"交给网关预览/落盘。
 *
 * @param projectId 项目 id
 * @param stack     识别出的技术栈：JAVA / NODE / JAVA_NODE / UNKNOWN
 * @param files     生成的文件（路径相对仓库根 + 内容）
 * @param notes     面向人的说明与告警（缺 Dockerfile、多模块等）；已按根因去重
 * @param meta      结构化识别结果：target/端口/健康路径/工作目录/诊断分组等
 */
public record ScaffoldPlan(String projectId, String stack, List<ScaffoldFile> files,
                           List<String> notes, Map<String, Object> meta) {

    public ScaffoldPlan {
        notes = notes == null ? List.of() : List.copyOf(notes);
        // JSON 输出要稳定顺序；Map.copyOf 不保证顺序，这里用只读 LinkedHashMap
        meta = meta == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    /** 兼容旧构造：没有结构化元信息时退化为空 map（测试/手工装配用）。 */
    public ScaffoldPlan(String projectId, String stack, List<ScaffoldFile> files, List<String> notes) {
        this(projectId, stack, files, notes, Map.of());
    }

    /** 一个生成文件。path 使用正斜杠、相对仓库根。 */
    public record ScaffoldFile(String path, String content) {
    }
}
