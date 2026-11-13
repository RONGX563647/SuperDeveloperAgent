package top.rongx.agent.gateway.bugfix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixNotifier;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.gateway.gitissue.GitIssueRemoteClient;

/**
 * 把自动修复的关键节点回写到 Git Issue 评论。
 *
 * <p>只对已绑定平台/仓库且有远端编号的任务生效；未绑定账号、网络失败等一律只打日志，
 * 绝不能因为「评论发不出去」让修复运行失败。
 */
@Component
public class GitIssueNotifier implements BugFixNotifier {

    private static final Logger log = LoggerFactory.getLogger(GitIssueNotifier.class);

    private final GitIssueRemoteClient remote;

    public GitIssueNotifier(GitIssueRemoteClient remote) {
        this.remote = remote;
    }

    @Override
    public void comment(BugFixRun run, String message) {
        if (run == null || message == null || message.isBlank()) {
            return;
        }
        if (run.provider() == null || run.repo() == null || run.issueNumber() == null
                || run.issueNumber().isBlank()) {
            return;
        }
        // 兜底脱敏：即使上游漏脱敏，也不会把 token/密码写进公开 Issue
        String safe = BugFixRedactor.redact(message);
        try {
            remote.credentials(run.userId(), run.provider(), run.repo())
                    .ifPresent(credentials -> remote.comment(credentials, run.issueNumber(), safe));
        } catch (RuntimeException e) {
            log.warn("自动修复评论回写失败 run={} issue={}：{}", run.id(), run.issueNumber(),
                    e.getMessage());
            // 交给发件箱调度器重试；通知失败不能影响修复运行，但也不能悄悄丢弃
            throw e;
        }
    }
}
