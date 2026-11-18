package top.rongx.agent.runtime.workspace;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.conversation.ConversationPatch;
import top.rongx.agent.domain.conversation.ConversationRepository;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.ConversationRunRepository;
import top.rongx.agent.domain.conversation.ConversationStatus;
import top.rongx.agent.domain.conversation.RunStatus;
import top.rongx.agent.domain.run.RunKind;
import top.rongx.agent.domain.run.RunLedgerRepository;
import top.rongx.agent.domain.run.RunRecord;
import top.rongx.agent.domain.run.RunState;

/**
 * 运行看门狗：把卡死的运行收敛到终态。
 *
 * <p>模型/工具调用可能永远不返回，进程重启后内存句柄也会丢失；没有看门狗时，
 * 这些运行会一直显示「运行中」，且占着同一会话的并发闸门（同一会话不能再次派发）。
 * 看门狗只做兜底：正常结束的运行不会被它碰到。
 */
@Component
public class WorkspaceRunWatchdog {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRunWatchdog.class);
    /** 单次扫描上限：积压再多也不会一次把线程池/数据库压垮。 */
    private static final int SCAN_LIMIT = 200;

    private final ConversationRunRepository runs;
    private final ConversationRepository conversations;
    private final RunAborter aborter;
    private final RunLedgerRepository ledger;
    private final long runTimeoutMillis;
    private final long queueTimeoutMillis;

    public WorkspaceRunWatchdog(ConversationRunRepository runs,
                                ConversationRepository conversations,
                                RunAborter aborter,
                                RunLedgerRepository ledger,
                                @Value("${agent.workspace.watchdog.run-timeout-ms:600000}") long runTimeoutMillis,
                                @Value("${agent.workspace.watchdog.queue-timeout-ms:600000}") long queueTimeoutMillis) {
        this.runs = runs;
        this.conversations = conversations;
        this.aborter = aborter;
        this.ledger = ledger;
        this.runTimeoutMillis = Math.max(1_000, runTimeoutMillis);
        this.queueTimeoutMillis = Math.max(1_000, queueTimeoutMillis);
    }

    @Scheduled(initialDelayString = "${agent.workspace.watchdog.initial-delay-ms:30000}",
            fixedDelayString = "${agent.workspace.watchdog.interval-ms:30000}")
    public void scan() {
        try {
            int expired = scanOnce(System.currentTimeMillis());
            if (expired > 0) {
                log.warn("运行看门狗中止了 {} 个超时运行", expired);
            }
        } catch (RuntimeException e) {
            // 看门狗自身不能因为一次扫描失败而停止后续调度
            log.warn("运行看门狗扫描失败：{}", e.getMessage());
        }
    }

    /** 执行一次扫描，返回被中止的运行数（便于测试直接调用）。 */
    public int scanOnce(long now) {
        List<ConversationRun> active = runs.findActiveRuns(SCAN_LIMIT);
        int expired = 0;
        for (ConversationRun run : active) {
            String reason = timeoutReason(run, now);
            if (reason == null) {
                continue;
            }
            if (abort(run, reason)) {
                expired++;
            }
        }
        return expired;
    }

    private String timeoutReason(ConversationRun run, long now) {
        if (run.status() == RunStatus.RUNNING) {
            long started = run.startedAt() == 0 ? run.createdAt() : run.startedAt();
            long elapsed = now - started;
            if (elapsed > runTimeoutMillis) {
                return "运行超时：已运行 " + (elapsed / 1000) + "s，超过上限 "
                        + (runTimeoutMillis / 1000) + "s，已由看门狗中止";
            }
            return null;
        }
        if (run.status() == RunStatus.QUEUED) {
            long elapsed = now - run.createdAt();
            if (elapsed > queueTimeoutMillis) {
                return "排队超时：已等待 " + (elapsed / 1000) + "s，超过上限 "
                        + (queueTimeoutMillis / 1000) + "s，已由看门狗中止";
            }
        }
        return null;
    }

    private boolean abort(ConversationRun run, String reason) {
        // 进程内句柄还在：交给执行器统一收敛（释放事件流/队列/并发位）
        if (aborter.fail(run.id(), reason)) {
            track(run, reason);
            return true;
        }
        // 句柄丢失（进程重启 / 运行已被其它路径结束）：直接收敛数据库状态，避免永远「运行中」
        long now = System.currentTimeMillis();
        try {
            runs.updateRun(run.withStatus(RunStatus.FAILED, run.result(), reason,
                    run.startedAt() == 0 ? now : run.startedAt(), now));
            conversations.update(run.conversationId(), run.ownerUserId(),
                    ConversationPatch.status(ConversationStatus.FAILED));
            track(run, reason);
            return true;
        } catch (RuntimeException e) {
            log.warn("看门狗收敛运行失败 run={}：{}", run.id(), e.getMessage());
            return false;
        }
    }

    private void track(ConversationRun run, String reason) {
        try {
            long now = System.currentTimeMillis();
            ledger.save(new RunRecord(run.id(), RunKind.WORKSPACE, run.projectId(),
                    run.conversationId(), run.ownerUserId(), RunState.FAILED,
                    "看门狗中止", reason, run.createdAt(), now));
        } catch (RuntimeException e) {
            log.debug("看门狗写运行账本失败 run={}：{}", run.id(), e.getMessage());
        }
    }
}
