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
    /** fetch 单独收紧超时：远端不可达时不该让一次运行挂满 10 分钟。 */
    private static final long FETCH_TIMEOUT_SECONDS = 90;
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
        run(bare, List.of("git", "fetch", "--all", "--quiet"), "git fetch origin", FETCH_TIMEOUT_SECONDS);
    }

    /** 在指定（用户）仓库目录内 fetch 远端（DIRECT/REMOTE 模式同步最新代码）。 */
    public void fetchRemoteIn(Path repoDir) {
        run(repoDir, List.of("git", "fetch", "--all", "--quiet"), "git fetch（用户目录）",
                FETCH_TIMEOUT_SECONDS);
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
        try {
            return tryRun(localDir, List.of("git", "status", "--porcelain"), "git status")
                    .logTail().lines().filter(l -> !l.isBlank()).toList();
        } catch (GitIntakeException noWorkTree) {
            // bare 仓库（MANAGED）没有工作区："this operation must be run in a work tree" 是预期结果
            return List.of();
        }
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

    /** 列出分支（普通仓库/worktree 内）：refs/heads/xxx。 */
    public List<String> localBranches(Path repoDir) {
        ActionResult out = run(repoDir,
                List.of("git", "for-each-ref", "--format=%(refname:short)", "refs/heads"), "git branch");
        return out.logTail().lines().filter(l -> !l.isBlank()).map(String::trim).toList();
    }

    /**
     * 在指定仓库目录切换分支（DIRECT 的用户目录 / MANAGED 的 worktree）。
     *
     * <p>只接受普通分支名：拒绝空白、以 {@code -} 开头、含路径穿越的输入，
     * 避免把参数拼成 git 选项或跳出仓库。
     */
    public void checkout(Path repoDir, String branch) {
        String name = branch == null ? "" : branch.trim();
        if (!name.matches("[A-Za-z0-9._/-]+") || name.startsWith("-") || name.contains("..")) {
            throw new GitIntakeException("分支名不合法：" + branch);
        }
        run(repoDir, List.of("git", "checkout", "--quiet", name), "git checkout " + name);
    }

    // ---------------------------------------------------------------- 受限补丁（读内容 / 生成 diff / 提交）

    /** 读取某个提交里的文件内容；文件不存在返回空串（新文件场景）。 */
    public String showFile(Path repoDir, String ref, String path) {
        Raw raw = exec(repoDir, List.of("git", "show", ref + ":" + path), "git show", 60);
        return raw.exit() == 0 ? raw.output() : "";
    }

    /**
     * 生成统一 diff（{@code git diff --no-index}）：把新旧内容写到临时目录再比。
     *
     * <p>放在 infra 的原因：tools 层不允许起进程（ArchUnit A7）。
     * 退出码 0 = 无差异，1 = 有差异，其余按失败处理。
     */
    public String diffNoIndex(Path repoDir, String relPath, String oldText, String newText) {
        Path tmp;
        try {
            tmp = Files.createTempDirectory("agent-patch-");
        } catch (IOException e) {
            throw new GitIntakeException("无法创建临时目录：" + e.getMessage());
        }
        try {
            writeText(tmp.resolve("a").resolve(relPath), oldText);
            writeText(tmp.resolve("b").resolve(relPath), newText);
            Raw raw = exec(tmp, List.of("git", "diff", "--no-index", "--unified=3", "--no-color",
                    "--", "a/" + relPath, "b/" + relPath), "git diff", 60);
            if (raw.exit() > 1) {
                throw new GitIntakeException("生成 diff 失败：\n" + raw.output());
            }
            return rewriteDiffHeaders(raw.output(), relPath);
        } finally {
            deleteRecursively(tmp);
        }
    }

    /** 在指定仓库目录创建并切换到新分支。 */
    public void createBranch(Path repoDir, String branch) {
        String name = branch == null ? "" : branch.trim();
        if (!name.matches("[A-Za-z0-9._/-]+") || name.startsWith("-") || name.contains("..")) {
            throw new GitIntakeException("分支名不合法：" + branch);
        }
        run(repoDir, List.of("git", "checkout", "--quiet", "-b", name),
                "git checkout -b " + name);
    }

    /** 暂存全部改动并提交，返回新提交 sha。 */
    public String commitAll(Path repoDir, String message) {
        run(repoDir, List.of("git", "add", "-A"), "git add -A");
        run(repoDir, List.of("git", "-c", "user.name=agent", "-c", "user.email=agent@local",
                "commit", "--quiet", "-m", message), "git commit");
        return revParse(repoDir, "HEAD");
    }

    /** 把 --no-index 产出的 a/a/x、b/b/x 头改写为 a/x、b/x（供人工阅读）。 */
    private static String rewriteDiffHeaders(String diff, String relPath) {
        StringBuilder out = new StringBuilder();
        for (String line : diff.split("\\R", -1)) {
            // 丢弃 git 的行尾告警（CRLF 提示等），它们不属于补丁内容
            if (line.startsWith("warning:") || line.startsWith("hint:")) {
                continue;
            }
            String rewritten;
            if (line.startsWith("diff --git ")) {
                rewritten = "diff --git a/" + relPath + " b/" + relPath;
            } else if (line.startsWith("--- ") && !line.startsWith("--- /dev/null")) {
                rewritten = "--- a/" + relPath;
            } else if (line.startsWith("+++ ") && !line.startsWith("+++ /dev/null")) {
                rewritten = "+++ b/" + relPath;
            } else {
                rewritten = line;
            }
            out.append(rewritten).append('\n');
        }
        return out.toString();
    }

    private static void writeText(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text == null ? "" : text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GitIntakeException("写临时文件失败：" + file + "（" + e.getMessage() + "）");
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响主流程
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 跑一条 git 命令；失败抛 GitIntakeException（带原始报错）。 */
    private ActionResult run(Path dir, List<String> argv, String what) {
        return run(dir, argv, what, GIT_TIMEOUT_SECONDS);
    }

    private ActionResult run(Path dir, List<String> argv, String what, long timeoutSeconds) {
        return run(dir, argv, what, timeoutSeconds, false);
    }

    /**
     * 允许失败的探测（如"分支没有 upstream"）：非零退出不算异常信号，不记 WARN。
     * 仍然抛异常，由调用方决定如何降级。
     */
    private ActionResult tryRun(Path dir, List<String> argv, String what) {
        return run(dir, argv, what, GIT_TIMEOUT_SECONDS, true);
    }

    private ActionResult run(Path dir, List<String> argv, String what, long timeoutSeconds, boolean quiet) {
        Raw raw = exec(dir, argv, what, timeoutSeconds);
        if (raw.exit() != 0) {
            String detail = raw.output().isBlank() ? ("exit " + raw.exit()) : raw.output();
            if (!quiet) {
                log.warn("git 命令失败 [{}]：{}", what, detail);
            }
            throw new GitIntakeException(what + " 失败：\n" + detail);
        }
        return new ActionResult(
                top.rongx.agent.domain.run.StageResult.success("git", 0, List.of(), 0),
                raw.output());
    }

    /** 执行结果（退出码 + 输出）；非零退出不抛异常，由调用方解释。 */
    private record Raw(int exit, String output) {
    }

    private Raw exec(Path dir, List<String> argv, String what, long timeoutSeconds) {
        // 目录必须先存在：ProcessBuilder 对无效目录抛 CreateProcess error=267（目录名称无效），
        // 这种 OS 码对用户毫无意义——转成"先接入项目"的可读指引。
        if (dir == null || !Files.isDirectory(dir)) {
            throw new GitIntakeException(
                    what + " 失败：git 仓库目录不存在（" + dir + "）。该项目尚未完成接入，请先在「项目接入」页完成 clone 或本地登记。");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(argv)
                    .directory(dir.toFile())
                    .redirectErrorStream(true);
            // 远端不可达时 git 可能会等待凭据输入：禁止交互，让它尽快失败而不是挂住
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process p = pb.start();
            // 输出必须与等待并行读取：先 readAllBytes 会一直阻塞到进程退出，
            // 超时参数就形同虚设（远端 hang 住时整条运行永远是"进行中"）。
            StringBuilder out = new StringBuilder();
            Thread pump = Thread.ofVirtual().start(() -> {
                try (var in = p.getInputStream()) {
                    out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // 进程被强杀时流会提前关闭，属预期
                }
            });
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(10, TimeUnit.SECONDS);
            }
            pump.join(TimeUnit.SECONDS.toMillis(10));
            String output = out.toString();
            if (!finished) {
                log.warn("git 命令超时 [{}]：{}", what, output);
                throw new GitIntakeException(what + " 超时（" + timeoutSeconds + "s）："
                        + "远端不可达或网络过慢，请检查仓库地址/网络后重试");
            }
            return new Raw(p.exitValue(), output);
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
            // 本地新分支没有 upstream 是常态（fatal: no upstream configured），不该刷 WARN
            ActionResult out = tryRun(repoDir,
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
