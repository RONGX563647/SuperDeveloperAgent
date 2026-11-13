package top.rongx.agent.gateway.gitissue;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.project.ProjectEnv;
import top.rongx.agent.runtime.ChatService;

/**
 * Git 评论触发 Agent：只有「指令型」评论才唤醒，避免普通讨论也消耗模型。
 *
 * <p>在独立虚拟线程里跑，webhook 回调线程已提前返回；失败只记日志，不影响任务同步。
 * 任务必须已关联项目（projectId 非空）才会唤醒——没有项目上下文时 Agent 无法安全执行。
 */
@Component
public class GitIssueAgentWaker {

    private static final Logger log = LoggerFactory.getLogger(GitIssueAgentWaker.class);

    /** 指令型评论关键词（中英文）；命中任一即视为要求 Agent 处理。 */
    private static final List<String> COMMANDS = List.of(
            "排查", "修复", "处理", "定位", "分析", "看看", "看下", "帮我",
            "fix", "debug", "investigate", "please", "handle");

    private final ChatService chatService;
    private final ProjectRepository projects;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public GitIssueAgentWaker(ChatService chatService, ProjectRepository projects) {
        this.chatService = chatService;
        this.projects = projects;
    }

    /** 评论命中指令关键词且任务已关联项目时，异步唤醒 Agent。 */
    public void wakeIfCommand(GitIssue issue, String comment) {
        if (issue == null || comment == null || comment.isBlank()) {
            return;
        }
        if (issue.projectId().isBlank()) {
            log.debug("任务 {} 未关联项目，跳过 Agent 唤醒", issue.id());
            return;
        }
        String text = comment.toLowerCase(Locale.ROOT);
        boolean command = COMMANDS.stream().anyMatch(text::contains);
        if (!command) {
            return;
        }
        executor.submit(() -> wake(issue, comment));
    }

    private void wake(GitIssue issue, String comment) {
        try {
            Project project = projects.findById(issue.projectId()).orElse(null);
            if (project == null) {
                return;
            }
            String env = projects.findEnvs(project.id()).stream()
                    .map(ProjectEnv::env)
                    .findFirst()
                    .orElse("test");
            ProjectContext ctx = new ProjectContext(project.id(), env, issue.userId(),
                    project.repoUrl(), null, false);
            String prompt = """
                    Git Issue #%s 收到新评论，请处理：
                    %s

                    先调用 list_git_issues 读取任务详情；需要时用 comment_git_issue 回写结论，
                    完成后可用 close_git_issue 关闭任务。不要重复回复相同内容。
                    """.formatted(issue.number(), comment.trim());
            chatService.stream(ctx, "git-issue-" + issue.id(), prompt, "chat").blockLast();
            log.info("Git Issue {} 的评论已唤醒 Agent 处理", issue.id());
        } catch (Exception e) {
            log.warn("Git Issue {} 唤醒 Agent 失败：{}", issue.id(), e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
