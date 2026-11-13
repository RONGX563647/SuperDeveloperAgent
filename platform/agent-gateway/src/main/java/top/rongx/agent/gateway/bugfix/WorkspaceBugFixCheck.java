package top.rongx.agent.gateway.bugfix;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixCheckPort;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.infra.git.GitWorktreeManager;

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

    public WorkspaceBugFixCheck(GitWorktreeManager git, ProjectRepoLocator locator) {
        this.git = git;
        this.locator = locator;
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
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
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
                        clip(response.body()));
            }
            return CheckResult.pass("测试环境可访问（HTTP " + status + "）", "");
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return CheckResult.fail("测试环境不可访问：" + baseUrl + "（" + e.getMessage() + "）", "");
        }
    }

    private CommandResult execute(Path worktree, String command) {
        try {
            Process process = new ProcessBuilder("/bin/sh", "-c", command)
                    .directory(worktree.toFile())
                    .redirectErrorStream(true)
                    .start();
            StringBuffer output = new StringBuffer();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var stream = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        if (output.length() < MAX_OUTPUT_CHARS) {
                            output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                        }
                    }
                } catch (IOException ignored) {
                    // 进程退出时流关闭属于正常情况
                }
            });
            boolean finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(Duration.ofSeconds(2));
                return new CommandResult(false, -1,
                        clip("命令超时（" + (COMMAND_TIMEOUT_MS / 1000) + " 秒）：" + command
                                + "\n" + output));
            }
            reader.join(Duration.ofSeconds(2));
            return new CommandResult(process.exitValue() == 0, process.exitValue(),
                    clip(output.toString()));
        } catch (IOException e) {
            return new CommandResult(false, -1, "命令无法执行：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(false, -1, "命令执行被中断");
        }
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_OUTPUT_CHARS ? value
                : value.substring(0, MAX_OUTPUT_CHARS) + "…";
    }

    private record CommandResult(boolean passed, int exitCode, String output) {
    }

    /** 供测试与其他适配器复用的命令列表（当前只用于日志）。 */
    static List<String> commands(BugFixPolicy policy) {
        return List.of(policy.buildCommand(), policy.testCommand()).stream()
                .filter(command -> command != null && !command.isBlank()).toList();
    }
}
