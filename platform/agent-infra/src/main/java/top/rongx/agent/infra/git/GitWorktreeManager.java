package top.rongx.agent.infra.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.executor.ProcessActionRunner;

import java.time.Duration;

/**
 * git 仓库托管：bare 对象库 + N 个 worktree（v2 接入设计的底层执行器）。
 *
 * <p>职责：
 * <ul>
 *   <li>建 bare 对象库（{@code workspaces/<id>/git}）；</li>
 *   <li>本地目录收编（remote add agent + push + worktree 登记原目录）；</li>
 *   <li>远程 clone --bare；</li>
 *   <li>worktree 生命周期（add / remove / list / prune）；</li>
 *   <li>LOCAL 来源构建前 fetch（从用户目录拉最新对象）。</li>
 * </ul>
 *
 * <p>所有 git 命令经 {@link ProcessActionRunner}（argv 数组、不经 shell、日志脱敏）。
 * 命令失败抛 {@link GitIntakeException}，原始报错原样带出（向导 UI 展示）。
 */
public class GitWorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(GitWorktreeManager.class);
    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(10);
    private static final String AGENT_REMOTE = "agent";

    private final ProcessActionRunner runner;
    private final Path workspaceRoot;

    public GitWorktreeManager(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.runner = new ProcessActionRunner();
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
        // push 当前 HEAD 到 bare
        String head = revParse(local, "HEAD");
        run(local, List.of("git", "push", "--quiet", AGENT_REMOTE, head), "git push agent");

        // 把用户目录登记为 bare 的 worktree（已是 worktree 则跳过）
        if (!isWorktree(bare, local)) {
            run(bare, List.of("git", "worktree", "add", "--quiet", posix(local), head),
                    "git worktree add（用户目录）");
        }
        return head;
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
        if (path.startsWith(wtRoot)) {
            String name = wtRoot.relativize(path).toString().replace('\\', '/');
            role = name.startsWith("wt-build-") ? "BUILD" : "VERIFY";
        } else {
            role = "USER";
        }
        return new WorktreeInfo(path.toString(), head, role);
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

    /** REMOTE 来源刷新：bare 内 fetch origin。 */
    public void fetchRemote(String projectId) {
        Path bare = bareDir(projectId);
        if (!Files.isDirectory(bare.resolve("objects"))) {
            throw new GitIntakeException("项目仓库不存在：" + bare);
        }
        run(bare, List.of("git", "fetch", "--all", "--quiet"), "git fetch origin");
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

    /** 跑一条 git 命令；失败抛 GitIntakeException（带脱敏后的原始报错）。 */
    private ActionResult run(Path dir, List<String> argv, String what) {
        ActionSpec spec = new ActionSpec(ActionId.BACKEND_BUILD, List.of(argv),
                List.of(), GIT_TIMEOUT, ActionSource.builtin("git:" + what));
        ActionResult result = runner.run(dir, spec);
        if (result.result().status() != StageStatus.SUCCEEDED) {
            String detail = result.logTail() == null || result.logTail().isBlank()
                    ? result.result().errorSummary()
                    : result.logTail();
            log.warn("git 命令失败 [{}]：{}", what, detail);
            throw new GitIntakeException(what + " 失败：\n" + detail);
        }
        return result;
    }

    private String posix(Path path) {
        return path.toString().replace('\\', '/');
    }

    /** worktree 信息（name、HEAD、角色）。 */
    public record WorktreeInfo(String path, String head, String role) {
        /** worktree 短名（目录名）。 */
        public String name() {
            return Path.of(path).getFileName().toString();
        }
    }
}
