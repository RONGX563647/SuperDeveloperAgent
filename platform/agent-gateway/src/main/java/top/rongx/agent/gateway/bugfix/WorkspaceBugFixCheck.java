package top.rongx.agent.gateway.bugfix;

import top.rongx.agent.domain.process.LogTail;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import top.rongx.agent.domain.http.HttpClients;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixCheckPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.process.BoundedCommand;
import top.rongx.agent.domain.process.CommandShell;
import top.rongx.agent.infra.git.GitWorktreeManager;
import top.rongx.agent.domain.error.PlatformException;

/**
 * 确定性检查实现：构建/测试命令在修复提交的临时 worktree 中执行，冒烟检查直连测试环境。
 *
 * <p>这类检查不依赖模型自述，是「修复是否可信」的硬证据。命令未配置时返回 skipped，
 * 由阶段继续走模型验证；配置了就必须通过，否则回到修复阶段。
 */
@Component
public class WorkspaceBugFixCheck implements BugFixCheckPort {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceBugFixCheck.class);
    private static final long COMMAND_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final int MAX_OUTPUT_CHARS = 8000;

    private final GitWorktreeManager git;
    private final ProjectRepoLocator locator;
    /** 原始配置值；解析推迟到实际执行，配置写错不能阻塞应用启动。 */
    private final String shellConfig;

    public WorkspaceBugFixCheck(GitWorktreeManager git, ProjectRepoLocator locator,
                                @Value("${agent.bugfix.shell:}") String shellConfig) {
        this.git = git;
        this.locator = locator;
        this.shellConfig = shellConfig;
    }

    @Override
    public CheckResult build(BugFixRun run, BugFixPolicy policy, String sha) {
        if (policy.productionLike(policy.testEnv())) {
            return CheckResult.fail("禁止在生产环境执行构建检查：" + policy.testEnv(), "");
        }
        String build = policy.buildCommand() == null ? "" : policy.buildCommand().trim();
        String test = policy.testCommand() == null ? "" : policy.testCommand().trim();
        if (build.isEmpty() && test.isEmpty()) {
            return CheckResult.skipped("未配置 buildCommand / testCommand");
        }
        if (sha == null || !sha.matches("[0-9a-f]{7,40}")) {
            return CheckResult.fail("修复提交非法，无法执行构建检查：" + sha, "");
        }
        Path repo = locator.repoDir(run.projectId()).orElse(null);
        if (repo == null) {
            return CheckResult.fail("项目尚未接入 git 仓库，无法执行构建检查", "");
        }
        if (!Files.exists(repo.resolve(".git")) && !Files.isDirectory(repo.resolve("objects"))) {
            return CheckResult.fail("项目目录不是 git 仓库根目录：" + repo, "");
        }
        String name = "wt-check-" + run.id() + "-" + UUID.randomUUID().toString().substring(0, 6);
        ProjectRepoLocator.StorageMode mode = locator.modeOf(run.projectId());
        Path worktree = mode == ProjectRepoLocator.StorageMode.DIRECT
                ? git.addWorktreeAt(repo, name, sha)
                : git.addWorktree(run.projectId(), name, sha);
        try {
            if (!build.isEmpty()) {
                CommandResult result = execute(worktree, build);
                if (!result.passed()) {
                    return CheckResult.fail("构建命令失败（exit=" + result.exitCode() + "）",
                            result.output());
                }
            }
            if (!test.isEmpty()) {
                CommandResult result = execute(worktree, test);
                if (!result.passed()) {
                    return CheckResult.fail("测试命令失败（exit=" + result.exitCode() + "）",
                            result.output());
                }
            }
            return CheckResult.pass("构建/测试命令通过", "");
        } finally {
            try {
                if (mode == ProjectRepoLocator.StorageMode.DIRECT) {
                    git.removeWorktreeAt(repo, name);
                } else {
                    git.removeWorktree(run.projectId(), name);
                }
            } catch (RuntimeException e) {
                log.warn("清理构建检查 worktree 失败：{}", e.getMessage());
            }
        }
    }

    @Override
    public CheckResult smoke(BugFixRun run, BugFixPolicy policy) {
        String baseUrl = policy.baseUrl() == null ? "" : policy.baseUrl().trim();
        if (baseUrl.isEmpty()) {
            return CheckResult.skipped("未配置 baseUrl");
        }
        if (policy.productionLike(policy.testEnv())) {
            return CheckResult.fail("禁止对生产环境做自动冒烟检查：" + policy.testEnv(), "");
        }
        try {
            HttpClient client = HttpClients.builder(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            // 4xx 说明服务已起来（可能需要登录/路由不存在），5xx 或连不上才算冒烟失败
            if (status >= 500) {
                return CheckResult.fail("测试环境返回 " + status + "：" + baseUrl,
                        LogTail.prefix(response.body(), MAX_OUTPUT_CHARS, "…"));
            }
            return CheckResult.pass("测试环境可访问（HTTP " + status + "）", "");
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return CheckResult.fail("测试环境不可访问：" + baseUrl + "（" + e.getMessage() + "）", "");
        }
    }

    /**
     * 走共用的有界执行原语：并行排空输出、硬超时、超时杀进程树。
     *
     * <p>原实现写死 {@code /bin/sh -c}（Windows 直接失败），且超时只
     * {@code destroyForcibly} 主进程：构建工具拉起的子进程会残留并持有管道写端，
     * 与「缺镜像静默卡住」同根。解释器前缀改由 {@link CommandShell} 按 OS 自适应
     * 或配置覆盖，执行预算与进程树清理统一交给 {@link BoundedCommand}。
     */
    private CommandResult execute(Path worktree, String command) {
        // 策略输入只允许单行：换行会把一条配置变成多条命令，属于配置注入面
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            return new CommandResult(false, -1,
                    "构建/测试命令不允许包含换行：请在设置页改成单行命令，复合命令请写成项目内脚本");
        }
        List<String> shellPrefix;
        try {
            shellPrefix = CommandShell.prefix(shellConfig);
        } catch (PlatformException | IllegalArgumentException e) {
            return new CommandResult(false, -1, e.getMessage());
        }
        BoundedCommand.Outcome outcome = BoundedCommand.run(
                CommandShell.argv(shellPrefix, command), worktree, COMMAND_TIMEOUT_MS);
        String output = LogTail.prefix(outcome.output(), MAX_OUTPUT_CHARS, "…");
        if (outcome.ok()) {
            return new CommandResult(true, outcome.exitCode(), output);
        }
        String reason = outcome.timedOut()
                ? "命令超时（" + (COMMAND_TIMEOUT_MS / 1000) + " 秒），已终止进程树"
                : outcome.detail();
        return new CommandResult(false, outcome.exitCode(), LogTail.prefix(reason + "\n" + output, MAX_OUTPUT_CHARS, "…"));
    }


    private record CommandResult(boolean passed, int exitCode, String output) {
    }

    /** 供测试与其他适配器复用的命令列表（当前只用于日志）。 */
    static List<String> commands(BugFixPolicy policy) {
        return List.of(policy.buildCommand(), policy.testCommand()).stream()
                .filter(command -> command != null && !command.isBlank()).toList();
    }
}
