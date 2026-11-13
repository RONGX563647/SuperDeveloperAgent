package top.rongx.agent.gateway.bugfix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixGitPort;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.patch.CodePatch;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.gateway.gitissue.GitIssueRemoteClient;
import top.rongx.agent.infra.git.GitWorktreeManager;

/**
 * 自动修复的 Git 适配器：在基线提交上建临时 worktree → 写修复文件 → 提交到独立分支 → 推送并建 PR。
 *
 * <p>安全边界：
 * <ul>
 *   <li>只写 {@code agent/bugfix-*} 分支，绝不触碰默认分支，也永不自动合并；</li>
 *   <li>路径逃逸、{@code .git} 内部、密钥/凭证文件一律拒绝；</li>
 *   <li>文件数与单文件大小有上限，Agent 无法把整仓内容当成一次补丁。</li>
 * </ul>
 */
@Component
public class WorkspaceBugFixGit implements BugFixGitPort {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceBugFixGit.class);
    private static final int MAX_FILES = 15;
    private static final int MAX_CONTENT_CHARS = 400_000;

    private final GitWorktreeManager git;
    private final ProjectRepoLocator locator;
    private final GitIssueRemoteClient remote;

    public WorkspaceBugFixGit(GitWorktreeManager git, ProjectRepoLocator locator,
                              GitIssueRemoteClient remote) {
        this.git = git;
        this.locator = locator;
        this.remote = remote;
    }

    @Override
    public String headSha(String projectId) {
        Path repo = requireRepo(projectId);
        // 优先远端默认分支，避免以用户本地当前分支（可能是未合并的 feature）为基线
        for (String ref : List.of("refs/remotes/origin/HEAD", "refs/remotes/origin/main",
                "refs/remotes/origin/master", "refs/heads/main", "refs/heads/master", "HEAD")) {
            try {
                String sha = git.revParse(repo, ref);
                if (sha != null && !sha.isBlank()) {
                    return sha;
                }
            } catch (RuntimeException ignored) {
                // 该引用不存在，继续尝试下一个
            }
        }
        throw new IllegalStateException("仓库没有可用提交：" + projectId);
    }

    @Override
    public String commitFix(BugFixRun run, List<CodePatch.FileEdit> edits, String message) {
        if (edits == null || edits.isEmpty()) {
            throw new IllegalArgumentException("修复补丁为空");
        }
        if (edits.size() > MAX_FILES) {
            throw new IllegalArgumentException("一次最多修改 " + MAX_FILES + " 个文件，收到 "
                    + edits.size());
        }
        Path repo = requireRepo(run.projectId());
        String baseSha = run.baseSha() == null || run.baseSha().isBlank()
                ? headSha(run.projectId()) : run.baseSha();
        if (baseSha == null || !baseSha.matches("[0-9a-f]{7,40}")) {
            throw new IllegalArgumentException("基线提交非法：" + baseSha);
        }
        if (git.revParse(repo, baseSha + "^{commit}").isBlank()) {
            throw new IllegalArgumentException("基线提交不存在：" + baseSha);
        }
        String name = "wt-bugfix-" + run.id();
        ProjectRepoLocator.StorageMode mode = locator.modeOf(run.projectId());
        Path worktree = mode == ProjectRepoLocator.StorageMode.DIRECT
                ? git.addWorktreeAt(repo, name, baseSha)
                : git.addWorktree(run.projectId(), name, baseSha);
        try {
            for (CodePatch.FileEdit edit : edits) {
                String path = normalize(edit.path());
                validate(path, edit.content());
                Path target = resolveInside(worktree, path);
                try {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, edit.content() == null ? "" : edit.content(),
                            StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("写入修复文件失败：" + path + "（"
                            + e.getMessage() + "）");
                }
            }
            // -B：重试时把旧分支重置到基线，避免失败尝试的提交叠加
            git.resetBranch(worktree, run.branch());
            return git.commitAll(worktree, message);
        } finally {
            if (mode == ProjectRepoLocator.StorageMode.DIRECT) {
                git.removeWorktreeAt(repo, name);
            } else {
                git.removeWorktree(run.projectId(), name);
            }
        }
    }

    @Override
    public String openPullRequest(BugFixRun run, String title, String body) {
        GitIssueRemoteClient.Credentials credentials = remote.credentials(
                        run.userId(), run.provider(), run.repo())
                .orElseThrow(() -> new IllegalStateException(
                        "未绑定 " + run.provider() + " 账号，无法推送分支与创建 PR"));
        Path repo = requireRepo(run.projectId());
        String remoteUrl = httpsUrl(credentials);
        GitWorktreeManager.GitAuth auth = new GitWorktreeManager.GitAuth(
                username(credentials.provider()), credentials.token());
        log.info("推送修复分支：repo={} branch={} provider={}", run.repo(),
                run.branch(), run.provider());
        git.pushBranchTo(repo, run.branch(), remoteUrl, auth);
        String base = remote.defaultBranch(credentials);
        return remote.openPullRequest(credentials, run.branch(), base, title, body);
    }

    private Path requireRepo(String projectId) {
        return locator.repoDir(projectId).orElseThrow(() ->
                new IllegalStateException("项目尚未接入 git 仓库：" + projectId));
    }

    private static String normalize(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        return value;
    }

    private static void validate(String path, String content) {
        if (path.isBlank() || path.startsWith("/") || path.contains("..")) {
            throw new IllegalArgumentException("非法文件路径：" + path);
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.equals(".git") || lower.startsWith(".git/") || lower.contains("/.git/")) {
            throw new IllegalArgumentException(".git 目录不允许修改：" + path);
        }
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        if (name.startsWith(".env") || name.endsWith(".pem") || name.endsWith(".key")
                || name.startsWith("id_rsa") || name.startsWith("id_ed25519")
                || name.contains("credential") || name.contains("secret")) {
            throw new IllegalArgumentException("密钥/凭证文件不允许由 Agent 修改：" + path);
        }
        if (content != null && content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("文件内容过大：" + path + "（上限 "
                    + MAX_CONTENT_CHARS + " 字符）");
        }
    }

    private static Path resolveInside(Path root, String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IllegalArgumentException("路径超出仓库范围：" + relative);
        }
        return resolved;
    }

    private static String username(String provider) {
        return switch (provider) {
            case "GITHUB" -> "x-access-token";
            case "GITLAB" -> "oauth2";
            default -> "git";
        };
    }

    private static String httpsUrl(GitIssueRemoteClient.Credentials c) {
        String host = c.host() == null ? "" : c.host().trim();
        if (host.isBlank()) {
            host = switch (c.provider()) {
                case "GITEE" -> "gitee.com";
                case "GITHUB" -> "github.com";
                default -> "";
            };
        }
        if (host.isBlank()) {
            throw new IllegalStateException("无法确定 " + c.provider() + " 的仓库域名");
        }
        return "https://" + host + "/" + c.repo() + ".git";
    }
}
