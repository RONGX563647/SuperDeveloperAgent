package top.rongx.agent.domain.bugfix;

import java.util.List;
import top.rongx.agent.domain.patch.CodePatch;

/**
 * 修复分支与 PR 端口。
 *
 * <p>约束：只能提交到 {@code agent/bugfix-*} 分支并创建 PR/MR，禁止直接推送默认分支、
 * 禁止自动合并——最终合并必须由人工完成。
 */
public interface BugFixGitPort {

    /** 默认分支 HEAD 提交 sha（作为分析与修复的基线）。 */
    String headSha(String projectId);

    /** 把文件改动提交到 run.branch，返回新提交 sha。 */
    String commitFix(BugFixRun run, List<CodePatch.FileEdit> edits, String message);

    /** 推送分支并创建 PR/MR，返回可访问链接。 */
    String openPullRequest(BugFixRun run, String title, String body);
}
