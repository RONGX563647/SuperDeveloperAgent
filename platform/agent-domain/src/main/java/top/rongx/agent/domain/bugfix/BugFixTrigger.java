package top.rongx.agent.domain.bugfix;

import top.rongx.agent.domain.gitissue.GitIssue;

/** 任务创建/更新后的自动修复触发器（由 webhook 与平台入口调用）。 */
public interface BugFixTrigger {

    /**
     * 新建任务时调用：只有 Bug 类型、开启策略、且同任务没有活动运行时才会入队。
     * 实现必须快速返回，真正的执行走异步。
     */
    void onIssueCreated(GitIssue issue);

    /**
     * 任务更新时调用（重新打开、补标签等）。默认与新建一致，由实现决定是否额外包含
     * 「被关闭又重开」等场景；关闭状态的任务永远不入队。
     */
    default void onIssueUpdated(GitIssue issue) {
        onIssueCreated(issue);
    }
}
