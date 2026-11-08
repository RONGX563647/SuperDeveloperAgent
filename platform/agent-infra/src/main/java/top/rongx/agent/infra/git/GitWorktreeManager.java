package top.rongx.agent.infra.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.artifact.ArtifactRef;

/**
 * git 仓库托管：bare 对象库 + N 个 worktree（v2 接入设计的底层执行器）。
 *
 * <p>职责：
 * <ul>
 *   <li>建 bare 对象库（{@code workspaces/<id>/git}）；</li>
 *   <li>本地目录收编（remote add agent + push + 用户目录保持独立仓库）；</li>
 *   <li>远程 clone --bare；</li>
 *   <li>worktree 生命周期（add / remove / list / prune）；</li>
 *   <li>LOCAL 来源构建前 fetch（从用户目录拉最新对象）。</li>
 * </ul>
 *
 * <p>分层：infra 只依赖 domain——git 命令直接用 ProcessBuilder（argv、不经 shell、
 * 不走 executor 的 ActionRunner 语义：git 管理命令不需要预算/产物/沙箱）。
 * 命令失败抛 {@link GitIntakeException}，原始报错原样带出（向导 UI 展示）。
 */
public class GitWorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(GitWorktreeManager.class);
    private static final long GIT_TIMEOUT_SECONDS = 600;
    private static final String AGENT_REMOTE = "agent";

    private final Path workspaceRoot;

    public GitWorktreeManager(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /** git 操作失败（带原始报错）。 */
    public static class GitIntakeException extends RuntimeException {
        public GitIntakeException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- 路径

    /** bare 仓库路径：{@code <workspaceRoot>/<projectId>/git}。 */
    public Path bareDir(String projectId) {
        return workspaceRoot.resolve(projectId).resolve("git");
    }

    /** 平台 worktree 根：{@code <workspaceRoot>/<projectId>/worktrees}。 */
    public Path worktreesDir(String projectId) {
        return workspaceRoot.resolve(projectId).resolve("worktrees");
    }

    /** 常驻自检 worktree。 */
    public Path verifyWorktree(String projectId) {
        return worktreesDir(projectId).resolve("wt-verify");
    }

    // ---------------------------------------------------------------- bare / 来源接入

    /** 初始化 bare 对象库（已存在则跳过）。 */
    public void bareInit(String projectId) {
        Path bare = bareDir(projectId);
        if (Files.isDirectory(bare.resolve("objects"))) {
            return;
        }
        try {
            Files.createDirectories(bare.getParent());
        } catch (IOException e) {
            throw new GitIntakeException("无法创建工作区目录 " + bare.getParent() + "：" + e.getMessage());
        }
        run(bare.getParent(), List.of("git", "init", "--bare", "--quiet", posix(bare)), "git init --bare");
    }

    /**
     * 远程 clone（bare）。目标已存在时抛错（防覆盖）。
     */
    public void cloneRemote(String projectId, String repoUrl) {
        Path bare = bareDir(projectId);
        if (Files.isDirectory(bare.resolve("objects"))) {
            throw new GitIntakeException("项目 " + projectId + " 的仓库已存在，不能重复接入");
        }
        try {
            Files.createDirectories(bare.getParent());
        } catch (IOException e) {
            throw new GitIntakeException("无法创建工作区目录：" + e.getMessage());
        }
        run(bare.getParent(), List.of("git", "clone", "--bare", "--quiet", repoUrl, posix(bare)),
                "git clone --bare " + repoUrl);
    }

    /**
     * 本地目录收编（不动用户文件）：
     * <ol>
     *   <li>bare init；</li>
     *   <li>用户目录 {@code remote add agent <bare>}（已有则复用）；</li>
     *   <li>用户目录 {@code push agent HEAD}（对象进 bare）；</li>
     *   <li>bare {@code worktree add <用户目录> <HEAD>}（登记原目录为 worktree）。</li>
     * </ol>
     *
     * @param gitInitIfMissing 用户目录无 .git 时是否允许自动 init
     * @return 用户目录当前 HEAD
     */
    public String linkLocal(String projectId, Path localPath, boolean gitInitIfMissing) {
        Path local = localPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(local)) {
            throw new GitIntakeException("目录不存在：" + local);
        }
        if (!Files.isDirectory(local.resolve(".git"))) {
            if (!gitInitIfMissing) {
                throw new GitIntakeException("目录不是 git 仓库（无 .git）：" + local
                        + "；如需自动 git init 请勾选确认");
            }
            run(local, List.of("git", "init", "--quiet"), "git init");
            run(local, List.of("git", "add", "-A"), "git add -A");
            run(local, List.of("git", "-c", "user.name=agent-intake", "-c",
                    "user.email=agent@local", "commit", "--quiet", "-m", "intake: initial"),
                    "git commit");
        }
        bareInit(projectId);
        Path bare = bareDir(projectId);

        // remote add（已有 agent remote 则跳过）
        ActionResult remotes = run(local, List.of("git", "remote"), "git remote");
        boolean hasAgent = remotes.logTail().lines().anyMatch(l -> l.trim().equals(AGENT_REMOTE));
        if (!hasAgent) {
            run(local, List.of("git", "remote", "add", AGENT_REMOTE, posix(bare)), "git remote add agent");
        }
        // push 当前 HEAD 到 bare（裸 sha 需要显式 refspec 才能落成分支）
        String head = revParse(local, "HEAD");
        run(local, List.of("git", "push", "--quiet", AGENT_REMOTE,
                head + ":refs/heads/" + defaultBranchName(local)), "git push agent");

        // 注意：用户目录有自己独立的 .git，它永远是它自己仓库的主人——
        // 不能（也不需要）把它登记为平台 bare 的 worktree。
        // 平台读取本地代码 = fetchLocal（从用户目录 pull 对象进 bare），构建在平台自己的 worktree 里。
        // 用户目录路径由项目存储记录，UI 上作为 USER 角色展示（见 listWorktrees 的调用方）。
        return head;
    }

    /** 用户目录当前分支名（detached 时用 main 兜底）。 */
    private String defaultBranchName(Path local) {
        ActionResult b = run(local, List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), "git branch name");
        String name = b.logTail().trim();
        return (name.isBlank() || "HEAD".equals(name)) ? "main" : name;
    }

    /** 用户目录是否已登记为 bare 的 worktree。 */
    private boolean isWorktree(Path bare, Path local) {
        ActionResult out = run(bare, List.of("git", "worktree", "list", "--porcelain"), "git worktree list");
        return out.logTail().lines()
                .filter(l -> l.startsWith("worktree "))
                .anyMatch(l -> Path.of(l.substring("worktree ".length()).trim())
                        .toAbsolutePath().normalize().equals(local));
    }

    // ---------------------------------------------------------------- worktree 生命周期

    /**
     * 建 worktree（检出指定 sha，detached）。已存在同名 worktree 则先移除再建。
     *
     * @return worktree 路径
     */
    public Path addWorktree(String projectId, String name, String sha) {
        Path bare = bareDir(projectId);
        Path wt = worktreesDir(projectId).resolve(name);
        try {
            Files.createDirectories(wt.getParent());
        } catch (IOException e) {
            throw new GitIntakeException("无法创建 worktree 目录：" + e.getMessage());
        }
        if (Files.isDirectory(wt)) {
            removeWorktree(projectId, name);
        }
        run(bare, List.of("git", "worktree", "add", "--quiet", "--detach", posix(wt), sha),
                "git worktree add " + name);
        return wt;
    }

    /** 建 worktree（检出指定分支/引用的最新提交）。 */
    public Path addWorktreeAtRef(String projectId, String name, String ref) {
        return addWorktree(projectId, name, revParse(bareDir(projectId), ref));
    }

    /** 移除 worktree（不存在则静默）。 */
    public void removeWorktree(String projectId, String name) {
        Path bare = bareDir(projectId);
        Path wt = worktreesDir(projectId).resolve(name);
        if (!Files.isDirectory(wt)) {
            return;
        }
        run(bare, List.of("git", "worktree", "remove", "--force", posix(wt)),
                "git worktree remove " + name);
    }

    /** 平台管理的 worktree 列表（name → HEAD）。 */
    public List<WorktreeInfo> listWorktrees(String projectId) {
        Path bare = bareDir(projectId);
        if (!Files.isDirectory(bare.resolve("objects"))) {
            return List.of();
        }
        ActionResult out = run(bare, List.of("git", "worktree", "list", "--porcelain"), "git worktree list");
        List<WorktreeInfo> result = new ArrayList<>();
        String currentPath = null;
        String currentHead = null;
        for (String line : out.logTail().lines().toList()) {
            if (line.startsWith("worktree ")) {
                if (currentPath != null) {
                    result.add(toInfo(projectId, Path.of(currentPath), currentHead));
                }
                currentPath = line.substring("worktree ".length()).trim();
                currentHead = null;
            } else if (line.startsWith("HEAD ")) {
                currentHead = line.substring("HEAD ".length()).trim();
            }
        }
        if (currentPath != null) {
            result.add(toInfo(projectId, Path.of(currentPath), currentHead));
        }
        return result;
    }

    private WorktreeInfo toInfo(String projectId, Path path, String head) {
        Path wtRoot = worktreesDir(projectId);
        String role;
        // 用文件名判断角色（porcelain 输出可能是 8.3 短路径，startsWith 不可靠）
        String name = path.getFileName().toString();
        if (name.startsWith("wt-build-")) {
            role = "BUILD";
        } else if (name.startsWith("wt-")) {
            role = "VERIFY";
        } else {
            role = "USER";
        }
        return WorktreeInfo.of(path.toString(), head, role);
    }

    /** 清理失效 worktree 登记（孤儿）。 */
    public void prune(String projectId) {
        Path bare = bareDir(projectId);
        if (Files.isDirectory(bare.resolve("objects"))) {
            run(bare, List.of("git", "worktree", "prune"), "git worktree prune");
        }
    }

    // ---------------------------------------------------------------- fetch / 对比

    /**
     * LOCAL 来源构建前刷新：从用户目录 fetch 最新对象进 bare。
     * 目录不存在时抛错（R7：DEGRADED 语义由调用方处理）。
     */
    public void fetchLocal(String projectId, Path localPath) {
        Path local = localPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(local)) {
            throw new GitIntakeException("本地目录已不存在：" + local + "（项目标记为待修复）");
        }
        run(local, List.of("git", "push", "--quiet", AGENT_REMOTE, "--all"),
                "git push agent --all（同步本地提交）");
    }

    /**
     * DIRECT 模式：纯 git init（不建 bare）——用户目录就地成为 git 仓库。
     */
    public void initBareless(Path dir) {
        run(dir, List.of("git", "init", "--quiet"), "git init");
        run(dir, List.of("git", "add", "-A"), "git add -A");
        run(dir, List.of("git", "-c", "user.name=agent-intake", "-c",
                "user.email=agent@local", "commit", "--quiet", "-m", "intake: initial"),
                "git commit");
    }

    /**
     * 在指定仓库目录（普通 .git 仓库，DIRECT 模式）上挂一个 worktree。
     * worktree 位于 {@code <repoDir>/.agent/worktrees/<name>}。
     */
    public Path addWorktreeAt(Path repoDir, String name, String sha) {
        Path repo = repoDir.toAbsolutePath().normalize();
        Path wt = repo.resolve(".agent").resolve("worktrees").resolve(name);
        try {
            Files.createDirectories(wt.getParent());
        } catch (IOException e) {
            throw new GitIntakeException("无法创建 worktree 目录：" + e.getMessage());
        }
        if (Files.isDirectory(wt)) {
            run(repo, List.of("git", "worktree", "remove", "--force", posix(wt)),
                    "git worktree remove " + name);
        }
        run(repo, List.of("git", "worktree", "add", "--quiet", "--detach", posix(wt), sha),
                "git worktree add " + name);
        return wt;
    }

    /** 移除挂在指定仓库目录上的 worktree（DIRECT 模式清理用）。 */
    public void removeWorktreeAt(Path repoDir, String name) {
        Path repo = repoDir.toAbsolutePath().normalize();
        Path wt = repo.resolve(".agent").resolve("worktrees").resolve(name);
        if (Files.isDirectory(wt)) {
            run(repo, List.of("git", "worktree", "remove", "--force", posix(wt)),
                    "git worktree remove " + name);
        }
    }

    /** REMOTE 来源刷新：bare 内 fetch origin。 */
    public void fetchRemote(String projectId) {
        Path bare = bareDir(projectId);
        if (!Files.isDirectory(bare.resolve("objects"))) {
            throw new GitIntakeException("项目仓库不存在：" + bare);
        }
        run(bare, List.of("git", "fetch", "--all", "--quiet"), "git fetch origin");
    }

    /** 在指定（用户）仓库目录内 fetch 远端（DIRECT/REMOTE 模式同步最新代码）。 */
    public void fetchRemoteIn(Path repoDir) {
        run(repoDir, List.of("git", "fetch", "--all", "--quiet"), "git fetch（用户目录）");
    }

    /**
     * clone 远程仓库到用户指定目录（DIRECT/REMOTE：仓库留在用户选的位置，平台就地操作）。
     * 目录已存在且非空时报错（防覆盖）。
     */
    public void cloneTo(String repoUrl, Path targetDir) {
        Path target = targetDir.toAbsolutePath().normalize();
        if (Files.exists(target)) {
            try (var stream = Files.list(target)) {
                if (stream.findAny().isPresent()) {
                    throw new GitIntakeException("目标目录已存在且非空：" + target + "（请换一个目录或先清空）");
                }
            } catch (IOException e) {
                throw new GitIntakeException("无法读取目标目录：" + e.getMessage());
            }
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new GitIntakeException("无法创建父目录：" + e.getMessage());
        }
        run(target.getParent(), List.of("git", "clone", "--quiet", repoUrl, posix(target)),
                "git clone → " + target);
    }

    /** 解析引用的提交号。 */
    public String revParse(Path repoDir, String ref) {
        return run(repoDir, List.of("git", "rev-parse", ref), "git rev-parse " + ref)
                .logTail().trim().lines().findFirst().orElse("").trim();
    }

    /** 用户目录工作区状态（未提交改动）：porcelain 行列表。 */
    public List<String> statusPorcelain(Path localDir) {
        return run(localDir, List.of("git", "status", "--porcelain"), "git status")
                .logTail().lines().filter(l -> !l.isBlank()).toList();
    }

    /** 提交区间列表（oneline）：{@code from..to}。 */
    public List<String> logRange(Path repoDir, String from, String to) {
        return run(repoDir, List.of("git", "log", "--oneline", from + ".." + to), "git log")
                .logTail().lines().filter(l -> !l.isBlank()).toList();
    }

    /** diff --stat 摘要：{@code from..to}。 */
    public List<String> diffStat(Path repoDir, String from, String to) {
        return run(repoDir, List.of("git", "diff", "--stat", from + ".." + to), "git diff --stat")
                .logTail().lines().filter(l -> !l.isBlank()).toList();
    }

    /** 列出分支（bare 内）：refs/heads/xxx。 */
    public List<String> branches(String projectId) {
        ActionResult out = run(bareDir(projectId),
                List.of("git", "for-each-ref", "--format=%(refname:short)", "refs/heads"), "git branch");
        return out.logTail().lines().filter(l -> !l.isBlank()).map(String::trim).toList();
    }

    // ---------------------------------------------------------------- 内部

    /** 跑一条 git 命令；失败抛 GitIntakeException（带原始报错）。 */
    private ActionResult run(Path dir, List<String> argv, String what) {
        // 目录必须先存在：ProcessBuilder 对无效目录抛 CreateProcess error=267（目录名称无效），
        // 这种 OS 码对用户毫无意义——转成"先接入项目"的可读指引。
        if (dir == null || !Files.isDirectory(dir)) {
            throw new GitIntakeException(
                    what + " 失败：git 仓库目录不存在（" + dir + "）。该项目尚未完成接入，请先在「项目接入」页完成 clone 或本地登记。");
        }
        try {
            Process p = new ProcessBuilder(argv)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) || p.exitValue() != 0) {
                if (!p.waitFor(10, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
                String detail = out.isBlank() ? ("exit " + p.exitValue()) : out;
                log.warn("git 命令失败 [{}]：{}", what, detail);
                throw new GitIntakeException(what + " 失败：\n" + detail);
            }
            return new ActionResult(
                    top.rongx.agent.domain.run.StageResult.success("git", 0, List.of(), 0),
                    out);
        } catch (IOException e) {
            throw new GitIntakeException(what + " 失败（无法启动 git）：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitIntakeException(what + " 被中断");
        }
    }

    /** 列出挂在指定仓库目录（DIRECT 用户目录）下的 worktree。 */
    public List<WorktreeInfo> listWorktreesAt(Path repoDir) {
        Path repo = repoDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(repo.resolve(".git"))) {
            return List.of();
        }
        try {
            ActionResult out = run(repo, List.of("git", "worktree", "list", "--porcelain"),
                    "git worktree list");
            List<WorktreeInfo> result = new ArrayList<>();
            String currentPath = null;
            String currentHead = null;
            for (String line : out.logTail().lines().toList()) {
                if (line.startsWith("worktree ")) {
                    if (currentPath != null) {
                        result.add(toInfoByPath(currentPath, currentHead));
                    }
                    currentPath = line.substring("worktree ".length()).trim();
                    currentHead = null;
                } else if (line.startsWith("HEAD ")) {
                    currentHead = line.substring("HEAD ".length()).trim();
                }
            }
            if (currentPath != null) {
                result.add(toInfoByPath(currentPath, currentHead));
            }
            return result;
        } catch (GitIntakeException e) {
            return List.of();
        }
    }

    private WorktreeInfo toInfoByPath(String path, String head) {
        String name = Path.of(path).getFileName().toString();
        String role = name.startsWith("wt-build-") ? "BUILD"
                : name.startsWith("wt-") ? "VERIFY" : "USER";
        return WorktreeInfo.of(path, head, role);
    }

    /** 项目是否已完成 git 接入（bare 仓库存在）。 */
    public boolean isInitialized(String projectId) {
        return Files.isDirectory(bareDir(projectId).resolve("objects"));
    }

    // ---------------------------------------------------------------- 远程与分支状态（DIRECT/MANAGED 通用）

    /** 列出远程（name + url）。DIRECT 读用户目录，MANAGED 读 bare。 */
    public List<RemoteInfo> remotes(Path repoDir) {
        Path repo = repoDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(repo.resolve(".git")) && !Files.isDirectory(repo.resolve("objects"))) {
            return List.of();
        }
        ActionResult out = run(repo, List.of("git", "remote", "-v"), "git remote -v");
        List<RemoteInfo> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String line : out.logTail().lines().toList()) {
            String[] parts = line.trim().split("\\s+");
            // 每行形如 "origin\thttps://...git (fetch)"；Windows 下第三段是 "(fetch)"、
            // Linux 是 "(fetch)"——一律不看第三段，靠 remote 名去重（原实现比较 "fetch"
            // 导致 Windows 上全部被过滤，remote 列表恒为空）
            if (parts.length >= 2 && !parts[0].isBlank() && seen.add(parts[0])) {
                result.add(new RemoteInfo(parts[0], parts[1]));
            }
        }
        return result;
    }

    /** 当前分支名（detached 返回 "HEAD"）。 */
    public String currentBranch(Path repoDir) {
        ActionResult out = run(repoDir, List.of("git", "rev-parse", "--abbrev-ref", "HEAD"),
                "git branch name");
        String name = out.logTail().trim().lines().findFirst().orElse("").trim();
        return name.isBlank() ? "HEAD" : name;
    }

    /** ref 对应的 upstream（如 origin/main）；无则返回 null。 */
    public String upstreamOf(Path repoDir, String ref) {
        try {
            ActionResult out = run(repoDir,
                    List.of("git", "rev-parse", "--abbrev-ref", ref + "@{upstream}"), "git upstream");
            String name = out.logTail().trim().lines().findFirst().orElse("").trim();
            return (name.isBlank() || name.contains("fatal") || "HEAD".equals(name)) ? null : name;
        } catch (GitIntakeException notTracking) {
            // 分支没有 upstream（本地新分支）：不是错误
            return null;
        }
    }

    /**
     * 领先/落后计数：返回 [ahead, behind]（相对 upstream）。
     * {@code git rev-list --left-right --count upstream...HEAD} → "behind\tahead"。
     */
    public long[] aheadBehind(Path repoDir, String upstream) {
        try {
            ActionResult out = run(repoDir,
                    List.of("git", "rev-list", "--left-right", "--count", upstream + "...HEAD"),
                    "git ahead/behind");
            String line = out.logTail().trim().lines().findFirst().orElse("").trim();
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                return new long[]{Long.parseLong(parts[1]), Long.parseLong(parts[0])};
            }
        } catch (GitIntakeException | NumberFormatException ignored) {
            // 无 upstream / 输出异常：按 0/0
        }
        return new long[]{0, 0};
    }

    /** 远程分支列表（refs/remotes/<remote>/*），用于 UI 展示可对比目标。 */
    public List<String> remoteBranches(Path repoDir, String remote) {
        try {
            ActionResult out = run(repoDir,
                    List.of("git", "for-each-ref", "--format=%(refname:short)",
                            "refs/remotes/" + remote),
                    "git remote branches");
            return out.logTail().lines().filter(l -> !l.isBlank())
                    .map(String::trim)
                    .filter(l -> !l.endsWith("/HEAD"))
                    .toList();
        } catch (GitIntakeException e) {
            return List.of();
        }
    }

    /** 最近提交（oneline，最多 n 条）。 */
    public List<CommitInfo> recentCommits(Path repoDir, int n) {
        return commits(repoDir, 0, n);
    }

    /**
     * 提交分页：skip 跳过前 N 条，limit 取多少条。
     * 供"最近提交"面板展示更长历史（默认 5 条，面板可到 200）。
     */
    public List<CommitInfo> commits(Path repoDir, int skip, int limit) {
        try {
            int safeLimit = Math.min(Math.max(1, limit), 200);
            int safeSkip = Math.max(0, skip);
            List<String> argv = new ArrayList<>(List.of("git", "log",
                    "-n", String.valueOf(safeLimit),
                    "--pretty=format:%h%x09%an%x09%ar%x09%s"));
            if (safeSkip > 0) {
                argv.add("--skip");
                argv.add(String.valueOf(safeSkip));
            }
            ActionResult out = run(repoDir, argv, "git log");
            List<CommitInfo> result = new ArrayList<>();
            for (String line : out.logTail().lines().toList()) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length >= 4) {
                    result.add(new CommitInfo(parts[0], parts[1], parts[2], parts[3]));
                } else {
                    result.add(new CommitInfo(parts[0], "", "", line.trim()));
                }
            }
            return result;
        } catch (GitIntakeException e) {
            return List.of();
        }
    }

    /** 提交摘要（sha 短号 / 作者 / 相对时间 / 标题）。 */
    public record CommitInfo(String sha, String author, String relativeTime, String subject) {
    }

    /** 远程信息（name + url）。 */
    public record RemoteInfo(String name, String url) {
    }

    private String posix(Path path) {
        return path.toString().replace('\\', '/');
    }

    /** worktree 信息（name、path、HEAD、角色）。name 作为正式字段，保证 JSON 可序列化。 */
    public record WorktreeInfo(String name, String path, String head, String role) {

        /** 从路径推导展示名（取最后一段，去掉尾部分隔符）。 */
        public static WorktreeInfo of(String path, String head, String role) {
            return new WorktreeInfo(leafOf(path), path, head, role);
        }

        static String leafOf(String path) {
            if (path == null || path.isBlank()) {
                return "";
            }
            String normalized = path.replace('\\', '/').replaceAll("/+$", "");
            int slash = normalized.lastIndexOf('/');
            String leaf = slash < 0 ? normalized : normalized.substring(slash + 1);
            return leaf.isBlank() ? normalized : leaf;
        }
    }
}
