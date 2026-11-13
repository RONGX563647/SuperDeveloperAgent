package top.rongx.agent.gateway.bugfix;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.bugfix.BugFixEvent;
import top.rongx.agent.domain.bugfix.BugFixNotifier;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixPolicyStore;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixRunStore;
import top.rongx.agent.domain.bugfix.BugFixStage;
import top.rongx.agent.domain.bugfix.BugFixStageResult;
import top.rongx.agent.domain.bugfix.BugFixStatus;
import top.rongx.agent.domain.gitissue.GitIssue;

/**
 * Bug 自动修复编排器：把「分析 → 复现 → 修复 → 验证 → PR」串成可持久化的状态机。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>阶段边界落库</b>：每完成一个阶段就整体保存快照，进程重启后从断点继续/提示重试；</li>
 *   <li><b>证据驱动</b>：阶段只能返回 CONTINUE / BLOCKED / FAILED，不能跳阶段；</li>
 *   <li><b>幂等</b>：同一 Issue 同时只允许一个活动运行，重复 webhook 不会重复修复；</li>
 *   <li><b>并发受控</b>：全局最多 2 个运行，且<b>同一项目串行</b>，避免两个运行同时部署
 *       同一个测试环境互相覆盖；</li>
 *   <li><b>取消安全</b>：阶段执行中被取消时，结果会按最新快照重新校验并丢弃，
 *       绝不会用旧快照把 CANCELLED 覆盖回 RUNNING。</li>
 * </ul>
 */
@Service
public class BugFixOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BugFixOrchestrator.class);
    private static final int MAX_CONCURRENT_RUNS = 2;
    private static final int LOCK_STRIPES = 64;

    private final BugFixRunStore runs;
    private final BugFixPolicyStore policies;
    private final BugFixNotifier notifier;
    private final Map<BugFixStage, BugFixStageHandler> handlers;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_RUNS);
    private final Map<String, Semaphore> projectLocks = new ConcurrentHashMap<>();
    private final Set<String> executing = ConcurrentHashMap.newKeySet();
    private final Set<String> rerunRequested = ConcurrentHashMap.newKeySet();
    private final Object[] runLocks = new Object[LOCK_STRIPES];

    public BugFixOrchestrator(BugFixRunStore runs, BugFixPolicyStore policies,
                              List<BugFixStageHandler> handlers, BugFixNotifier notifier) {
        this.runs = runs;
        this.policies = policies;
        this.notifier = notifier;
        this.handlers = handlers.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        BugFixStageHandler::stage, handler -> handler, (a, b) -> a));
        for (int i = 0; i < runLocks.length; i++) {
            runLocks[i] = new Object();
        }
    }

    /** 重启时把残留的 RUNNING 标记为可重试，避免状态永远卡住。 */
    @PostConstruct
    void recoverInterrupted() {
        int count = runs.markInterrupted();
        if (count > 0) {
            log.warn("检测到 {} 个中断的自动修复运行，已标记为可重试", count);
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
                .filter(run -> run.status() == BugFixStatus.SUCCEEDED
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
                BugFixStage.ANALYZE, BugFixStatus.QUEUED, "已入队，等待执行", "", "", "", "", "", "",
                0, now, now, 0);
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
            if (run.status().active()) {
                return run;
            }
            BugFixPolicy policy = policies.load();
            if (run.attempt() >= policy.maxAttempts()) {
                throw new IllegalStateException("已达最大尝试次数（" + policy.maxAttempts()
                        + "），请人工处理后再继续");
            }
            long now = System.currentTimeMillis();
            BugFixRun next = copy(run, run.stage(), BugFixStatus.QUEUED, "等待重试",
                    null, null, null, null, null, run.attempt() + 1, now, 0);
            runs.save(next);
            runs.appendEvent(BugFixEvent.info(id, run.stage(), "用户触发重试"));
            submit(id);
            return next;
        }
    }

    /** 取消：阶段边界生效（正在跑的阶段会跑完当前步骤，结果会被丢弃）。 */
    public BugFixRun cancel(String userId, String id) {
        synchronized (lockFor(id)) {
            BugFixRun run = require(userId, id);
            if (run.status() == BugFixStatus.CANCELLED || run.status() == BugFixStatus.SUCCEEDED) {
                return run;
            }
            long now = System.currentTimeMillis();
            BugFixRun cancelled = copy(run, run.stage(), BugFixStatus.CANCELLED, "用户取消",
                    null, null, null, null, null, run.attempt(), now, now);
            runs.save(cancelled);
            runs.appendEvent(BugFixEvent.warn(id, run.stage(), "用户取消自动修复"));
            return cancelled;
        }
    }

    public BugFixRun get(String userId, String id) {
        return require(userId, id);
    }

    public List<BugFixRun> list(String userId, String issueId, int limit) {
        return runs.list(userId, issueId, limit);
    }

    public List<BugFixEvent> events(String userId, String id) {
        require(userId, id);
        return runs.events(id);
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
        try {
            BugFixRun latest = runs.find(id).orElse(null);
            if (latest == null) {
                return;
            }
            projectKey = latest.projectId() == null || latest.projectId().isBlank()
                    ? "__unknown__" : latest.projectId();
            // 先锁项目，再占全局名额：同一项目永远串行，避免两个部署互相覆盖测试环境
            projectLock = projectLocks.computeIfAbsent(projectKey, key -> new Semaphore(1));
            projectLock.acquire();
            slots.acquire();
            try {
                loop(id);
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
            executing.remove(id);
            boolean rerun = rerunRequested.remove(id);
            if (rerun || isQueued(id)) {
                submit(id);
            }
        }
    }

    private boolean isQueued(String id) {
        return runs.find(id).map(run -> run.status() == BugFixStatus.QUEUED).orElse(false);
    }

    private void loop(String id) {
        while (true) {
            BugFixRun run = runs.find(id).orElse(null);
            if (run == null) {
                return;
            }
            if (run.status() == BugFixStatus.CANCELLED
                    || run.status() == BugFixStatus.BLOCKED
                    || run.status() == BugFixStatus.FAILED
                    || run.status() == BugFixStatus.SUCCEEDED) {
                return;
            }
            BugFixPolicy policy = policies.load();
            if (run.status() == BugFixStatus.QUEUED) {
                run = runs.save(copy(run, run.stage(), BugFixStatus.RUNNING,
                        "正在执行：" + run.stage().label(), null, null, null, null, null,
                        run.attempt(), System.currentTimeMillis(), 0));
                runs.appendEvent(BugFixEvent.info(id, run.stage(),
                        "阶段开始：" + run.stage().label()));
            }
            BugFixStageHandler handler = handlers.get(run.stage());
            if (handler == null) {
                block(run, "阶段实现缺失：" + run.stage().name());
                return;
            }
            BugFixStage stage = run.stage();
            BugFixStageResult result;
            try {
                result = handler.execute(run, policy);
            } catch (RuntimeException e) {
                log.warn("阶段执行失败 run={} stage={}：{}", id, stage, e.getMessage());
                result = BugFixStageResult.failed(message(e));
            }
            BugFixRun latest = runs.find(id).orElse(null);
            if (latest == null) {
                return;
            }
            // 取消/重试可能发生在阶段执行期间：只要状态或阶段变了，就丢弃这次结果，
            // 绝不能用旧快照覆盖用户的最新操作。
            if (latest.status() != BugFixStatus.RUNNING || latest.stage() != stage) {
                log.info("运行状态已变化，丢弃阶段结果 run={} stage={} 当前={}/{}", id, stage,
                        latest.status(), latest.stage());
                return;
            }
            if (!apply(latest, result)) {
                return;
            }
        }
    }

    /** 应用阶段结果；返回 false 表示本次运行已停（阻塞/失败/完成/取消）。 */
    private boolean apply(BugFixRun run, BugFixStageResult result) {
        BugFixStage stage = run.stage();
        boolean success = result.outcome() == BugFixStageResult.Outcome.CONTINUE;
        BugFixStage nextStage = success ? stage.next() : stage;
        BugFixStatus status;
        if (success) {
            status = nextStage == BugFixStage.DONE ? BugFixStatus.SUCCEEDED : BugFixStatus.RUNNING;
        } else if (result.outcome() == BugFixStageResult.Outcome.BLOCKED) {
            status = BugFixStatus.BLOCKED;
        } else {
            status = BugFixStatus.FAILED;
        }
        int attempt = result.outcome() == BugFixStageResult.Outcome.FAILED
                ? run.attempt() + 1 : run.attempt();
        boolean finished = status == BugFixStatus.SUCCEEDED
                || status == BugFixStatus.FAILED || status == BugFixStatus.BLOCKED;
        BugFixRun saved;
        synchronized (lockFor(run.id())) {
            BugFixRun current = runs.find(run.id()).orElse(null);
            if (current == null) {
                return false;
            }
            if (current.status() != BugFixStatus.RUNNING || current.stage() != stage) {
                return false;
            }
            long now = System.currentTimeMillis();
            saved = runs.save(copy(current, nextStage, status,
                    result.message() == null || result.message().isBlank()
                            ? stage.label() + "：" + status.name() : result.message(),
                    result.analysis(), result.baseSha(), result.branch(), result.fixSha(),
                    result.env(), attempt, now, finished ? now : 0)
                    .withEvidence(result.evidence())
                    .withPrUrl(result.prUrl()));
            runs.appendEvent(eventFor(saved, result));
        }
        notifyResult(stage, saved, result);
        return status == BugFixStatus.RUNNING;
    }

    /** 关键节点回写 Issue 评论；通知失败绝不影响运行。 */
    private void notifyResult(BugFixStage stage, BugFixRun run, BugFixStageResult result) {
        if (notifier == null) {
            return;
        }
        try {
            if (result.outcome() == BugFixStageResult.Outcome.CONTINUE) {
                if (stage == BugFixStage.ANALYZE) {
                    notifier.comment(run, """
                            自动修复 · 分析完成

                            - 基线提交：`%s`
                            - 根因与版本：
                            %s
                            """.formatted(shortSha(run.baseSha()), clip(run.analysis(), 1200)));
                } else if (stage == BugFixStage.PULL_REQUEST) {
                    notifier.comment(run, "自动修复 · PR 已创建，等待人工 Review 与合并："
                            + run.prUrl());
                }
            } else if (result.outcome() == BugFixStageResult.Outcome.BLOCKED
                    || result.outcome() == BugFixStageResult.Outcome.FAILED) {
                notifier.comment(run, "自动修复 · %s\n\n- 阶段：%s\n- 原因：%s\n- 修复分支：`%s`"
                        .formatted(result.outcome() == BugFixStageResult.Outcome.BLOCKED
                                        ? "已暂停，等待人工处理" : "执行失败",
                                stage.label(), clip(run.message(), 800),
                                run.branch() == null ? "" : run.branch()));
            }
        } catch (RuntimeException e) {
            log.warn("回写 Issue 评论失败 run={}：{}", run.id(), e.getMessage());
        }
    }

    private static BugFixEvent eventFor(BugFixRun run, BugFixStageResult result) {
        String message = result.message() == null ? run.stage().label() : result.message();
        return switch (result.outcome()) {
            case CONTINUE -> BugFixEvent.info(run.id(), run.stage(), message);
            case BLOCKED -> BugFixEvent.warn(run.id(), run.stage(), message);
            case FAILED -> BugFixEvent.error(run.id(), run.stage(), message);
        };
    }

    private void block(BugFixRun run, String message) {
        synchronized (lockFor(run.id())) {
            BugFixRun current = runs.find(run.id()).orElse(null);
            if (current == null || current.status() != BugFixStatus.RUNNING) {
                return;
            }
            long now = System.currentTimeMillis();
            runs.save(copy(current, current.stage(), BugFixStatus.BLOCKED, message,
                    null, null, null, null, null, current.attempt(), now, now));
            runs.appendEvent(BugFixEvent.warn(run.id(), run.stage(), message));
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
            runs.save(copy(current, current.stage(), BugFixStatus.FAILED, message,
                    null, null, null, null, null, current.attempt() + 1, now, now));
            runs.appendEvent(BugFixEvent.error(id, current.stage(), message));
        }
    }

    private Object lockFor(String id) {
        return runLocks[Math.floorMod(id == null ? 0 : id.hashCode(), LOCK_STRIPES)];
    }

    private static String shortSha(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(8, sha.length()));
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static BugFixRun copy(BugFixRun run, BugFixStage stage, BugFixStatus status,
                                  String message, String analysis, String baseSha, String branch,
                                  String fixSha, String env, int attempt, long updatedAt,
                                  long finishedAt) {
        return new BugFixRun(run.id(), run.issueId(), run.userId(), run.projectId(),
                run.provider(), run.repo(), run.issueNumber(), run.issueTitle(), run.issueBody(),
                branch == null ? run.branch() : branch, stage, status, message,
                analysis == null ? run.analysis() : analysis,
                baseSha == null ? run.baseSha() : baseSha,
                fixSha == null ? run.fixSha() : fixSha,
                env == null ? run.env() : env,
                run.prUrl(), run.evidence(), attempt, run.createdAt(), updatedAt, finishedAt);
    }

    private static String message(RuntimeException e) {
        String text = e.getMessage();
        return text == null || text.isBlank() ? e.getClass().getSimpleName() : text;
    }
}
