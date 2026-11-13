package top.rongx.agent.gateway.config;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.rongx.agent.governance.ChangeRegistry;
import top.rongx.agent.governance.ConfirmTokenService;
import top.rongx.agent.governance.PatchRegistry;

/**
 * 治理数据保留策略：确认令牌过期即删，变更单只保留最近 24 小时。
 *
 * <p>令牌是一次性凭据，留着只会扩大被重放的时间窗口；变更单只服务于确认卡片，
 * 历史运行的证据在运行记录里，不需要长期占用治理表。
 */
@Component
public class GovernanceCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(GovernanceCleanupJob.class);

    /** 变更单保留时长：覆盖一次人工确认的正常操作时间即可。 */
    private static final Duration CHANGE_RETENTION = Duration.ofHours(24);

    private final ChangeRegistry changes;
    private final PatchRegistry patches;
    private final ConfirmTokenService tokens;

    public GovernanceCleanupJob(ChangeRegistry changes, PatchRegistry patches,
                                ConfirmTokenService tokens) {
        this.changes = changes;
        this.patches = patches;
        this.tokens = tokens;
    }

    /** 启动 1 分钟后先清一次，之后每 10 分钟一次。 */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 600_000L)
    public void purge() {
        int expiredCount = tokens.purgeExpired();
        int staleChanges = changes.purgeBefore(Instant.now().minus(CHANGE_RETENTION));
        int stalePatches = patches.purgeBefore(Instant.now().minus(CHANGE_RETENTION));
        if (expiredCount + staleChanges + stalePatches > 0) {
            log.info("治理数据清理：过期令牌 {} 条，超期变更单 {} 条，超期补丁 {} 条",
                    expiredCount, staleChanges, stalePatches);
        }
    }
}
