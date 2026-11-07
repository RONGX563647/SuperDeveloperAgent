package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 动作写工具（L2：仅测试环境）。
 *
 * <p>三道门禁在工具层强制：① L3 动作拒绝；② 生产环境拒绝；③ 工作区不存在拒绝。
 */
@Component
public class ActionCommandTools {

    private final ActionResolver resolver;
    private final ActionRunner runner;
    private final String workspaceRoot;

    public ActionCommandTools(ActionResolver resolver,
                              ActionRunner runner,
                              @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.resolver = resolver;
        this.runner = runner;
        this.workspaceRoot = workspaceRoot;
    }

    @Tool(name = "run_action",
            description = """
                    执行一个动作（仅限测试环境的构建类动作）。写操作。
                    执行前建议先调用 plan_action 并把命令原文告知用户。
                    stack_deploy / stack_rollback 属 L3，不能由此工具执行，需走变更确认流程。""",
            readOnly = false, concurrencySafe = false)
    public String runAction(
            @ToolParam(name = "action", description = "动作名，见 list_actions 的返回") String action,
            ProjectContext ctx) {
        Optional<ActionId> id = ActionId.fromWire(action);
        if (id.isEmpty()) {
            return "err: 未知动作 " + action + "；可用：" + wireNames();
        }
        ActionId target = id.get();
        if (!target.autoExecutable()) {
            return "err: " + target.wireName() + " 属 L3（影响生产），不能直接执行。"
                    + "请先调用 request_confirmation 走人工确认流程。";
        }
        if (ctx.isProduction()) {
            return "err: 生产环境不允许直接执行动作，请先在测试环境验证。";
        }
        Path root = projectRoot(ctx);
        if (!Files.isDirectory(root)) {
            return "err: 项目工作区不存在：" + root + "（请先接入项目或克隆仓库）";
        }
        Optional<ActionSpec> spec = resolver.resolve(root, target);
        if (spec.isEmpty()) {
            return "err: 动作 " + action + " 没有配方。";
        }

        ActionResult result = runner.run(root, spec.get());
        return format(result, spec.get(), root);
    }

    private String format(ActionResult result, ActionSpec spec, Path root) {
        var stage = result.result();
        String artifacts = stage.artifacts().isEmpty()
                ? "（无）"
                : stage.artifacts().stream().map(this::formatArtifact)
                .collect(Collectors.joining("\n  ", "\n  ", ""));
        return """
                action: %s
                status: %s
                exit: %d
                cost: %dms
                source: %s
                command: %s
                artifacts: %s
                log tail:
                %s
                evidence: workspace:%s
                """.formatted(
                spec.action().wireName(),
                stage.status(),
                stage.exitCode(),
                stage.durationMs(),
                spec.source().ref(),
                spec.steps().stream().map(a -> String.join(" ", a)).collect(Collectors.joining(" && ")),
                artifacts,
                result.logTail() == null || result.logTail().isBlank() ? "（无输出）" : result.logTail(),
                root);
    }

    private String formatArtifact(ArtifactRef artifact) {
        String digest = artifact.digest() == null ? "?" : artifact.digest().substring(0, 12) + "…";
        long size = artifact.sizeBytes();
        String human = size < 1024 ? size + " B" : String.format("%.1f KB", size / 1024.0);
        return "%s  sha256:%s  %s".formatted(artifact.ref(), digest, human);
    }

    private Path projectRoot(ProjectContext ctx) {
        return Path.of(workspaceRoot).resolve(ctx.projectId()).toAbsolutePath().normalize();
    }

    private String wireNames() {
        return Arrays.stream(ActionId.values())
                .map(ActionId::wireName)
                .collect(Collectors.joining(", "));
    }
}
