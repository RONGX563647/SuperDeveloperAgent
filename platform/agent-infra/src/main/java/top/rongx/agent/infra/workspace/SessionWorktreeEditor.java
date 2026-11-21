package top.rongx.agent.infra.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.IntakeException;
import top.rongx.agent.domain.error.RunException;
import top.rongx.agent.domain.fs.PathGuard;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.SessionWorkspaceEditor;
import top.rongx.agent.infra.git.GitWorktreeManager;

/**
 * 会话工作树编辑器实现：每个会话一个独立 worktree + 独立分支。
 *
 * <p>布局与构建工作树一致（{@link ProjectRepoLocator#worktreeRoot}）：
 * DIRECT 在用户目录的 {@code .agent/worktrees}，MANAGED 在平台 {@code workspaces/<id>/worktrees}。
 * 分支固定为 {@code agent/session-<key>}，首次从仓库 HEAD 切出，之后复用。
 *
 * <p>安全边界只有一处：所有路径都必须解析到本会话 worktree 内；
 * 绝对路径、{@code ..}、{@code .git} 段与敏感文件（.env / 私钥）一律拒绝。
 */
@Component
public class SessionWorktreeEditor implements SessionWorkspaceEditor {

    private static final Logger log = LoggerFactory.getLogger(SessionWorktreeEditor.class);

    /** 单文件读写上限（字符）。 */
    private static final int MAX_FILE_CHARS = 200_000;
    /** 列目录返回上限（防止把整个仓库塞进上下文）。 */
    private static final int MAX_LIST_ENTRIES = 500;
    /** 列目录跳过的大目录。 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".agent", "node_modules", "target", "dist");

    private final GitWorktreeManager git;
    private final ProjectRepoLocator locator;

    public SessionWorktreeEditor(GitWorktreeManager git, ProjectRepoLocator locator) {
        this.git = git;
        this.locator = locator;
    }

    /** 定位结果：仓库根、工作树路径、分支与是否 DIRECT 布局。 */
    private record Located(Path repo, Path worktree, String branch, String name, boolean direct) {
    }

    @Override
    public Session open(String projectId, String sessionKey) {
        Located located = locate(projectId, sessionKey);
        boolean created = !Files.exists(located.worktree().resolve(".git"));
        ensure(located, projectId);
        return new Session(located.worktree().toString(), located.branch(),
                git.revParse(located.worktree(), "HEAD"), created);
    }

    @Override
    public List<String> list(String projectId, String sessionKey, String dir, int depth) {
        Located located = ensure(projectId, sessionKey);
        Path wt = located.worktree();
        Path start = dir == null || dir.isBlank() ? wt : resolve(wt, dir);
        if (!Files.isDirectory(start)) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "目录不存在：" + dir);
        }
        int maxDepth = depth <= 0 ? 1 : Math.min(depth, 3);
        List<String> out = new ArrayList<>();
        try {
            Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), maxDepth,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
                            if (!path.equals(start)
                                    && SKIP_DIRS.contains(path.getFileName().toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (!path.equals(start)) {
                                String rel = PathGuard.relative(wt, path);
                                if (rel != null) {
                                    out.add(rel + "/");
                                }
                                if (out.size() >= MAX_LIST_ENTRIES) {
                                    return FileVisitResult.TERMINATE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (SKIP_DIRS.contains(file.getFileName().toString())) {
                                return FileVisitResult.CONTINUE;
                            }
                            String rel = PathGuard.relative(wt, file);
                            if (rel != null) {
                                out.add(rel);
                            }
                            return out.size() >= MAX_LIST_ENTRIES
                                    ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            // 无权限/符号链接错误不应让整个列表失败：跳过该条目
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new IntakeException(ErrorCode.INTAKE_FAILED, "列目录失败：" + e.getMessage(), e);
        }
        out.sort(String::compareTo);
        return out;
    }

    @Override
    public String read(String projectId, String sessionKey, String path) {
        Located located = ensure(projectId, sessionKey);
        Path file = resolve(located.worktree(), path);
        if (!Files.isRegularFile(file)) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "文件不存在：" + path);
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.length() > MAX_FILE_CHARS) {
                throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT,
                        "文件超过 " + MAX_FILE_CHARS + " 字符，拒绝读取：" + path);
            }
            return text;
        } catch (IOException e) {
            throw new IntakeException(ErrorCode.INTAKE_FAILED, "读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void write(String projectId, String sessionKey, String path, String content) {
        Located located = ensure(projectId, sessionKey);
        Path target = resolve(located.worktree(), path);
        rejectSensitive(target);
        String text = content == null ? "" : content;
        if (text.length() > MAX_FILE_CHARS) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT,
                    "内容超过 " + MAX_FILE_CHARS + " 字符，拒绝写入：" + path);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IntakeException(ErrorCode.INTAKE_FAILED, "写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(String projectId, String sessionKey, String path) {
        Located located = ensure(projectId, sessionKey);
        Path target = resolve(located.worktree(), path);
        rejectSensitive(target);
        if (Files.isDirectory(target)) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "只能删除文件，不能删除目录：" + path);
        }
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new IntakeException(ErrorCode.INTAKE_FAILED, "删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Diff diff(String projectId, String sessionKey) {
        Located located = ensure(projectId, sessionKey);
        List<String> changes = git.statusPorcelain(located.worktree());
        String stat = String.join("\n", git.diffStatWorking(located.worktree()));
        return new Diff(changes, stat);
    }

    @Override
    public Commit commit(String projectId, String sessionKey, String message) {
        Located located = ensure(projectId, sessionKey);
        List<String> changes = git.statusPorcelain(located.worktree());
        if (changes.isEmpty()) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "工作树没有可提交的改动");
        }
        List<String> files = changes.stream().map(SessionWorktreeEditor::pathOf).toList();
        String sha = git.commitOnly(located.worktree(), message);
        return new Commit(sha, located.branch(), files);
    }

    // ---------------------------------------------------------------- 内部

    private Located locate(String projectId, String sessionKey) {
        Path repo = locator.repoDir(projectId).orElseThrow(() ->
                new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "项目仓库未接入：" + projectId));
        String slug = sanitize(sessionKey);
        String name = worktreeName(slug, sessionKey);
        String branch = "agent/session-" + (slug.length() <= 48 ? slug : slug.substring(0, 48));
        Path root = locator.worktreeRoot(projectId).orElseThrow(() ->
                new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "项目缺少工作树目录：" + projectId));
        Path worktree = root.resolve(name).toAbsolutePath().normalize();
        boolean direct = locator.modeOf(projectId) == ProjectRepoLocator.StorageMode.DIRECT;
        return new Located(repo, worktree, branch, name, direct);
    }

    private Located ensure(String projectId, String sessionKey) {
        Located located = locate(projectId, sessionKey);
        ensure(located, projectId);
        return located;
    }

    /**
     * 确保 worktree 与分支存在且已切到会话分支（幂等）。
     *
     * <p>已存在则复用，<b>不</b>重建——重建会丢掉本会话未提交的改动。
     */
    private void ensure(Located located, String projectId) {
        Path wt = located.worktree();
        if (!Files.exists(wt.resolve(".git"))) {
            cleanupRemnant(located, projectId);
            String base = git.revParse(located.repo(), "HEAD");
            if (base.isBlank()) {
                throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT,
                        "仓库还没有任何提交，无法创建会话工作树");
            }
            if (located.direct()) {
                git.addWorktreeAt(located.repo(), located.name(), base);
            } else {
                git.addWorktree(projectId, located.name(), base);
            }
        }
        if (!git.localBranches(wt).contains(located.branch())) {
            git.createBranch(wt, located.branch());
        } else if (!located.branch().equals(git.currentBranch(wt))) {
            git.checkout(wt, located.branch());
        }
    }

    /** 目录残留（上次清理中断）时先清登记再让 add 重建。 */
    private void cleanupRemnant(Located located, String projectId) {
        if (!Files.exists(located.worktree())) {
            return;
        }
        try {
            if (located.direct()) {
                git.removeWorktreeAt(located.repo(), located.name());
            } else {
                git.removeWorktree(projectId, located.name());
            }
        } catch (RuntimeException remnantError) {
            // 半残状态（.git 已丢失）由 add 内部的 force remove + prune 兜底
            log.debug("清理残留工作树失败，交给重建兜底：{}（{}）",
                    located.worktree(), remnantError.getMessage());
        }
    }

    /** 会话标识 → 安全 slug（只保留字母数字与 . _ -）。 */
    private static String sanitize(String sessionKey) {
        String raw = sessionKey == null ? "" : sessionKey.trim();
        String slug = raw.replaceAll("[^A-Za-z0-9._-]", "-");
        if (!slug.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "工作树标识不合法：" + sessionKey);
        }
        return slug;
    }

    /** worktree 名：{@code wt-session-<slug>}，超长截断并追加短 hash，整体 ≤64。 */
    private static String worktreeName(String slug, String sessionKey) {
        if (slug.length() <= 40) {
            return "wt-session-" + slug;
        }
        String hash = String.format("%08x", sessionKey.hashCode());
        return "wt-session-" + slug.substring(0, 40) + "-" + hash;
    }

    /** 解析相对路径，并保证结果落在工作树内。 */
    private static Path resolve(Path worktree, String path) {
        if (path == null || path.isBlank()) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "path 不能为空");
        }
        String normalized = path.trim().replace('\\', '/');
        if (normalized.contains(":")) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "不允许盘符或协议前缀：" + path);
        }
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment) || ".git".equalsIgnoreCase(segment)) {
                throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "路径包含非法片段：" + path);
            }
        }
        Path relative = Path.of(normalized);
        if (relative.isAbsolute()) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "不允许绝对路径：" + path);
        }
        try {
            return PathGuard.resolveInside(worktree, normalized);
        } catch (RunException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "路径越界：" + path);
        }
    }

    /** 凭证类文件拒绝写入/删除（Agent 不应触碰密钥）。 */
    private static void rejectSensitive(Path target) {
        String name = target.getFileName() == null
                ? "" : target.getFileName().toString().toLowerCase(Locale.ROOT);
        String path = target.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        boolean sensitive = name.startsWith(".env")
                || name.endsWith(".pem")
                || name.endsWith(".key")
                || name.startsWith("id_rsa")
                || path.contains("/.ssh/")
                || path.endsWith("/.ssh");
        if (sensitive) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT,
                    "拒绝操作凭证类文件：" + target.getFileName());
        }
    }

    /** 状态行 → 相对路径（去掉 porcelain 的两位状态与空格）。 */
    private static String pathOf(String statusLine) {
        String line = statusLine == null ? "" : statusLine;
        return line.length() > 3 ? line.substring(3).trim() : line.trim();
    }

}
