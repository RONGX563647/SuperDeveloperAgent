package top.rongx.agent.gateway.bugfix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixPolicyStore;
import top.rongx.agent.domain.bugfix.BugFixTrigger;
import top.rongx.agent.domain.gitissue.GitIssue;

/**
 * Bug 任务自动修复触发器：webhook 新建任务与平台新建任务都经过这里。
 *
 * <p>只有「Bug + 开启策略 + 已关联项目 + 未关闭 + 无活动运行」才入队；
 * 入队本身很快（落库 + 异步执行），不会阻塞 webhook 回调。
 */
@Component
public class BugFixTriggerService implements BugFixTrigger {

    private static final Logger log = LoggerFactory.getLogger(BugFixTriggerService.class);

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
        if (issue == null || !"BUG".equals(issue.kind()) || !issue.open()) {
            return;
        }
        if (issue.projectId() == null || issue.projectId().isBlank()) {
            return;
        }
        try {
            BugFixPolicy policy = policies.load();
            if (!policy.enabled()) {
                return;
            }
            orchestrator.getObject().start(issue);
            log.info("Bug 任务 {} 已触发自动修复", issue.id());
        } catch (IllegalStateException e) {
            // 已有活动运行：webhook 重投/重复创建时属于正常情况
            log.debug("Bug 任务 {} 跳过自动修复：{}", issue.id(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Bug 任务 {} 自动修复触发失败：{}", issue.id(), e.getMessage());
        }
    }
}
