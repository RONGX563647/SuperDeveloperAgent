package top.rongx.agent.gateway.config;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.run.RunLedgerRepository;
import top.rongx.agent.governance.ChangeRegistry;
import top.rongx.agent.governance.ConfirmTokenService;
import top.rongx.agent.governance.PatchRegistry;

/**
 * 治理数据保留策略：确认令牌过期即删；变更单/运行账本按配置保留（BUG-37）。
 *
 * <p>令牌是一次性凭据，留着只会扩大被重放的时间窗口。变更单与运行账本的保留期
 * 从硬编码 24h/30d 改为可配置（{@code agent.governance.retention.*}），默认 90 天，
 * 与审计表的追溯窗口对齐——"谁确认了什么"在 {@code audit_event} 里长期留痕，
 * 确认卡片原文则依赖这里的保留期。
 */
@Component
public class GovernanceCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(GovernanceCleanupJob.class);

    /** 变更单保留时长（小时）：默认 2160h = 90 天，审计友好。 */
    private final Duration changeRetention;

    /** 运行账本保留时长（天）：默认 90 天。 */
    private final Duration runRetention;

    private final ChangeRegistry changes;
    private final PatchRegistry patches;
    private final ConfirmTokenService tokens;
    private final RunLedgerRepository runs;

    public GovernanceCleanupJob(ChangeRegistry changes, PatchRegistry patches,
                                ConfirmTokenService tokens, RunLedgerRepository runs,
                                @Value("${agent.governance.retention.change-hours:2160}") long changeHours,
                                @Value("${agent.governance.retention.run-days:90}") long runDays) {
        this.changes = changes;
        this.patches = patches;
        this.tokens = tokens;
        this.runs = runs;
        this.changeRetention = Duration.ofHours(changeHours);
        this.runRetention = Duration.ofDays(runDays);
        log.info("治理数据保留策略生效：变更单 {} 小时（{} 天），运行账本 {} 天",
                changeHours, changeRetention.toDays(), runDays);
    }

    /** 启动 1 分钟后先清一次，之后每 10 分钟一次。 */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 600_000L)
    public void purge() {
        int expiredCount = tokens.purgeExpired();
        int staleChanges = changes.purgeBefore(Instant.now().minus(changeRetention));
        int stalePatches = patches.purgeBefore(Instant.now().minus(changeRetention));
        int staleRuns = runs.purgeTerminalBefore(
                System.currentTimeMillis() - runRetention.toMillis());
        if (expiredCount + staleChanges + stalePatches + staleRuns > 0) {
            log.info("治理数据清理：过期令牌 {} 条，超期变更单 {} 条，超期补丁 {} 条，超期运行 {} 条",
                    expiredCount, staleChanges, stalePatches, staleRuns);
        }
    }
}
