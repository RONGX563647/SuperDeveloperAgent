package top.rongx.agent.gateway.bugfix;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.bugfix.BugFixEvent;
import top.rongx.agent.domain.bugfix.BugFixNotificationStore;
import top.rongx.agent.domain.bugfix.BugFixNotifier;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixPolicyStore;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixRunArtifacts;
import top.rongx.agent.domain.bugfix.BugFixRunStore;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;
import top.rongx.agent.domain.bugfix.BugFixStageRun;
import top.rongx.agent.domain.bugfix.BugFixStatus;
import top.rongx.agent.domain.bugfix.BugFixTransitions;
import top.rongx.agent.domain.gitissue.GitIssue;

/**
 * Bug 自动修复编排器：把「分析 → 复现 → 修复 → 验证 → PR」串成可持久化的状态机。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>阶段边界落库</b>：每完成一个阶段就整体保存快照，并单独记录阶段尝试/耗时/证据；</li>
 *   <li><b>证据驱动</b>：阶段只能返回 CONTINUE / BLOCKED / FAILED，不能跳阶段；</li>
 *   <li><b>验证不通过回到修复</b>：VERIFY 失败且仍有尝试次数时回到 FIX，而不是直接终态；</li>
 *   <li><b>幂等</b>：同一 Issue 同时只允许一个活动运行；运行租约保证多实例只有一个执行者；</li>
 *   <li><b>并发受控</b>：全局最多 2 个运行，同一项目串行，测试环境再加一层租约；</li>
 *   <li><b>重启恢复</b>：启动时把 RUNNING 改回 QUEUED 并全部重新入队，不再永久卡住；</li>
 *   <li><b>取消安全</b>：取消会协作中断 Agent 流并关闭阶段记录，迟到的阶段结果一律丢弃。</li>
 * </ul>
 */
@Service
public class BugFixOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BugFixOrchestrator.class);
    private static final int MAX_CONCURRENT_RUNS = 2;
    private static final int LOCK_STRIPES = 64;
    /** 租约 30 分钟：大于单阶段 Agent 超时（15 分钟），避免执行中被误抢占。 */
    private static final long LEASE_TTL_MS = 30 * 60 * 1000L;

    private final BugFixRunStore runs;
    private final BugFixPolicyStore policies;
    private final BugFixNotifier notifier;
    private final BugFixNotificationStore notifications;
    private final BugFixCancellationRegistry cancellations;
    private final Map<BugFixStage, BugFixStageHandler> handlers;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("bugfix-retry").factory());
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_RUNS);
    private final Map<String, Semaphore> projectLocks = new ConcurrentHashMap<>();
    private final Set<String> executing = ConcurrentHashMap.newKeySet();
    private final Set<String> rerunRequested = ConcurrentHashMap.newKeySet();
    private final Object[] runLocks = new Object[LOCK_STRIPES];
    private final String instanceId = "gw-" + UUID.randomUUID().toString().substring(0, 8);

    public BugFixOrchestrator(BugFixRunStore runs, BugFixPolicyStore policies,
                              List<BugFixStageHandler> handlers, BugFixNotifier notifier,
                              BugFixNotificationStore notifications,
                              BugFixCancellationRegistry cancellations) {
        this.runs = runs;
        this.policies = policies;
        this.notifier = notifier;
        this.notifications = notifications;
        this.cancellations = cancellations;
        this.handlers = handlers.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        BugFixStageHandler::stage, handler -> handler, (a, b) -> a));
        for (int i = 0; i < runLocks.length; i++) {
            runLocks[i] = new Object();
        }
    }

    /** 重启时把残留的 RUNNING 改回 QUEUED，并重新入队所有排队运行。 */
    @PostConstruct
    void recoverInterrupted() {
        List<String> queued = runs.recoverInterrupted();
        if (!queued.isEmpty()) {
            log.warn("检测到 {} 个自动修复运行，已恢复排队并重新提交", queued.size());
            queued.forEach(this::submit);
        }
    }

    /** 为 Bug 任务创建运行并入队（手动触发也走这里）。 */
    public BugFixRun start(GitIssue issue) {
        if (issue == null) {
            throw new IllegalArgumentException("任务不能为空");
        }
        if (issue.projectId() == null || issue.projectId().isBlank()) {
            throw new IllegalArgumentException("任务未关联项目，无法自动修复");
        }
        if (runs.activeByIssue(issue.id()).isPresent()) {
            throw new IllegalStateException("该任务已有进行中的自动修复");
        }
        // 已成功提过 PR 的任务不再重复跑，避免为同一个 Bug 建出多个 PR
        runs.list(issue.userId(), issue.id(), 10).stream()
                .filter(run -> (run.status() == BugFixStatus.SUCCEEDED
                        || run.status() == BugFixStatus.WAITING_MERGE)
                        && run.prUrl() != null && !run.prUrl().isBlank())
                .findFirst()
                .ifPresent(run -> {
                    throw new IllegalStateException("该任务已自动修复并创建 PR：" + run.prUrl());
                });
        long now = System.currentTimeMillis();
        String id = "bf-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String branch = "agent/bugfix-" + id.substring(3);
        BugFixRun run = new BugFixRun(
                id, issue.id(), issue.userId(), issue.projectId(), issue.provider(), issue.repo(),
                issue.number(), issue.title(), issue.body(), branch,
                BugFixStage.ANALYZE, BugFixStatus.QUEUED, "已入队，等待执行",
                BugFixRunArtifacts.EMPTY, 0, now, now, 0);
        runs.save(run);
        runs.appendEvent(BugFixEvent.info(id, BugFixStage.ANALYZE, "自动修复已入队"));
        submit(id);
        return run;
    }

    /** 重试：从当前未完成阶段继续（BLOCKED / FAILED / CANCELLED 均可）。 */
    public BugFixRun resume(String userId, String id) {
        synchronized (lockFor(id)) {
            BugFixRun run = require(userId, id);
            if (run.stage() == BugFixStage.DONE) {
                throw new IllegalStateException("该运行已完成，无需重试");
            }
            if (run.status() == BugFixStatus.WAITING_MERGE) {
                throw new IllegalStateException("PR 正在等待人工合并，无需重试");
            }
            if (run.status().active()) {
                return run;
            }
            BugFixPolicy policy = policies.load(run.projectId());
            if (run.attempt() >= policy.maxAttempts()) {
                throw new IllegalStateException("已达最大尝试次数（" + policy.maxAttempts()
                        + "），请人工处理后再继续");
            }
            long now = System.currentTimeMillis();
            BugFixRun next = run.withState(run.stage(), BugFixStatus.QUEUED, "等待重试",
                    run.attempt() + 1, now, 0);
            runs.save(next);
            runs.appendEvent(BugFixEvent.info(id, run.stage(), "用户触发重试"));
            cancellations.clear(id);
            submit(id);
            return next;
        }
    }

    /** 取消：协作中断正在执行的 Agent，并立即关闭阶段记录。 */
    public BugFixRun cancel(String userId, String id) {
        cancellations.markCancelled(id);
        synchronized (lockFor(id)) {
            BugFixRun run = require(userId, id);
            if (run.status() == BugFixStatus.CANCELLED || run.status() == BugFixStatus.SUCCEEDED) {
                return run;
            }
            long now = System.currentTimeMillis();
            BugFixRun cancelled = run.withState(run.stage(), BugFixStatus.CANCELLED, "用户取消",
                    run.attempt(), now, now);
            runs.save(cancelled);
            runs.closeOpenStages(id, BugFixStageRun.CANCELLED, "用户取消", now);
            runs.releaseEnvLeases(id);
            runs.appendEvent(BugFixEvent.warn(id, run.stage(), "用户取消自动修复"));
            return cancelled;
        }
    }

    public BugFixRun get(String userId, String id) {
        return require(userId, id);
    }

    /**
     * 合并追踪器确认 PR 已合并：这是运行进入 {@link BugFixStatus#SUCCEEDED} 的唯一入口。
     *
     * <p>「创建了 PR」与「Bug 真正修复」是两件事，只有远端确认 merged 才闭环完成。
     */
    public BugFixRun markMerged(String id) {
        synchronized (lockFor(id)) {
            BugFixRun run = runs.find(id).orElse(null);
            if (run == null || !run.waitingMerge()) {
                return run;
            }
            long now = System.currentTimeMillis();
            BugFixRun done = run.completed(now);
            runs.save(done);
            runs.appendEvent(BugFixEvent.info(id, BugFixStage.PULL_REQUEST, "PR 已合并，自动修复完成"));
            runs.releaseEnvLeases(id);
            enqueue(done, "自动修复 · PR 已合并\n\nBug 修复已合入主干：" + safeUrl(done.prUrl()));
            return done;
        }
    }

    /** PR 被关闭但未合并：回到可重试状态，避免运行永远停在等待合并。 */
    public BugFixRun markPrClosed(String id) {
        synchronized (lockFor(id)) {
            BugFixRun run = runs.find(id).orElse(null);
            if (run == null || !run.waitingMerge()) {
                return run;
            }
            long now = System.currentTimeMillis();
            BugFixRun blocked = run.withState(BugFixStage.PULL_REQUEST, BugFixStatus.BLOCKED,
                    "PR 已被关闭但未合并，请人工确认后重试", run.attempt(), now, now);
            runs.save(blocked);
            runs.appendEvent(BugFixEvent.warn(id, BugFixStage.PULL_REQUEST, blocked.message()));
            enqueue(blocked, "自动修复 · PR 已关闭但未合并\n\n" + safeUrl(blocked.prUrl())
                    + "\n可人工确认后重试。");
            return blocked;
        }
    }

    public List<BugFixRun> list(String userId, String issueId, int limit) {
        return runs.list(userId, issueId, limit);
    }

    public List<BugFixEvent> events(String userId, String id) {
        require(userId, id);
        return runs.events(id);
    }

    public List<BugFixStageRun> stageRuns(String userId, String id) {
        require(userId, id);
        return runs.stageRuns(id);
    }

    private BugFixRun require(String userId, String id) {
        BugFixRun run = runs.find(id)
                .orElseThrow(() -> new IllegalArgumentException("自动修复运行不存在：" + id));
        if (userId != null && !userId.equals(run.userId())) {
            throw new IllegalArgumentException("无权访问该自动修复运行");
        }
        return run;
    }

    private void submit(String id) {
        executor.submit(() -> execute(id));
    }

    private void execute(String id) {
        if (!executing.add(id)) {
            // 上一次执行还没结束（例如取消后立刻重试）：交给它在收尾时重新入队
            rerunRequested.add(id);
            return;
        }
        Semaphore projectLock = null;
        String projectKey = null;
        String owner = instanceId + "-" + UUID.randomUUID().toString().substring(0, 8);
        boolean leased = false;
        try {
            BugFixRun latest = runs.find(id).orElse(null);
            if (latest == null) {
                return;
            }
            // 运行租约：多实例/重复调度时只有一方能推进
            if (!runs.tryAcquireLease(id, owner, LEASE_TTL_MS)) {
                log.info("运行 {} 已有其他执行者持有租约，跳过本次调度", id);
                return;
            }
            leased = true;
            projectKey = latest.projectId() == null || latest.projectId().isBlank()
                    ? "__unknown__" : latest.projectId();
            // 先锁项目，再占全局名额：同一项目永远串行，避免两个部署互相覆盖测试环境
            projectLock = projectLocks.computeIfAbsent(projectKey, key -> new Semaphore(1));
            projectLock.acquire();
            slots.acquire();
            try {
                loop(id, owner);
            } finally {
                slots.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("自动修复执行异常 run={}", id, e);
            fail(id, "编排器异常：" + e.getMessage());
        } finally {
            if (projectLock != null) {
                // 不回收锁对象：删除与 computeIfAbsent 存在竞态，可能让同项目的新旧锁并行；
                // 项目数量有界，保留少量 Semaphore 比偶尔并发部署同一环境安全得多。
                projectLock.release();
            }
            if (leased) {
                try {
                    runs.releaseLease(id, owner);
                } catch (RuntimeException e) {
                    log.warn("释放运行租约失败 run={}：{}", id, e.getMessage());
                }
            }
            try {
                runs.releaseEnvLeases(id);
            } catch (RuntimeException e) {
                log.warn("释放环境租约失败 run={}：{}", id, e.getMessage());
            }
            executing.remove(id);
            boolean rerun = rerunRequested.remove(id);
            if (rerun) {
                submit(id);
            }
        }
    }

    private void loop(String id, String owner) {
        while (true) {
            BugFixRun run = runs.find(id).orElse(null);
            if (run == null) {
                return;
            }
            if (run.status() == BugFixStatus.CANCELLED
                    || run.status() == BugFixStatus.BLOCKED
                    || run.status() == BugFixStatus.FAILED
                    || run.status() == BugFixStatus.WAITING_MERGE
                    || run.status() == BugFixStatus.SUCCEEDED) {
                return;
            }
            if (cancellations.cancelled(id)) {
                return;
            }
            if (!runs.renewLease(id, owner, LEASE_TTL_MS)) {
                log.warn("运行 {} 租约已丢失，停止执行", id);
                return;
            }
            BugFixPolicy policy = policies.load(run.projectId());
            if (run.status() == BugFixStatus.QUEUED) {
                run = runs.save(run.withState(run.stage(), BugFixStatus.RUNNING,
                        "正在执行：" + run.stage().label(), run.attempt(),
                        System.currentTimeMillis(), 0));
                runs.appendEvent(BugFixEvent.info(id, run.stage(),
                        "阶段开始：" + run.stage().label()));
            }
            BugFixStageHandler handler = handlers.get(run.stage());
            if (handler == null) {
                block(run, "阶段实现缺失：" + run.stage().name());
                return;
            }
            BugFixStage stage = run.stage();
            boolean deployStage = stage == BugFixStage.REPRO_DEPLOY
                    || stage == BugFixStage.VERIFY_DEPLOY;
            if (deployStage) {
                if (BugFixPolicy.productionLike(policy.testEnv())) {
                    block(run, "自动修复禁止使用生产环境：" + policy.testEnv());
                    return;
                }
                if (!runs.acquireEnvLease(run.projectId(), policy.testEnv(), id, LEASE_TTL_MS)) {
                    block(run, "测试环境 " + policy.testEnv() + " 正被其他修复运行占用，请稍后重试");
                    return;
                }
            }
            int stageAttempt = runs.stageAttempts(id, stage) + 1;
            long startedAt = System.currentTimeMillis();
            long stageId = runs.startStage(id, stage, stageAttempt, startedAt);
            BugFixStageResult result;
            try {
                result = handler.execute(run, policy);
            } catch (RuntimeException e) {
                log.warn("阶段执行失败 run={} stage={}：{}", id, stage, e.getMessage());
                result = BugFixStageResult.failed(message(e));
            }
            long endedAt = System.currentTimeMillis();
            BugFixRun latest = runs.find(id).orElse(null);
            if (latest == null) {
                return;
            }
            // 取消/重试可能发生在阶段执行期间：只要状态或阶段变了，就丢弃这次结果，
            // 绝不能用旧快照覆盖用户的最新操作。
            if (latest.status() != BugFixStatus.RUNNING || latest.stage() != stage) {
                log.info("运行状态已变化，丢弃阶段结果 run={} stage={} 当前={}/{}", id, stage,
                        latest.status(), latest.stage());
                runs.finishStage(stageId, BugFixStageRun.CANCELLED, "阶段结果已失效（状态已变化）",
                        null, endedAt, endedAt - startedAt);
                return;
            }
            if (deployStage) {
                runs.releaseEnvLease(run.projectId(), policy.testEnv(), id);
            }
            runs.finishStage(stageId,
                    switch (result.outcome()) {
                        case CONTINUE -> BugFixStageRun.CONTINUE;
                        case BLOCKED -> BugFixStageRun.BLOCKED;
                        case FAILED -> BugFixStageRun.FAILED;
                    },
                    AnalyzeClip.clip(result.message(), 4000),
                    result.artifact() == null ? AnalyzeClip.clip(result.evidence(), 20_000)
                            : result.artifact(),
                    endedAt, endedAt - startedAt);
            if (!apply(latest, result, policy, endedAt)) {
                return;
            }
        }
    }

    /** 应用阶段结果；返回 false 表示本次运行已停（阻塞/失败/完成/等待合并/等待退避重试）。 */
    private boolean apply(BugFixRun run, BugFixStageResult result, BugFixPolicy policy, long now) {
        BugFixStage stage = run.stage();
        BugFixStageResult.Outcome outcome = result.outcome();
        BugFixTransitions.Decision decision = BugFixTransitions.decide(
                stage, outcome, run.attempt(), policy.maxAttempts());
        String message = result.message() == null || result.message().isBlank()
                ? stage.label() + "：" + decision.status().name() : result.message();
        if (decision.autoRetry()) {
            message = message + "（" + (decision.retryDelayMs() / 1000) + " 秒后自动重试，第 "
                    + (decision.attempt() + 1) + " 次）";
        }
        BugFixRun saved;
        synchronized (lockFor(run.id())) {
            BugFixRun current = runs.find(run.id()).orElse(null);
            if (current == null) {
                return false;
            }
            if (current.status() != BugFixStatus.RUNNING || current.stage() != stage) {
                return false;
            }
            saved = runs.save(current
                    .withState(decision.nextStage(), decision.status(), message, decision.attempt(),
                            now, decision.finished() ? now : 0)
                    .withResult(result));
            runs.appendEvent(eventFor(saved, stage, result));
            if (decision.finished()) {
                runs.releaseEnvLeases(run.id());
            }
        }
        notifyResult(stage, saved, result);
        if (decision.autoRetry()) {
            String runId = run.id();
            retryScheduler.schedule(() -> submit(runId), decision.retryDelayMs(),
                    TimeUnit.MILLISECONDS);
            return false;
        }
        return decision.status() == BugFixStatus.RUNNING;
    }

    /** 关键节点写通知发件箱；投递由后台重试，失败绝不影响运行。 */
    private void notifyResult(BugFixStage stage, BugFixRun run, BugFixStageResult result) {
        String message = null;
        if (result.outcome() == BugFixStageResult.Outcome.CONTINUE) {
            if (stage == BugFixStage.ANALYZE) {
                message = """
                        自动修复 · 分析完成

                        - 基线提交：`%s`
                        - 根因与版本：
                        %s
                        """.formatted(shortSha(run.baseSha()), clip(run.analysis(), 1200));
            } else if (stage == BugFixStage.PULL_REQUEST) {
                message = "自动修复 · PR 已创建，等待人工 Review 与合并：" + run.prUrl();
            }
        } else if (result.outcome() == BugFixStageResult.Outcome.BLOCKED
                || result.outcome() == BugFixStageResult.Outcome.FAILED) {
            message = "自动修复 · %s\n\n- 阶段：%s\n- 原因：%s\n- 修复分支：`%s`"
                    .formatted(result.outcome() == BugFixStageResult.Outcome.BLOCKED
                                    ? "已暂停，等待人工处理" : "执行失败",
                            stage.label(), clip(run.message(), 800),
                            run.branch() == null ? "" : run.branch());
        }
        if (message == null) {
            return;
        }
        enqueue(run, message);
    }

    /** 通知先脱敏再入发件箱；投递失败由后台重试，绝不影响状态机。 */
    private void enqueue(BugFixRun run, String message) {
        String safe = BugFixRedactor.redact(message);
        try {
            if (notifications != null) {
                notifications.enqueue(run.id(), safe);
            } else if (notifier != null) {
                notifier.comment(run, safe);
            }
        } catch (RuntimeException e) {
            log.warn("自动修复通知入队失败 run={}：{}", run.id(), e.getMessage());
        }
    }

    private static BugFixEvent eventFor(BugFixRun run, BugFixStage stage,
                                        BugFixStageResult result) {
        String message = result.message() == null ? stage.label() : result.message();
        return switch (result.outcome()) {
            case CONTINUE -> BugFixEvent.info(run.id(), stage, message);
            case BLOCKED -> BugFixEvent.warn(run.id(), stage, message);
            case FAILED -> BugFixEvent.error(run.id(), stage, message);
        };
    }

    private void block(BugFixRun run, String message) {
        synchronized (lockFor(run.id())) {
            BugFixRun current = runs.find(run.id()).orElse(null);
            if (current == null || current.status() != BugFixStatus.RUNNING) {
                return;
            }
            long now = System.currentTimeMillis();
            runs.save(current.withState(current.stage(), BugFixStatus.BLOCKED, message,
                    current.attempt(), now, now));
            runs.appendEvent(BugFixEvent.warn(run.id(), run.stage(), message));
            runs.releaseEnvLeases(run.id());
        }
    }

    private void fail(String id, String message) {
        synchronized (lockFor(id)) {
            Optional<BugFixRun> run = runs.find(id);
            if (run.isEmpty() || run.get().status().terminal()) {
                return;
            }
            BugFixRun current = run.get();
            long now = System.currentTimeMillis();
            runs.save(current.withState(current.stage(), BugFixStatus.FAILED, message,
                    current.attempt() + 1, now, now));
            runs.appendEvent(BugFixEvent.error(id, current.stage(), message));
            runs.closeOpenStages(id, BugFixStageRun.FAILED, message, now);
            runs.releaseEnvLeases(id);
        }
    }

    private Object lockFor(String id) {
        return runLocks[Math.floorMod(id == null ? 0 : id.hashCode(), LOCK_STRIPES)];
    }

    private static String shortSha(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(8, sha.length()));
    }

    private static String safeUrl(String url) {
        return url == null ? "" : url;
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String message(RuntimeException e) {
        String text = e.getMessage();
        return text == null || text.isBlank() ? e.getClass().getSimpleName() : text;
    }

    @PreDestroy
    void shutdown() {
        retryScheduler.shutdownNow();
        executor.shutdownNow();
    }

    /** 阶段层共用的裁剪逻辑（避免结果字段无限长落库）。 */
    static final class AnalyzeClip {
        private AnalyzeClip() {
        }

        static String clip(String value, int max) {
            if (value == null) {
                return "";
            }
            return value.length() <= max ? value : value.substring(0, max) + "…";
        }
    }
}
