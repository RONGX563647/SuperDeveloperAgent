package top.rongx.agent.domain.port;

import java.nio.file.Path;
import java.util.List;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;

/**
 * 项目脚手架端口：扫描仓库结构 → 生成平台专用流水线脚本。
 *
 * <p>目标：导入一个项目后不必手写 YAML，平台直接给出"可跑通"的
 * {@code ci/pipeline.yml}（+ 必要时 {@code ci/actions.yml}），人只需确认与微调。
 *
 * <p>安全：只读扫描仓库，只写 {@code ci/} 下声明的文件；不覆盖已有文件（除非显式允许）。
 */
public interface ProjectScaffolder {

    /** 扫描仓库并生成计划（不写盘）。 */
    ScaffoldPlan plan(String projectId, Path repoRoot, DeployTarget preferredTarget);

    /** 落盘：返回实际写入的相对路径；已存在且 overwrite=false 的文件会被跳过并记入返回值。 */
    List<String> apply(String projectId, Path repoRoot, ScaffoldPlan plan, boolean overwrite);
}
