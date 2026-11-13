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
 *   <li><b>并发受控</b>：虚拟线程执行，信号量限制同时跑 2 个任务，避免同时部署多个环境。</li>
 * </ul>
 */
@Service
public class BugFixOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BugFixOrchestrator.class);
    private static final int MAX_CONCURRENT_RUNS = 2;

    private final BugFixRunStore runs;
    private final BugFixPolicyStore policies;
    private final Map<BugFixStage, BugFixStageHandler> handlers;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_RUNS);
    private final Set<String> executing = ConcurrentHashMap.newKeySet();

    public BugFixOrchestrator(BugFixRunStore runs, BugFixPolicyStore policies,
                              List<BugFixStageHandler> handlers) {
        this.runs = runs;
        this.policies = policies;
        this.handlers = handlers.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        BugFixStageHandler::stage, handler -> handler, (a, b) -> a));
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

    /** 取消：阶段边界生效（正在跑的阶段会跑完当前步骤）。 */
    public BugFixRun cancel(String userId, String id) {
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
            return;
        }
        try {
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
            executing.remove(id);
        }
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
            BugFixStageResult result;
            try {
                result = handler.execute(run, policy);
            } catch (RuntimeException e) {
                log.warn("阶段执行失败 run={} stage={}：{}", id, run.stage(), e.getMessage());
                result = BugFixStageResult.failed(message(e));
            }
            if (!apply(run, result)) {
                return;
            }
        }
    }

    /** 应用阶段结果；返回 false 表示本次运行已停（阻塞/失败/完成/取消）。 */
    private boolean apply(BugFixRun run, BugFixStageResult result) {
        long now = System.currentTimeMillis();
        boolean success = result.outcome() == BugFixStageResult.Outcome.CONTINUE;
        BugFixStage nextStage = success ? run.stage().next() : run.stage();
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
        BugFixRun saved = runs.save(copy(run, nextStage, status,
                result.message() == null || result.message().isBlank()
                        ? run.stage().label() + "：" + status.name() : result.message(),
                result.analysis(), result.baseSha(), result.branch(), result.fixSha(),
                result.env(), attempt, now, finished ? now : 0)
                .withEvidence(result.evidence())
                .withPrUrl(result.prUrl()));
        runs.appendEvent(eventFor(saved, result));
        return status == BugFixStatus.RUNNING;
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
        long now = System.currentTimeMillis();
        runs.save(copy(run, run.stage(), BugFixStatus.BLOCKED, message,
                null, null, null, null, null, run.attempt(), now, now));
        runs.appendEvent(BugFixEvent.warn(run.id(), run.stage(), message));
    }

    private void fail(String id, String message) {
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
