package top.rongx.agent.gateway.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.run.RunLedgerRepository;

/**
 * 统一运行账本的启动恢复。
 *
 * <p>只处理"超过阈值仍未更新"的 RUNNING：这类记录基本是上一个进程崩溃/被 kill 留下的孤儿。
 * 用时间阈值而不是"启动即清空"，是因为多实例部署时不能误杀其他实例正在跑的运行。
 *
 * <p>BugFix 有自己的租约与重新入队逻辑，这里只把账本状态纠正为 FAILED，
 * 不会改动 BugFix 自身的运行记录与阶段记录。
 */
@Service
public class RunRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RunRecoveryService.class);

    private final RunLedgerRepository ledger;
    private final long staleSeconds;

    public RunRecoveryService(RunLedgerRepository ledger,
                              @Value("${agent.run.stale-seconds:300}") long staleSeconds) {
        this.ledger = ledger;
        this.staleSeconds = Math.max(60, staleSeconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        try {
            long threshold = System.currentTimeMillis() - staleSeconds * 1000L;
            int recovered = ledger.markStaleRunning(threshold,
                    "服务重启或进程中断，运行状态未知");
            if (recovered > 0) {
                log.warn("运行账本恢复：{} 个超过 {} 秒未更新的运行已标记为失败", recovered, staleSeconds);
            }
        } catch (RuntimeException e) {
            // 恢复失败不影响启动，下一次启动会再次尝试
            log.warn("运行账本启动恢复失败：{}", e.getMessage());
        }
    }
}
