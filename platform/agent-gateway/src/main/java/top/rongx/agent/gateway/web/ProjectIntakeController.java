package top.rongx.agent.gateway.web;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.infra.git.GitWorktreeManager;
import top.rongx.agent.infra.git.ProjectIntakeService;

/**
 * 项目接入与 git 管理接口（v2）。
 *
 * <p>接入：本地收编 / 远程 clone（异步 + 状态轮询）+ 配方自检报告。
 * git：状态/diff/同步（只读对比 + fetch；平台绝不 push 到用户目录或远端）。
 * worktree：列表与清理。
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectIntakeController {

    private final ProjectIntakeService intake;
    private final GitWorktreeManager git;

    public ProjectIntakeController(ProjectIntakeService intake, GitWorktreeManager git) {
        this.intake = intake;
        this.git = git;
    }

    // ---------------------------------------------------------------- 接入

    /** 表单级校验（前端失焦调用）。{ok: true} 或 {ok: false, error}。 */
    @PostMapping("/intake/validate")
    public Map<String, Object> validate(@RequestBody ProjectIntakeService.IntakeRequest req) {
        String err = intake.validate(req);
        return err == null ? Map.of("ok", true) : Map.of("ok", false, "error", err);
    }

    /** 本地目录收编（异步）。 */
    @PostMapping("/intake/local")
    public ProjectIntakeService.IntakeState intakeLocal(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        requireUser(userId);
        ProjectIntakeService.IntakeRequest req = new ProjectIntakeService.IntakeRequest(
                str(body, "projectId"),
                str(body, "localPath"),
                null,
                str(body, "type"),
                Boolean.TRUE.equals(body.get("gitInitIfMissing")));
        return intake.submit(req);
    }

    /** 远程 clone（异步）。 */
    @PostMapping("/intake/remote")
    public ProjectIntakeService.IntakeState intakeRemote(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        requireUser(userId);
        ProjectIntakeService.IntakeRequest req = new ProjectIntakeService.IntakeRequest(
                str(body, "projectId"),
                null,
                str(body, "repoUrl"),
                str(body, "type"),
                false);
        return intake.submit(req);
    }

    /** 接入进度。 */
    @GetMapping("/intake/{projectId}/status")
    public ProjectIntakeService.IntakeState intakeStatus(@PathVariable String projectId) {
        return intake.status(projectId);
    }

    // ---------------------------------------------------------------- git 状态 / 对比 / 同步

    /** git 状态：HEAD、未提交改动、领先/落后（bare 内对比）。 */
    @GetMapping("/{id}/git/status")
    public Map<String, Object> gitStatus(@PathVariable String id) {
        Path bare = git.bareDir(id);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("head", git.revParse(bare, "HEAD"));
        out.put("branches", git.branches(id));
        // 本地未提交改动：在 wt-verify 上查（只读 worktree，改动通常为空；真实"未提交"在用户目录，
        // 路径由项目存储提供——P1 接入项目落库后补 USER 目录探测，此处先看 verify worktree）
        try {
            out.put("uncommitted", git.statusPorcelain(git.verifyWorktree(id)));
        } catch (Exception e) {
            out.put("uncommitted", List.of());
        }
        out.put("worktrees", git.listWorktrees(id));
        return out;
    }

    /** 提交区间对比：from..to（oneline 列表 + diff stat）。 */
    @GetMapping("/{id}/git/diff")
    public Map<String, Object> gitDiff(@PathVariable String id,
                                       @org.springframework.web.bind.annotation.RequestParam String from,
                                       @org.springframework.web.bind.annotation.RequestParam String to) {
        Path bare = git.bareDir(id);
        return Map.of(
                "commits", git.logRange(bare, from, to),
                "stat", git.diffStat(bare, from, to));
    }

    /** 同步（fetch）。LOCAL 来源推本地目录对象进 bare；REMOTE 在 bare 内 fetch origin。 */
    @PostMapping("/{id}/git/sync")
    public Map<String, Object> gitSync(@PathVariable String id,
                                       @RequestHeader(value = "X-User-Id", required = false) String userId) {
        requireUser(userId);
        // P1 之前：REMOTE 语义（bare 内 fetch）；LOCAL 来源需项目存储的 localPath（落库后补）
        git.fetchRemote(id);
        return Map.of("ok", true, "head", git.revParse(git.bareDir(id), "HEAD"));
    }

    // ---------------------------------------------------------------- worktree

    @GetMapping("/{id}/worktrees")
    public List<GitWorktreeManager.WorktreeInfo> worktrees(@PathVariable String id) {
        return git.listWorktrees(id);
    }

    @DeleteMapping("/{id}/worktrees/{name}")
    public Map<String, Object> removeWorktree(@PathVariable String id, @PathVariable String name,
                                              @RequestHeader(value = "X-User-Id", required = false) String userId) {
        requireUser(userId);
        if (!name.startsWith("wt-build-")) {
            throw new IllegalArgumentException("仅允许清理构建 worktree（wt-build-*）");
        }
        git.removeWorktree(id, name);
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- helpers

    private void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("未认证：缺少 X-User-Id");
        }
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
