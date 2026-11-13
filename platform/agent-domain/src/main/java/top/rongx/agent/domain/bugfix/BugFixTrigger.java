package top.rongx.agent.domain.bugfix;

import top.rongx.agent.domain.gitissue.GitIssue;

/** 任务创建后的自动修复触发器（由 webhook 与平台创建入口调用）。 */
public interface BugFixTrigger {

    /**
     * 新建任务时调用：只有 Bug 类型、开启策略、且同任务没有活动运行时才会入队。
     * 实现必须快速返回，真正的执行走异步。
     */
    void onIssueCreated(GitIssue issue);
}
