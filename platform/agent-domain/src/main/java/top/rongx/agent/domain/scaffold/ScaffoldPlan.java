package top.rongx.agent.domain.scaffold;

import java.util.List;

/**
 * 脚手架产物：把"从仓库结构推导出的流水线脚本"交给网关预览/落盘。
 *
 * @param projectId 项目 id
 * @param stack     识别出的技术栈：JAVA / NODE / JAVA_NODE / UNKNOWN
 * @param files     生成的文件（路径相对仓库根 + 内容）
 * @param notes     面向人的说明与告警（缺 Dockerfile、多模块等）
 */
public record ScaffoldPlan(String projectId, String stack, List<ScaffoldFile> files, List<String> notes) {

    /** 一个生成文件。path 使用正斜杠、相对仓库根。 */
    public record ScaffoldFile(String path, String content) {
    }
}
