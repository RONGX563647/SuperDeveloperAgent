package top.rongx.agent.domain.bugfix;

import java.util.List;
import java.util.Optional;

/** 自动修复运行的持久化端口。 */
public interface BugFixRunStore {

    BugFixRun save(BugFixRun run);

    Optional<BugFixRun> find(String id);

    /** 按用户列出运行，可选按 issue 过滤；按创建时间倒序。 */
    List<BugFixRun> list(String userId, String issueId, int limit);

    /** 同一 issue 尚未结束的运行（用于幂等：一个任务同时只允许一个自动化）。 */
    Optional<BugFixRun> activeByIssue(String issueId);

    void appendEvent(BugFixEvent event);

    List<BugFixEvent> events(String runId);

    /** 进程重启时把残留的 RUNNING 标记为 BLOCKED，避免状态永远卡住。 */
    int markInterrupted();
}
