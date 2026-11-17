package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.project.ProjectContext;

/** 动作只读工具：列目录、看计划（与 HTTP 接口共用 {@link ActionService}）。 */
@Component
public class ActionQueryTools {

    private final ActionService actions;

    public ActionQueryTools(ActionService actions) {
        this.actions = actions;
    }

    @Tool(name = "list_actions", description = "List actions available in this project.",
            readOnly = true, concurrencySafe = true)
    public String listActions(ProjectContext ctx) {
        List<ActionSpec> specs = actions.list(ctx);
        if (specs.isEmpty()) {
            return "当前项目没有可用动作。请在仓库提供 ci/actions.yml 或 Makefile。"
                    + "\nevidence: workspace:" + actions.workspace(ctx);
        }
        String body = specs.stream()
                .map(s -> "- %s  [%s]  ← %s%s".formatted(
                        s.action().wireName(),
                        s.action().level(),
                        s.source().ref(),
                        s.source().humanAuthored() ? "" : "（平台默认）"))
                .collect(Collectors.joining("\n"));
        return "actions（项目 %s）:\n%s\nevidence: workspace:%s"
                .formatted(ctx.projectId(), body, actions.workspace(ctx));
    }

    @Tool(name = "plan_action", description = "Preview an action without running it.",
            readOnly = true, concurrencySafe = true)
    public String planAction(
            @ToolParam(name = "action",
                    description = "动作名：backend_build / frontend_build / image_package / stack_deploy / stack_rollback")
            String action,
            ProjectContext ctx) {
        var id = ActionId.fromWire(action);
        if (id.isEmpty()) {
            return "err: 未知动作 " + action + "；可用：" + wireNames();
        }
        ActionSpec s = actions.plan(ctx, id.get());
        String commands = s.steps().stream()
                .map(argv -> "  " + String.join(" ", argv))
                .collect(Collectors.joining("\n"));
        String paramPart;
        if (s.params().isEmpty()) {
            paramPart = "（无）";
        } else {
            paramPart = s.params().entrySet().stream()
                    .map(e -> {
                        String allowedPart = s.allowed().getOrDefault(e.getKey(), List.of()).isEmpty()
                                ? "" : " (allowed: " + s.allowed().get(e.getKey()) + ")";
                        // 声明了参数但 steps 未引用：提示配方作者（避免覆盖永远不生效）
                        boolean referenced = s.steps().stream()
                                .flatMap(List::stream)
                                .anyMatch(t -> t.contains("${param." + e.getKey() + "}"));
                        String unusedWarn = referenced ? "" : "  ← 未被命令引用，覆盖不生效";
                        return "  - %s = %s%s%s".formatted(e.getKey(), e.getValue(), allowedPart, unusedWarn);
                    })
                    .collect(Collectors.joining("\n"));
        }
        return """
                action: %s
                level: %s
                source: %s（%s）
                timeout: %dm
                produce: %s
                params:
                %s
                commands:
                %s
                evidence: %s
                """.formatted(
                s.action().wireName(),
                s.action().level(),
                s.source().ref(),
                s.source().humanAuthored() ? "来自项目仓库，人写的" : "平台内置默认",
                s.timeout().toMinutes(),
                s.produce().isEmpty() ? "（未声明）" : String.join(", ", s.produce()),
                paramPart,
                commands,
                s.source().ref());
    }

    private String wireNames() {
        return Arrays.stream(ActionId.values()).map(ActionId::wireName).collect(Collectors.joining(", "));
    }
}
