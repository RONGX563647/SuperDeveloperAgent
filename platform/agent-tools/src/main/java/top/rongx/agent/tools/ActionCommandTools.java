package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 动作写工具（动作层入口，L2：仅测试环境）。
 *
 * <p>层级契约：{@code run_action} 是「动作层」入口——执行单个命名动作（backend_build 等）。
 * 它也是 {@code ci_run} 流水线每个 stage 的底层执行单元（共用同一套 ActionRunner 与门禁）。
 * 优先用 ci_run 跑整条流水线；仅当需要单独验证/调试某个动作时才用本工具。
 *
 * <p>L3 动作（stack_deploy/stack_rollback）在这里被拒绝——它们必须走
 * 「登记变更 → 人确认 → 带令牌 apply」的 HTTP 路径。
 */
@Component
public class ActionCommandTools {

    private final ActionService actions;

    public ActionCommandTools(ActionService actions) {
        this.actions = actions;
    }

    @Tool(name = "run_action",
            description = """
                    执行单个动作（仅限测试环境的构建类动作）。写操作，动作层入口。
                    与 ci_run 的关系：ci_run 跑整条流水线；本工具只跑一个命名动作，用于单步调试。
                    执行前建议先调用 plan_action 并把命令原文告知用户。
                    stack_deploy / stack_rollback 属 L3，不能由此工具执行，需走变更确认流程。
                    params 为可选覆盖（如 backend_build 的 profile）；键名与取值范围见 plan_action 输出。""",
            readOnly = false, concurrencySafe = false)
    public String runAction(
            @ToolParam(name = "action", description = "动作名，见 list_actions 的返回") String action,
            @ToolParam(name = "params",
                    description = "可选参数覆盖，Map<参数名,值>；键与取值范围见 plan_action") Map<String, String> params,
            ProjectContext ctx) {
        var id = ActionId.fromWire(action);
        if (id.isEmpty()) {
            return "err: 未知动作 " + action + "；可用：" + wireNames();
        }
        ActionResult result;
        try {
            result = actions.run(ctx, id.get(), params == null ? Map.of() : params);
        } catch (top.rongx.agent.domain.policy.ConfirmationRequiredException e) {
            // 破坏性动作必须先人工确认：把 changeId 明确回给模型，禁止它重试
            return "err: 该动作需要人工确认，未执行。\n" + e.getMessage();
        }
        // 计划已在 plan_action 输出过；这里不再重复命令原文，减少 token
        return format(result);
    }

    private String format(ActionResult result) {
        var stage = result.result();
        String artifacts = stage.artifacts().isEmpty()
                ? "（无）"
                : stage.artifacts().stream().map(this::formatArtifact)
                .collect(Collectors.joining("\n  ", "\n  ", ""));
        String failedPart = "";
        if (stage.status() == top.rongx.agent.domain.run.StageStatus.FAILED) {
            failedPart = "errorCode: %s\nsummary: %s\n".formatted(
                    stage.errorCode() == null ? "" : stage.errorCode(),
                    stage.errorSummary() == null ? "" : stage.errorSummary());
        }
        return """
                action: %s
                status: %s
                exit: %d
                cost: %dms
                artifacts: %s
                %slog tail:
                %s
                """.formatted(
                stage.stageId(),
                stage.status(),
                stage.exitCode(),
                stage.durationMs(),
                artifacts,
                failedPart,
                result.logTail() == null || result.logTail().isBlank() ? "（无输出）" : result.logTail());
    }

    private String formatArtifact(ArtifactRef artifact) {
        String digest = artifact.digest() == null ? "?" : artifact.digest().substring(0, 12) + "…";
        long size = artifact.sizeBytes();
        String human = size < 1024 ? size + " B" : String.format("%.1f KB", size / 1024.0);
        return "%s  sha256:%s  %s".formatted(artifact.ref(), digest, human);
    }

    private String wireNames() {
        return Arrays.stream(ActionId.values()).map(ActionId::wireName).collect(Collectors.joining(", "));
    }
}
