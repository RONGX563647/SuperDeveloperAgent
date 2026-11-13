package top.rongx.agent.gateway.bugfix;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixGitPort;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.bugfix.BugFixRunStore;

/**
 * PR 合并状态追踪：每 60 秒检查「等待合并」的运行，确认远端是否已合并。
 *
 * <p>「提了 PR」不等于「Bug 已修复」：只有远端确认 merged 才把运行推进到 DONE/SUCCEEDED。
 * PR 被关闭但未合并时回到可重试状态，避免运行永远挂在等待合并。
 *
 * <p>与通知发件箱一致，用单线程虚拟线程轮询而不是 {@code @Scheduled}；{@link #pollOnce()}
 * 可在测试中直接调用。
 */
@Component
public class BugFixMergeTracker {

    private static final Logger log = LoggerFactory.getLogger(BugFixMergeTracker.class);
    private static final int BATCH = 20;
    private static final long INTERVAL_MS = 60_000L;

    private final BugFixRunStore runs;
    private final BugFixGitPort git;
    private final BugFixOrchestrator orchestrator;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;

    public BugFixMergeTracker(BugFixRunStore runs, BugFixGitPort git,
                              BugFixOrchestrator orchestrator) {
        this.runs = runs;
        this.git = git;
        this.orchestrator = orchestrator;
    }

    @PostConstruct
    void start() {
        running.set(true);
        worker = Thread.ofVirtual().name("bugfix-merge-tracker").start(() -> {
            while (running.get()) {
                try {
                    pollOnce();
                } catch (RuntimeException e) {
                    log.warn("PR 合并状态轮询异常：{}", e.getMessage());
                }
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    /** 检查一批等待合并的运行；返回状态发生变化的数量（供测试断言）。 */
    public int pollOnce() {
        List<BugFixRun> waiting = runs.listWaitingMerge(BATCH);
        int changed = 0;
        for (BugFixRun run : waiting) {
            try {
                switch (git.pullRequestState(run)) {
                    case MERGED -> {
                        orchestrator.markMerged(run.id());
                        changed++;
                    }
                    case CLOSED -> {
                        orchestrator.markPrClosed(run.id());
                        changed++;
                    }
                    default -> {
                        // OPEN / UNKNOWN：保持等待，下一轮再查
                    }
                }
            } catch (RuntimeException e) {
                log.warn("检查 PR 状态失败 run={}：{}", run.id(), e.getMessage());
            }
        }
        return changed;
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
    }
}
