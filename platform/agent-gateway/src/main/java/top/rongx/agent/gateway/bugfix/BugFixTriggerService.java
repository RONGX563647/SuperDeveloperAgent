package top.rongx.agent.gateway.bugfix;

import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixPolicyStore;
import top.rongx.agent.domain.bugfix.BugFixTrigger;
import top.rongx.agent.domain.gitissue.GitIssue;

/**
 * Bug 任务自动修复触发器：webhook 新建/更新任务与平台新建/编辑任务都经过这里。
 *
 * <p>门禁（全部满足才入队）：
 * <ul>
 *   <li>任务类型为 BUG、状态为 OPEN、已关联项目；</li>
 *   <li>项目级或平台级策略开启；</li>
 *   <li>标签里没有「禁止自动修复」标记（no-auto-fix / manual-only / do-not-fix 等）；</li>
 *   <li>同一任务没有进行中的运行（幂等，webhook 重投不会重复修复）。</li>
 * </ul>
 */
@Component
public class BugFixTriggerService implements BugFixTrigger {

    private static final Logger log = LoggerFactory.getLogger(BugFixTriggerService.class);
    /** 人工显式豁免自动修复的标签（包含匹配，大小写不敏感）。 */
    private static final Set<String> SKIP_LABELS = Set.of(
            "no-auto-fix", "skip-auto-fix", "do-not-fix", "manual-only", "manual-fix", "人工修复");

    private final BugFixPolicyStore policies;
    /**
     * 延迟取编排器：编排器依赖 Agent → 工具 → GitIssueService → 触发器，
     * 构造期直接注入会形成环；ObjectProvider 只在真正入队时才解析。
     */
    private final ObjectProvider<BugFixOrchestrator> orchestrator;

    public BugFixTriggerService(BugFixPolicyStore policies,
                                ObjectProvider<BugFixOrchestrator> orchestrator) {
        this.policies = policies;
        this.orchestrator = orchestrator;
    }

    @Override
    public void onIssueCreated(GitIssue issue) {
        if (!eligible(issue)) {
            return;
        }
        try {
            BugFixPolicy policy = policies.load(issue.projectId());
            if (!policy.enabled()) {
                return;
            }
            orchestrator.getObject().start(issue);
            log.info("Bug 任务 {} 已触发自动修复", issue.id());
        } catch (IllegalStateException e) {
            // 已有活动运行 / 已提过 PR：webhook 重投、编辑重开时属于正常情况
            log.debug("Bug 任务 {} 跳过自动修复：{}", issue.id(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Bug 任务 {} 自动修复触发失败：{}", issue.id(), e.getMessage());
        }
    }

    @Override
    public void onIssueUpdated(GitIssue issue) {
        // 编辑/重开/改标签后重新评估：关闭任务由 eligible 拦截，进行中的任务由 start 幂等拦截
        onIssueCreated(issue);
    }

    private static boolean eligible(GitIssue issue) {
        if (issue == null || !"BUG".equals(issue.kind()) || !issue.open()) {
            return false;
        }
        if (issue.projectId() == null || issue.projectId().isBlank()) {
            return false;
        }
        String labels = issue.labels() == null ? "" : issue.labels().toLowerCase(Locale.ROOT);
        if (SKIP_LABELS.stream().anyMatch(labels::contains)) {
            log.debug("Bug 任务 {} 带有禁止自动修复标签，跳过", issue.id());
            return false;
        }
        return true;
    }
}
