package top.rongx.agent.domain.bugfix;

import java.util.List;

/**
 * 修复分支与 PR 端口。
 *
 * <p>约束：只能提交到 {@code agent/bugfix-*} 分支并创建 PR/MR，禁止直接推送默认分支、
 * 禁止自动合并——最终合并必须由人工完成。
 */
public interface BugFixGitPort {

    /**
     * 尽力同步远端最新代码（fetch）。
     *
     * <p>分析前调用，避免用一个过期的本地仓库算出错误的基线；实现失败只应告警，
     * 不应阻塞自动修复。
     */
    void sync(String projectId);

    /** 默认分支 HEAD 提交 sha（作为分析与修复的基线）。 */
    String headSha(String projectId);

    /** 把文件改动提交到 run.branch，返回新提交 sha。 */
    String commitFix(BugFixRun run, List<BugFixEdit> edits, String message);

    /** 修复提交相对基线的改动文件列表（相对路径），用于校验是否越界修改。 */
    List<String> changedFiles(BugFixRun run, String fixSha);

    /** 推送分支并创建 PR/MR（已存在同分支 PR 时直接返回其链接），返回可访问链接。 */
    String openPullRequest(BugFixRun run, String title, String body);
}
