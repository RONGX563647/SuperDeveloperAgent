package top.rongx.agent.gateway.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.domain.bugfix.BugFixPolicy;
import top.rongx.agent.domain.bugfix.BugFixPolicyStore;
import top.rongx.agent.domain.bugfix.BugFixRun;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueService;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.gateway.bugfix.BugFixOrchestrator;

/**
 * Bug 自动修复运行接口：任务页时间线读取、手动启动 / 重试 / 取消，以及平台策略配置。
 *
 * <p>启动、重试、取消与策略写入都需要 {@code action:run} 权限（与发布 Agent 一致）；
 * 读取只要求登录，任务页可以直接展示进度与证据。
 */
@RestController
@RequestMapping("/api/v1/bug-fix")
public class BugFixController {

    private final BugFixOrchestrator orchestrator;
    private final BugFixPolicyStore policies;
    private final GitIssueService issues;
    private final CurrentUser currentUser;

    public BugFixController(BugFixOrchestrator orchestrator, BugFixPolicyStore policies,
                            GitIssueService issues, CurrentUser currentUser) {
        this.orchestrator = orchestrator;
        this.policies = policies;
        this.issues = issues;
        this.currentUser = currentUser;
    }

    @GetMapping("/runs")
    public List<BugFixRun> runs(@RequestParam(required = false) String issueId,
                                @RequestParam(defaultValue = "20") int limit) {
        String userId = currentUser.requireUserId();
        // 管理员（"*"）可查看全部运行，普通用户只能看自己的
        return orchestrator.list(currentUser.hasPermission("*") ? null : userId, issueId, limit);
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> run(@PathVariable String id) {
        String userId = currentUser.requireUserId();
        String scope = currentUser.hasPermission("*") ? null : userId;
        BugFixRun run = orchestrator.get(scope, id);
        return Map.of(
                "run", run,
                "progress", run.progress(),
                "resumable", run.resumable(),
                "events", orchestrator.events(scope, id),
                // 逐阶段记录：尝试次数、耗时、每阶段证据（验证失败回到修复后历史仍保留）
                "stages", orchestrator.stageRuns(scope, id));
    }

    /** 手动启动一次自动修复（Bug 任务）。 */
    @PostMapping("/runs")
    public BugFixRun start(@RequestBody Map<String, Object> body) {
        currentUser.checkPermission("action:run");
        String userId = currentUser.requireUserId();
        String issueId = str(body, "issueId");
        if (issueId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 issueId");
        }
        GitIssue issue = issues.get(userId, issueId);
        if (!"BUG".equals(issue.kind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只有 Bug 任务支持自动修复");
        }
        if (!issue.open()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已关闭，无法启动自动修复");
        }
        try {
            return orchestrator.start(issue);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/runs/{id}/retry")
    public BugFixRun retry(@PathVariable String id) {
        currentUser.checkPermission("action:run");
        String userId = currentUser.requireUserId();
        BugFixRun run = orchestrator.get(userId, id);
        // 已关闭的任务不再自动修复：先确认任务仍然打开，再进行状态推进
        GitIssue issue = issues.get(userId, run.issueId());
        if (!issue.open()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "任务已关闭，请先重新打开任务再重试自动修复");
        }
        return orchestrator.resume(userId, id);
    }

    @PostMapping("/runs/{id}/cancel")
    public BugFixRun cancel(@PathVariable String id) {
        currentUser.checkPermission("action:run");
        return orchestrator.cancel(currentUser.requireUserId(), id);
    }

    /** 读取策略；传 projectId 时读取项目级覆盖（未配置回退平台默认值）。 */
    @GetMapping("/policy")
    public BugFixPolicy policy(@RequestParam(required = false) String projectId) {
        currentUser.requireUserId();
        return policies.load(projectId);
    }

    @PutMapping("/policy")
    public BugFixPolicy savePolicy(@RequestBody Map<String, Object> body) {
        currentUser.checkPermission("action:run");
        String projectId = str(body, "projectId");
        BugFixPolicy current = policies.load(projectId);
        BugFixPolicy next = new BugFixPolicy(
                bool(body, "enabled", current.enabled()),
                bool(body, "autoDeploy", current.autoDeploy()),
                bool(body, "autoCreatePr", current.autoCreatePr()),
                body.containsKey("testEnv") ? str(body, "testEnv") : current.testEnv(),
                body.containsKey("bindingId") ? str(body, "bindingId") : current.bindingId(),
                body.containsKey("maxAttempts") ? integer(body, "maxAttempts",
                        current.maxAttempts()) : current.maxAttempts(),
                body.containsKey("baseUrl") ? str(body, "baseUrl") : current.baseUrl(),
                body.containsKey("buildCommand") ? str(body, "buildCommand")
                        : current.buildCommand(),
                body.containsKey("testCommand") ? str(body, "testCommand") : current.testCommand(),
                0);
        try {
            // 保存前拒绝生产/预生产环境，避免把全局策略改成线上
            next.validate();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return policies.save(projectId, next);
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean bool(Map<String, Object> body, String key, boolean fallback) {
        Object value = body == null ? null : body.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    private static int integer(Map<String, Object> body, String key, int fallback) {
        Object value = body == null ? null : body.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
