package top.rongx.agent.gateway.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.infra.git.GitWorktreeManager;
import top.rongx.agent.infra.git.ProjectIntakeService;
import top.rongx.agent.infra.persistence.CompositeProjectRepository;
import top.rongx.agent.infra.persistence.IntakeProjectStore;

/**
 * 项目接入与 git 管理接口（v2）。
 *
 * <p>接入：本地收编 / 远程 clone（异步 + 状态轮询）+ 配方自检报告。
 * git：状态/diff/同步（只读对比 + fetch；平台绝不 push 到用户目录或远端）。
 * worktree：列表与清理。项目列表 = yml 预置 ∪ SQLite 接入项。
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectIntakeController {

    private final ProjectIntakeService intake;
    private final GitWorktreeManager git;
    private final ProjectRepository projects;
    private final CompositeProjectRepository composite;
    private final top.rongx.agent.gateway.auth.CurrentUser currentUser;

    public ProjectIntakeController(ProjectIntakeService intake,
                                   GitWorktreeManager git,
                                   ProjectRepository projects,
                                   CompositeProjectRepository composite,
                                   top.rongx.agent.gateway.auth.CurrentUser currentUser,
                                   IntakeProjectStore store) {
        this.intake = intake;
        this.git = git;
        this.projects = projects;
        this.composite = composite;
        this.currentUser = currentUser;
        this.store = store;
    }

    private final IntakeProjectStore store;

    // ---------------------------------------------------------------- 接入

    /** 表单级校验（前端失焦调用）。{ok: true} 或 {ok: false, error}。 */
    @PostMapping("/intake/validate")
    public Map<String, Object> validate(@RequestBody ProjectIntakeService.IntakeRequest req) {
        String err = intake.validate(req);
        return err == null ? Map.of("ok", true) : Map.of("ok", false, "error", err);
    }

    /** 本地目录登记（DIRECT 默认：仓库留在用户目录，不复制）。 */
    @PostMapping("/intake/local")
    public ProjectIntakeService.IntakeState intakeLocal(@RequestBody Map<String, Object> body) {
        currentUser.requireUserId();
        ProjectIntakeService.IntakeRequest req = new ProjectIntakeService.IntakeRequest(
                str(body, "projectId"),
                str(body, "localPath"),
                null,
                str(body, "type"),
                Boolean.TRUE.equals(body.get("gitInitIfMissing")),
                str(body, "storageMode"),
                null);
        return intake.submit(req);
    }

    /** 远程 clone（DIRECT：clone 到用户选择的目录；MANAGED：平台 bare）。 */
    @PostMapping("/intake/remote")
    public ProjectIntakeService.IntakeState intakeRemote(@RequestBody Map<String, Object> body) {
        currentUser.requireUserId();
        ProjectIntakeService.IntakeRequest req = new ProjectIntakeService.IntakeRequest(
                str(body, "projectId"),
                null,
                str(body, "repoUrl"),
                str(body, "type"),
                false,
                str(body, "storageMode"),
                str(body, "cloneTargetDir"));
        return intake.submit(req);
    }

    /** 接入进度。 */
    @GetMapping("/intake/{projectId}/status")
    public ProjectIntakeService.IntakeState intakeStatus(@PathVariable String projectId) {
        return intake.status(projectId);
    }

    // ---------------------------------------------------------------- 环境

    /**
     * 项目已配置的环境（对话页环境下拉用）。
     *
     * <p>只返回项目真实存在的环境，避免前端选到未配置的 dev 后被服务端拒绝。
     */
    @GetMapping("/{id}/envs")
    public List<Map<String, Object>> envs(@PathVariable String id) {
        return projects.findEnvs(id).stream()
                .map(e -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("env", e.env());
                    row.put("appUrl", e.appUrl() == null ? "" : e.appUrl());
                    row.put("composeDir", e.composeDir() == null ? "" : e.composeDir());
                    return row;
                })
                .toList();
    }

    // ---------------------------------------------------------------- git 状态 / 对比 / 同步

    /** git 仓库上下文：DIRECT = 用户目录；MANAGED = 平台 bare。 */
    private record RepoContext(Path dir, boolean direct, boolean initialized) {
    }

    private RepoContext repoContext(String id) {
        var intake = store.find(id);
        String mode = intake.map(IntakeProjectStore.IntakeProject::mode).orElse("MANAGED");
        String localPath = intake.map(IntakeProjectStore.IntakeProject::localPath).orElse(null);
        if ("DIRECT".equals(mode)) {
            Path local = localPath == null || localPath.isBlank() ? null : Path.of(localPath);
            boolean ok = local != null && Files.isDirectory(local.resolve(".git"));
            return new RepoContext(local, true, ok);
        }
        Path bare = git.bareDir(id);
        return new RepoContext(bare, false, git.isInitialized(id));
    }

    /**
     * git 状态：HEAD、分支、远程（origin 等）、与 upstream 的领先/落后、未提交改动、worktree。
     * 未接入项目返回 initialized=false（不是错误）。
     */
    @GetMapping("/{id}/git/status")
    public Map<String, Object> gitStatus(@PathVariable String id) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        RepoContext ctx = repoContext(id);
        out.put("initialized", ctx.initialized());
        out.put("storageMode", ctx.direct() ? "DIRECT" : "MANAGED");

        if (!ctx.initialized()) {
            out.put("head", "");
            out.put("branch", "");
            out.put("branches", List.of());
            out.put("remotes", List.of());
            out.put("upstream", null);
            out.put("ahead", 0L);
            out.put("behind", 0L);
            out.put("uncommitted", List.of());
            out.put("worktrees", List.of());
            return out;
        }

        Path dir = ctx.dir();
        out.put("head", git.revParse(dir, "HEAD"));
        String branch = git.currentBranch(dir);
        out.put("branch", branch);
        // DIRECT 的用户目录同样要给出分支列表（此前只在 MANAGED 下返回，分支选择器因此为空）
        try {
            out.put("branches", ctx.direct() ? git.localBranches(dir) : git.branches(id));
        } catch (Exception e) {
            out.put("branches", List.of());
        }
        // 远程：名称 + URL（修复：之前完全不返回，前端"检测不到远程"）
        List<Map<String, String>> remotes = new ArrayList<>();
        for (var r : git.remotes(dir)) {
            Map<String, String> row = new java.util.LinkedHashMap<>();
            row.put("name", r.name());
            row.put("url", r.url());
            remotes.add(row);
        }
        out.put("remotes", remotes);
        // upstream（如 origin/main）与领先/落后
        String upstream = "HEAD".equals(branch) ? null : git.upstreamOf(dir, branch);
        out.put("upstream", upstream);
        if (upstream != null) {
            long[] ab = git.aheadBehind(dir, upstream);
            out.put("ahead", ab[0]);
            out.put("behind", ab[1]);
        } else {
            out.put("ahead", 0L);
            out.put("behind", 0L);
        }
        // 未提交改动：bare 仓库（MANAGED）没有工作区，manager 内部会降级为空列表
        try {
            out.put("uncommitted", git.statusPorcelain(dir));
        } catch (Exception e) {
            out.put("uncommitted", List.of());
        }
        out.put("worktrees", ctx.direct() ? git.listWorktreesAt(dir) : git.listWorktrees(id));
        // 最近提交（最近 5 条），供项目概览展示
        out.put("recentCommits", git.recentCommits(dir, 5));
        return out;
    }

    /** 提交区间对比：from..to（oneline 列表 + diff stat）。按存储模式选仓库。 */
    @GetMapping("/{id}/git/diff")
    public Map<String, Object> gitDiff(@PathVariable String id,
                                       @org.springframework.web.bind.annotation.RequestParam String from,
                                       @org.springframework.web.bind.annotation.RequestParam String to) {
        RepoContext ctx = repoContext(id);
        if (!ctx.initialized()) {
            return Map.of("commits", List.of(), "stat", List.of());
        }
        return Map.of(
                "commits", git.logRange(ctx.dir(), from, to),
                "stat", git.diffStat(ctx.dir(), from, to));
    }

    /**
     * 同步（fetch）：DIRECT 在用户目录 fetch 远端（拿最新提交与远程分支）；
     * MANAGED 在平台 bare 内 fetch origin。
     */
    @PostMapping("/{id}/git/sync")
    public Map<String, Object> gitSync(@PathVariable String id) {
        currentUser.requireUserId();
        RepoContext ctx = repoContext(id);
        if (!ctx.initialized()) {
            throw new IllegalArgumentException(
                    "该项目尚未完成 git 接入，无可同步内容。请先在「项目接入」页完成接入。");
        }
        if (ctx.direct()) {
            // 用户目录：直接 fetch 该仓库配置的远端（origin 等）
            git.fetchRemoteIn(ctx.dir());
        } else {
            git.fetchRemote(id);
        }
        return Map.of("ok", true, "head", git.revParse(ctx.dir(), "HEAD"));
    }

    /**
     * 切换分支：body {@code {branch, worktree?}}。
     *
     * <p>DIRECT 直接在用户目录切换；MANAGED 的 bare 没有工作区，切换发生在平台 worktree
     * （未指定时优先 wt-verify，其次任一已登记 worktree）。切换前不自动 stash：
     * 有未提交改动时 git 会自己拒绝，原始报错透传给前端。
     */
    @PostMapping("/{id}/git/checkout")
    public Map<String, Object> checkout(@PathVariable String id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        currentUser.requireUserId();
        Map<String, Object> payload = body == null ? Map.of() : body;
        String branch = str(payload, "branch");
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("branch 不能为空");
        }
        RepoContext ctx = repoContext(id);
        if (!ctx.initialized()) {
            throw new IllegalArgumentException("该项目尚未完成 git 接入，无法切换分支。");
        }
        String worktree = str(payload, "worktree");
        Path target = checkoutTarget(id, ctx, worktree);
        git.checkout(target, branch);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true);
        out.put("branch", git.currentBranch(target));
        out.put("worktree", worktree == null ? "" : worktree);
        out.put("head", git.revParse(target, "HEAD"));
        return out;
    }

    private Path checkoutTarget(String id, RepoContext ctx, String worktree) {
        if (worktree != null && !worktree.isBlank() && !"main".equals(worktree)) {
            if (worktree.contains("..") || worktree.contains("/") || worktree.contains("\\")) {
                throw new IllegalArgumentException("工作树名称不合法：" + worktree);
            }
            Path base = ctx.direct()
                    ? ctx.dir().resolve(".agent").resolve("worktrees")
                    : git.worktreesDir(id);
            Path target = base.resolve(worktree);
            if (!Files.isDirectory(target)) {
                throw new IllegalArgumentException("工作树不存在：" + worktree);
            }
            return target;
        }
        if (ctx.direct()) {
            return ctx.dir();
        }
        Path verify = git.verifyWorktree(id);
        if (Files.isDirectory(verify)) {
            return verify;
        }
        List<GitWorktreeManager.WorktreeInfo> list = git.listWorktrees(id);
        if (list.isEmpty()) {
            throw new IllegalArgumentException("该项目还没有可用的工作树，无法切换分支。");
        }
        return Path.of(list.get(0).path());
    }

    /** 远程分支列表（UI 选择对比目标用）。 */
    @GetMapping("/{id}/git/remotes")
    public List<Map<String, Object>> remotes(@PathVariable String id) {
        RepoContext ctx = repoContext(id);
        if (!ctx.initialized()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : git.remotes(ctx.dir())) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", r.name());
            row.put("url", r.url());
            row.put("branches", git.remoteBranches(ctx.dir(), r.name()));
            out.add(row);
        }
        return out;
    }

    /**
     * 提交历史（分页）：{@code ?limit=20&skip=0}。
     * 供"最近提交"面板展示更长历史（limit 上限 200）。
     */
    @GetMapping("/{id}/git/commits")
    public Map<String, Object> commits(@PathVariable String id,
                                       @RequestParam(defaultValue = "20") int limit,
                                       @RequestParam(defaultValue = "0") int skip) {
        RepoContext ctx = repoContext(id);
        if (!ctx.initialized()) {
            return Map.of("commits", List.of(), "hasMore", false, "skip", 0);
        }
        int safeLimit = Math.min(Math.max(1, limit), 200);
        List<GitWorktreeManager.CommitInfo> commits =
                git.commits(ctx.dir(), Math.max(0, skip), safeLimit + 1);
        boolean hasMore = commits.size() > safeLimit;
        List<GitWorktreeManager.CommitInfo> page =
                hasMore ? commits.subList(0, safeLimit) : commits;
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("commits", page);
        out.put("hasMore", hasMore);
        out.put("skip", Math.max(0, skip));
        return out;
    }

    // ---------------------------------------------------------------- 列表 / 下线

    /** 项目列表（预置 ∪ 接入），带来源与可下线性。 */
    @GetMapping
    public List<Map<String, Object>> listProjects() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var p : projects.findAll()) {
            boolean isIntake = composite.isIntake(p.id());
            var intakeOpt = store.find(p.id());
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", p.id());
            row.put("name", p.name());
            row.put("type", p.type());
            row.put("repoUrl", p.repoUrl());
            row.put("source", intakeOpt.map(IntakeProjectStore.IntakeProject::source).orElse("PRESET"));
            row.put("localPath", intakeOpt.map(IntakeProjectStore.IntakeProject::localPath).orElse(null));
            row.put("intake", isIntake);
            out.add(row);
        }
        return out;
    }

    /** 下线接入项目（预置项目不可下线）。默认保留 bare 仓库与本地目录。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> decommission(@PathVariable String id) {
        currentUser.requireUserId();
        if (!composite.isIntake(id)) {
            throw new IllegalArgumentException("项目 " + id + " 不是接入项目（预置配置项目请在 yml 中移除）");
        }
        boolean removed = store.delete(id);
        return Map.of("ok", removed, "note", "bare 仓库与本地目录未删除；如需彻底清理请手动处理工作区");
    }

    // ---------------------------------------------------------------- worktree

    @GetMapping("/{id}/worktrees")
    public List<GitWorktreeManager.WorktreeInfo> worktrees(@PathVariable String id) {
        RepoContext ctx = repoContext(id);
        if (!ctx.initialized()) {
            return List.of();
        }
        return ctx.direct() ? git.listWorktreesAt(ctx.dir()) : git.listWorktrees(id);
    }

    @DeleteMapping("/{id}/worktrees/{name}")
    public Map<String, Object> removeWorktree(@PathVariable String id, @PathVariable String name) {
        currentUser.requireUserId();
        if (!name.startsWith("wt-build-")) {
            throw new IllegalArgumentException("仅允许清理构建 worktree（wt-build-*）");
        }
        RepoContext ctx = repoContext(id);
        if (ctx.direct()) {
            git.removeWorktreeAt(ctx.dir(), name);
        } else {
            git.removeWorktree(id, name);
        }
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- helpers


    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
