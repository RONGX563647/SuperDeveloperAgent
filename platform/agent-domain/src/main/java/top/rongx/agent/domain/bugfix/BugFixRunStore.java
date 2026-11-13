package top.rongx.agent.domain.bugfix;

import java.util.List;
import java.util.Optional;

/**
 * 自动修复运行的持久化端口。
 *
 * <p>除运行快照外，还承载三类「防并发/防丢失」数据：
 * <ul>
 *   <li>运行租约：多实例或线程重复调度时，只有拿到租约的一方可以推进；</li>
 *   <li>环境租约：同一项目的同一测试环境同一时间只允许一个运行部署；</li>
 *   <li>阶段记录：逐阶段保存尝试次数、耗时与证据，互不覆盖。</li>
 * </ul>
 */
public interface BugFixRunStore {

    BugFixRun save(BugFixRun run);

    Optional<BugFixRun> find(String id);

    /** 按用户列出运行，可选按 issue 过滤；按创建时间倒序。listAll=true 时不按用户过滤。 */
    List<BugFixRun> list(String userId, String issueId, int limit);

    /** 同一 issue 尚未结束的运行（用于幂等：一个任务同时只允许一个自动化）。 */
    Optional<BugFixRun> activeByIssue(String issueId);

    void appendEvent(BugFixEvent event);

    List<BugFixEvent> events(String runId);

    // ---------------------------------------------------------------- 租约

    /** 抢占运行租约；已有未过期租约且 owner 不是自己时返回 false。 */
    boolean tryAcquireLease(String runId, String owner, long ttlMs);

    /** 续租（owner 不匹配返回 false，调用方应放弃执行）。 */
    boolean renewLease(String runId, String owner, long ttlMs);

    void releaseLease(String runId, String owner);

    /** 抢占项目环境租约；同一 (projectId, env) 已有其他运行持有时返回 false。 */
    boolean acquireEnvLease(String projectId, String env, String runId, long ttlMs);

    void releaseEnvLease(String projectId, String env, String runId);

    void releaseEnvLeases(String runId);

    // ---------------------------------------------------------------- 阶段记录

    /** 开始一个阶段，返回记录 id。 */
    long startStage(String runId, BugFixStage stage, int attempt, long startedAt);

    void finishStage(long stageId, String status, String message, String artifact,
                     long endedAt, long durationMs);

    /** 关闭该运行所有仍在执行的阶段（取消/异常收尾）。 */
    void closeOpenStages(String runId, String status, String message, long endedAt);

    List<BugFixStageRun> stageRuns(String runId);

    /** 该运行某阶段已执行过的次数（用于阶段内尝试计数）。 */
    int stageAttempts(String runId, BugFixStage stage);

    /**
     * 进程启动时恢复：RUNNING 一律改回 QUEUED（可继续重试），清理过期租约，
     * 返回所有待执行（QUEUED）的运行 id。
     */
    List<String> recoverInterrupted();
}
