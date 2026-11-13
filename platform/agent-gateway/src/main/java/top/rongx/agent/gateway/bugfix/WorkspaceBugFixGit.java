package top.rongx.agent.gateway.bugfix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixEdit;
import top.rongx.agent.domain.bugfix.BugFixGitPort;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.gateway.gitissue.GitIssueRemoteClient;
import top.rongx.agent.infra.git.GitWorktreeManager;

/**
 * 自动修复的 Git 适配器：在基线提交上建临时 worktree → 应用补丁 → 提交到独立分支 → 推送并建 PR。
 *
 * <p>安全边界：
 * <ul>
 *   <li>只写 {@code agent/bugfix-*} 分支，绝不触碰默认分支，也永不自动合并；</li>
 *   <li>路径逃逸、{@code .git} 内部、密钥/凭证文件一律拒绝；</li>
 *   <li>文件数、单文件大小与二进制内容有上限，Agent 无法把整仓内容当成一次补丁；</li>
 *   <li>片段替换要求 {@code old} 在基线文件中唯一出现，避免模型猜错位置改坏代码。</li>
 * </ul>
 */
@Component
public class WorkspaceBugFixGit implements BugFixGitPort {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceBugFixGit.class);
    private static final int MAX_FILES = 15;
    private static final int MAX_EDITS = 30;
    private static final int MAX_CONTENT_CHARS = 400_000;
    private static final long MAX_FILE_BYTES = 2_000_000L;
    private static final String BRANCH_PREFIX = "agent/bugfix-";

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
    public void sync(String projectId) {
        locator.sync(projectId);
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
    public String commitFix(BugFixRun run, List<BugFixEdit> edits, String message) {
        if (edits == null || edits.isEmpty()) {
            throw new IllegalArgumentException("修复补丁为空");
        }
        if (edits.size() > MAX_EDITS) {
            throw new IllegalArgumentException("一次最多 " + MAX_EDITS + " 处改动，收到 "
                    + edits.size());
        }
        long files = edits.stream().map(edit -> normalize(edit.path())).distinct().count();
        if (files > MAX_FILES) {
            throw new IllegalArgumentException("一次最多修改 " + MAX_FILES + " 个文件，收到 " + files);
        }
        Path repo = requireRepo(run.projectId());
        String baseSha = run.baseSha() == null || run.baseSha().isBlank()
                ? headSha(run.projectId()) : run.baseSha();
        if (!baseSha.matches("[0-9a-f]{7,40}")) {
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
            applyEdits(worktree, edits);
            if (git.statusPorcelain(worktree).isEmpty()) {
                throw new IllegalArgumentException("补丁未产生实际改动（old 与 new 相同，或片段替换后内容未变）");
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

    /** 按顺序应用改动：同一文件的多个片段替换会依次叠加。 */
    private static void applyEdits(Path worktree, List<BugFixEdit> edits) {
        for (BugFixEdit edit : edits) {
            String path = normalize(edit.path());
            validate(path, edit.replaceMode() ? edit.newText() : edit.content());
            Path target = resolveInside(worktree, path);
            String text;
            if (edit.replaceMode()) {
                text = replaceOnce(target, path, edit.oldText(), edit.newText());
            } else {
                text = edit.content() == null ? "" : edit.content();
            }
            write(target, path, text);
        }
    }

    private static String replaceOnce(Path target, String path, String oldText, String newText) {
        String current = readIfExists(target, path);
        if (oldText == null || oldText.isEmpty()) {
            // 新建文件：绝不覆盖已有内容，防止模型用「空片段」把整个文件清空
            if (!current.isEmpty()) {
                throw new IllegalArgumentException("文件已存在，拒绝用空片段整体覆盖：" + path);
            }
            if (newText == null || newText.isEmpty()) {
                throw new IllegalArgumentException("新建文件内容为空：" + path);
            }
            return newText;
        }
        int first = current.indexOf(oldText);
        if (first < 0) {
            throw new IllegalArgumentException("替换片段未在基线文件中出现（文件可能已变更或片段不精确）："
                    + path);
        }
        if (current.indexOf(oldText, first + oldText.length()) >= 0) {
            throw new IllegalArgumentException("替换片段在文件中出现多次，请提供更长且唯一的上下文："
                    + path);
        }
        return current.substring(0, first) + (newText == null ? "" : newText)
                + current.substring(first + oldText.length());
    }

    private static String readIfExists(Path target, String path) {
        if (!Files.exists(target)) {
            return "";
        }
        try {
            if (Files.size(target) > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("文件过大，自动修复拒绝修改：" + path);
            }
            String text = Files.readString(target, StandardCharsets.UTF_8);
            if (text.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("二进制文件不允许由 Agent 修改：" + path);
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("读取基线文件失败：" + path + "（" + e.getMessage() + "）");
        }
    }

    private static void write(Path target, String path, String text) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入修复文件失败：" + path + "（" + e.getMessage() + "）");
        }
    }

    @Override
    public List<String> changedFiles(BugFixRun run, String fixSha) {
        if (fixSha == null || !fixSha.matches("[0-9a-f]{7,40}")) {
            throw new IllegalArgumentException("修复提交非法：" + fixSha);
        }
        String base = run.baseSha();
        if (base == null || !base.matches("[0-9a-f]{7,40}")) {
            throw new IllegalStateException("缺少合法的基线提交，无法校验改动范围");
        }
        Path repo = requireRepo(run.projectId());
        return git.diffNames(repo, base, fixSha);
    }

    @Override
    public String openPullRequest(BugFixRun run, String title, String body) {
        if (remote == null) {
            throw new IllegalStateException("未配置 Git 远端客户端，无法创建 PR");
        }
        GitIssueRemoteClient.Credentials credentials = remote.credentials(
                        run.userId(), run.provider(), run.repo())
                .orElseThrow(() -> new IllegalStateException(
                        "未绑定 " + run.provider() + " 账号，无法推送分支与创建 PR"));
        // 幂等：上一步创建成功但响应超时/重试时，直接复用已有 PR，避免重复创建
        Optional<String> existing = remote.findPullRequest(credentials, run.branch());
        if (existing.isPresent()) {
            log.info("修复分支已有打开的 PR，直接复用：{}", existing.get());
            return existing.get();
        }
        Path repo = requireRepo(run.projectId());
        String remoteUrl = httpsUrl(credentials);
        GitWorktreeManager.GitAuth auth = new GitWorktreeManager.GitAuth(
                username(credentials.provider()), credentials.token());
        log.info("推送修复分支：repo={} branch={} provider={}", run.repo(),
                run.branch(), run.provider());
        git.forcePushBranchTo(repo, run.branch(), remoteUrl, auth);
        String base = remote.defaultBranch(credentials);
        try {
            return remote.openPullRequest(credentials, run.branch(), base, title, body);
        } catch (RuntimeException e) {
            // 远端报「已存在」或上次创建其实成功时，再查一次，保证幂等
            Optional<String> again = remote.findPullRequest(credentials, run.branch());
            if (again.isPresent()) {
                return again.get();
            }
            throw e;
        }
    }

    private Path requireRepo(String projectId) {
        Path repo = locator.repoDir(projectId).orElseThrow(() ->
                new IllegalStateException("项目尚未接入 git 仓库：" + projectId));
        // 关键防坑：项目目录若只是普通目录（甚至位于另一个仓库的子目录），
        // git 命令会「向上找到父仓库」并静默操作错误仓库。这里要求它本身就是仓库根。
        if (!Files.exists(repo.resolve(".git")) && !Files.isDirectory(repo.resolve("objects"))) {
            throw new IllegalStateException("项目目录不是 git 仓库根目录：" + repo);
        }
        return repo;
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
        if (content != null && content.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("二进制内容不允许由 Agent 写入：" + path);
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
