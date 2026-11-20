package top.rongx.agent.gateway.gitissue;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.port.UserRolePort;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.runtime.ChatService;

/**
 * Git 评论触发 Agent：只有「显式指令型」评论才唤醒，避免普通讨论也消耗模型。
 *
 * <p>显式指令判定（BUG-50）：命令词必须出现在显式指令位——行首（可带 {@code /} 前缀）、
 * 行首冒号引导（如 {@code 排查：…}）——或评论以 {@code @agent} / {@code /agent} 显式点名开头。
 * 弱词（please / 分析 / 看看 / 处理 / handle 等）不再参与判定；拉丁词按词边界匹配，
 * 避免子串误命中（如 {@code fixed} 里的 {@code fix}）。
 *
 * <p>并发有界：固定小线程池（2 个虚拟线程）+ 全局 {@link Semaphore}(2)，
 * 突发评论不会同时拉起大量模型会话。
 *
 * <p>角色（BUG-48）：readOnly 按 {@code issue.userId()} 的真实角色推导
 * （{@link UserRolePort#isReadOnly}，未知用户 fail-closed 按只读），
 * 不再硬编码 {@code false}；Agent 也不被授权自行关闭任务（关闭只能人工操作）。
 *
 * <p>在独立线程里跑，webhook 回调线程已提前返回；失败只记日志，不影响任务同步。
 * 任务必须已关联项目（projectId 非空）才会唤醒——没有项目上下文时 Agent 无法安全执行。
 */
@Component
public class GitIssueAgentWaker {

    private static final Logger log = LoggerFactory.getLogger(GitIssueAgentWaker.class);

    /** 全局同时在跑的唤醒会话上限。 */
    static final int MAX_CONCURRENT_WAKES = 2;

    /** 强指令词（中英文）；只允许出现在显式指令位，弱词（please/分析/看看/处理/handle 等）已移除。 */
    private static final String COMMAND_WORDS = "排查|修复|定位|fix|debug|investigate";

    /** 显式点名：@agent 或 /agent 开头（忽略行首空白）。 */
    private static final Pattern EXPLICIT_MENTION =
            Pattern.compile("(?i)^\\s*[@/]agent\\b");

    /**
     * 显式指令位：任一行的行首（可带 {@code /} 前缀）出现命令词；
     * 拉丁词要求词边界，避免 "fixed" 误命中 "fix"。
     */
    private static final Pattern EXPLICIT_COMMAND = Pattern.compile(
            "(?im)^\\s*/?(?:排查|修复|定位|(?:fix|debug|investigate)\\b)");

    private final ChatService chatService;
    private final ProjectRepository projects;
    private final UserRolePort userRoles;
    private final Semaphore permits = new Semaphore(MAX_CONCURRENT_WAKES);
    private final ExecutorService executor = Executors.newFixedThreadPool(
            MAX_CONCURRENT_WAKES, Thread.ofVirtual().factory());

    public GitIssueAgentWaker(ChatService chatService, ProjectRepository projects,
                              UserRolePort userRoles) {
        this.chatService = chatService;
        this.projects = projects;
        this.userRoles = userRoles;
    }

    /** 评论含显式指令且任务已关联项目时，异步唤醒 Agent（并发有界）。 */
    public void wakeIfCommand(GitIssue issue, String comment) {
        if (issue == null || comment == null || comment.isBlank()) {
            return;
        }
        if (issue.projectId().isBlank()) {
            log.debug("任务 {} 未关联项目，跳过 Agent 唤醒", issue.id());
            return;
        }
        String reason = matchReason(comment);
        if (reason == null) {
            return;
        }
        executor.submit(() -> wake(issue, comment, reason));
    }

    /** 命中原因（explicit-mention / explicit-command），未命中返回 null。 */
    static String matchReason(String comment) {
        if (EXPLICIT_MENTION.matcher(comment).find()) {
            return "explicit-mention";
        }
        if (EXPLICIT_COMMAND.matcher(comment).find()) {
            return "explicit-command";
        }
        return null;
    }

    private void wake(GitIssue issue, String comment, String reason) {
        try {
            permits.acquire();
            try {
                doWake(issue, comment, reason);
            } finally {
                permits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Git Issue {} 唤醒 Agent 失败：{}", issue.id(), e.getMessage());
        }
    }

    private void doWake(GitIssue issue, String comment, String reason) {
        Project project = projects.findById(issue.projectId()).orElse(null);
        if (project == null) {
            return;
        }
        // BUG-27：环境口径不按 findEnvs 顺序猜测。任务本身没有环境选择，评论区触发统一按 test，
        // 用户需要其他环境时必须在平台侧显式发起会话（跟随用户选择）。
        String env = "test";
        // BUG-48：按 issue 用户的真实角色推导 readOnly（未知用户 fail-closed = 只读）
        boolean readOnly = userRoles.isReadOnly(issue.userId());
        ProjectContext ctx = new ProjectContext(project.id(), env, issue.userId(),
                project.repoUrl(), null, readOnly);
        String prompt = """
                Git Issue #%s 收到新评论，请处理：
                %s

                先调用 list_git_issues 读取任务详情；需要时用 comment_git_issue 回写结论。
                不要重复回复相同内容。如需关闭任务，请在评论中说明并等待人工确认；
                Agent 不自行关闭任务（关闭只能由人操作）。
                """.formatted(issue.number(), comment.trim());
        chatService.stream(ctx, "git-issue-" + issue.id(), prompt, "chat").blockLast();
        log.info("Git Issue {} 的评论已唤醒 Agent 处理（原因：{}，readOnly={}）",
                issue.id(), reason, readOnly);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
