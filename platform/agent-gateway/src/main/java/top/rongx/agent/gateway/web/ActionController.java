package top.rongx.agent.gateway.web;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.tools.ActionService;

/**
 * 动作 HTTP 接口（不经过模型也能用，便于运维与可用性测试）。
 *
 * <p>三条路径：看动作 → 执行 L2 → 或登记 L3 变更（再由 {@code /changes/{id}/confirm} + {@code /apply} 闭环）。
 */
@RestController
@RequestMapping("/api/v1/actions")
public class ActionController {

    private final ActionService actions;
    private final ProjectContextResolver contextResolver;

    public ActionController(ActionService actions, ProjectContextResolver contextResolver) {
        this.actions = actions;
        this.contextResolver = contextResolver;
    }

    /** 列出当前项目可用动作。 */
    @GetMapping
    public List<ActionView> list(@RequestParam String projectId,
                                 @RequestParam(defaultValue = "test") String env,
                                 @RequestHeader(value = "X-User-Id", required = false) String userId) {
        ProjectContext ctx = resolve(projectId, env, userId);
        return actions.list(ctx).stream().map(ActionView::of).toList();
    }

    /** 看某个动作的计划（命令原文、来源、超时、产物）。 */
    @GetMapping("/{action}/plan")
    public ActionView plan(@PathVariable String action,
                           @RequestParam String projectId,
                           @RequestParam(defaultValue = "test") String env,
                           @RequestHeader(value = "X-User-Id", required = false) String userId) {
        ProjectContext ctx = resolve(projectId, env, userId);
        return ActionView.of(actions.plan(ctx, requireAction(action)));
    }

    /** 执行 L2 动作（构建类）。生产环境会被拒绝。 */
    @PostMapping("/{action}/run")
    public RunView run(@PathVariable String action,
                       @RequestParam String projectId,
                       @RequestParam(defaultValue = "test") String env,
                       @RequestHeader(value = "X-User-Id", required = false) String userId) {
        ProjectContext ctx = resolve(projectId, env, userId);
        ActionResult result = actions.run(ctx, requireAction(action));
        return RunView.of(result);
    }

    /** 登记 L3 变更，等待人工确认。 */
    @PostMapping("/{action}/request")
    public ChangeView request(@PathVariable String action,
                              @RequestParam String projectId,
                              @RequestParam(defaultValue = "test") String env,
                              @RequestHeader(value = "X-User-Id", required = false) String userId) {
        ProjectContext ctx = resolve(projectId, env, userId);
        ChangePlan plan = actions.requestApproval(ctx, requireAction(action));
        return new ChangeView(plan.changeId(), plan.projectId(), plan.env(), plan.kind(),
                plan.summary(), plan.planHash(), plan.highRisk());
    }

    // ---------------------------------------------------------------- helpers

    private ProjectContext resolve(String projectId, String env, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("未认证：缺少 X-User-Id");
        }
        return contextResolver.resolve(projectId, env, userId);
    }

    private ActionId requireAction(String raw) {
        return ActionId.fromWire(raw)
                .orElseThrow(() -> new IllegalArgumentException("未知动作: " + raw));
    }

    /** 动作视图（不含内部结构，只给前端/调用方需要的）。 */
    public record ActionView(String action, String level, String source, boolean humanAuthored,
                             List<String> commands, List<String> produce, long timeoutMinutes) {
        static ActionView of(ActionSpec spec) {
            return new ActionView(
                    spec.action().wireName(),
                    spec.action().level().name(),
                    spec.source().ref(),
                    spec.source().humanAuthored(),
                    spec.steps().stream().map(argv -> String.join(" ", argv)).toList(),
                    spec.produce(),
                    spec.timeout().toMinutes());
        }
    }

    /** 执行视图。 */
    public record RunView(String action, String status, int exitCode, String errorCode,
                          String summary, long durationMs, List<ArtifactView> artifacts, String logTail) {
        static RunView of(ActionResult r) {
            var stage = r.result();
            return new RunView(stage.stageId(), stage.status().name(), stage.exitCode(),
                    stage.errorCode(), stage.errorSummary(), stage.durationMs(),
                    stage.artifacts().stream()
                            .map(a -> new ArtifactView(a.kind(), a.ref(), a.digest(), a.sizeBytes()))
                            .toList(),
                    r.logTail());
        }
    }

    /** 产物视图。 */
    public record ArtifactView(String kind, String ref, String digest, long sizeBytes) {
    }

    /** 变更登记视图（供确认卡片使用）。 */
    public record ChangeView(String changeId, String projectId, String env, String kind,
                             String summary, String planHash, boolean highRisk) {
    }
}
