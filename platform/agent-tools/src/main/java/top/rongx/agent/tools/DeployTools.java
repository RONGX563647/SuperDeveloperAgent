package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.deploy.DeployRecipe;
import top.rongx.agent.domain.deploy.RecipeRenderer;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.run.StageStatus;

/**
 * 部署 Agent 的工具：**专用于"把某个环境跑通并固化"**。
 *
 * <p>与通用助手工具的区别：
 * <ul>
 *   <li>它面向"环境"而不是"项目"——每个环境有自己的可复现脚本与证据；</li>
 *   <li>它能<b>固化</b>（{@code deploy_save_recipe}）：把跑通的动作序列变成本环境的脚本，
 *       下次重放不再需要探索；</li>
 *   <li>它<b>不能</b>写命令、不能改凭证；生产环境只能重放，不能自主探索。</li>
 * </ul>
 *
 * <p>所有写操作都是 L2/L3 受控的：部署类动作在非测试环境会被流水线门禁拦住，
 * 服务器的门槛由 {@code DeployController} 的"测试证据 + 一次性确认令牌"把守。
 */
@Component
public class DeployTools {

    private final RecipeStore recipes;
    private final PipelineEngine engine;

    public DeployTools(RecipeStore recipes, PipelineEngine engine) {
        this.recipes = recipes;
        this.engine = engine;
    }

    @Tool(name = "deploy_env_status",
            description = """
                    查看某个环境当前的可复现脚本与证据状态（只读）。
                    返回：脚本版本、证据运行 id 与状态、能否上服务器（prod）。
                    探索前先调它，能避免重复劳动：已经有 vN 且证据成功，直接 replay 即可。""",
            readOnly = true, concurrencySafe = true)
    public String envStatus(@ToolParam(name = "env", description = "环境：dev / test / prod") String env,
                            ProjectContext ctx) {
        String target = normalize(env);
        Optional<DeployRecipe> current = recipes.current(ctx.projectId(), target);
        StringBuilder sb = new StringBuilder();
        sb.append("project: ").append(ctx.projectId()).append('\n');
        sb.append("env: ").append(target).append('\n');
        if (current.isEmpty()) {
            sb.append("recipe: 无（该环境还没有跑通过，或尚未固化）\n");
        } else {
            DeployRecipe r = current.get();
            StageStatus evidence = engine.status(r.evidenceRunId());
            sb.append("recipe: v").append(r.version()).append('\n')
                    .append("evidenceRunId: ").append(r.evidenceRunId()).append('\n')
                    .append("evidenceStatus: ").append(evidence).append('\n')
                    .append("gitSha: ").append(r.gitSha()).append('\n');
        }
        sb.append("versions: ").append(recipes.versions(ctx.projectId(), target).size()).append('\n');
        return sb.toString();
    }

    @Tool(name = "deploy_run",
            description = """
                    触发一次流水线运行（写操作，仅 dev/test）。
                    环境跑通后平台会自动固化为该环境的可复现脚本；失败时用 ci_status / read_run_log 看原因。
                    服务器（prod）不允许用本工具触发：必须先在测试环境跑通并固化，再到部署面板确认发布。""",
            readOnly = false, concurrencySafe = false)
    public String run(@ToolParam(name = "pipeline_id", description = "流水线名（release / verify）")
                      String pipelineId,
                      @ToolParam(name = "git_sha", description = "要构建的提交 sha（7-40 位十六进制）") String sha,
                      @ToolParam(name = "env", description = "环境：dev / test（prod 请走部署面板确认）")
                      String env,
                      ProjectContext ctx) {
        String target = normalize(env);
        if ("prod".equals(target)) {
            return "err: prod 不能用工具触发：先在测试环境跑通并固化，再到部署面板点「确认发布」";
        }
        if (pipelineId == null || pipelineId.isBlank()) {
            return "err: pipeline_id 不能为空";
        }
        if (!engine.pipelines(ctx.projectId()).contains(pipelineId)) {
            return "err: pipeline 不在白名单：" + pipelineId;
        }
        if (sha == null || !sha.matches("[0-9a-f]{7,40}")) {
            return "err: git_sha 需 7-40 位小写十六进制";
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("gitSha", sha);
        params.put("env", target);
        try {
            String runId = engine.trigger(ctx.projectId(), pipelineId, params);
            return "runId: " + runId + "\nenv: " + target + "\nstatus: " + engine.status(runId);
        } catch (IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }
    }

    @Tool(name = "deploy_save_recipe",
            description = """
                    把「已经跑通的动作序列」固化成该环境的可复现脚本（写操作）。
                    只在确实跑通过之后调用；脚本会记录当前证据运行，供后续重放与上服务器判断。
                    actions 只能取：backend_build / backend_test / frontend_build / image_package。
                    需要部署到容器时传 with_deploy=true，平台会补 deploy + health + rollback。""",
            readOnly = false, concurrencySafe = false)
    public String saveRecipe(@ToolParam(name = "env", description = "环境：dev / test") String env,
                             @ToolParam(name = "actions",
                                     description = "动作序列，逗号分隔，例如 backend_build,backend_test,image_package")
                             String actions,
                             @ToolParam(name = "with_deploy", description = "是否包含部署与健康检查（true/false）")
                             String withDeploy,
                             @ToolParam(name = "port", description = "部署端口，默认 8080") String port,
                             ProjectContext ctx) {
        String target = normalize(env);
        if ("prod".equals(target)) {
            return "err: 不要把生产环境当探索目标；测试环境跑通后直接 replay 到生产即可";
        }
        List<RecipeRenderer.Step> steps = new java.util.ArrayList<>();
        if (actions != null && !actions.isBlank()) {
            for (String raw : actions.split(",")) {
                String wire = raw.trim();
                if (wire.isEmpty()) {
                    continue;
                }
                Optional<ActionId> action = ActionId.fromWire(wire);
                if (action.isEmpty()) {
                    return "err: 不支持的动作：" + wire + "（只能引用白名单动作）";
                }
                steps.add(RecipeRenderer.Step.action(wire,
                        "image_package".equals(wire) ? "host" : "container"));
            }
        }
        if (steps.isEmpty()) {
            return "err: actions 不能为空";
        }
        boolean deploy = "true".equalsIgnoreCase(String.valueOf(withDeploy));
        if (deploy) {
            steps.add(RecipeRenderer.Step.deploy());
            steps.add(RecipeRenderer.Step.health());
            steps.add(RecipeRenderer.Step.rollback());
        }
        Integer parsedPort = parsePort(port);
        RecipeRenderer.RenderTarget renderTarget = deploy
                ? RecipeRenderer.RenderTarget.docker(parsedPort == null ? 8080 : parsedPort)
                : null;
        String yaml = RecipeRenderer.render("release", steps, renderTarget);

        String evidence = lastSuccessfulRun(ctx.projectId(), target);
        DeployRecipe saved = recipes.save(new DeployRecipe(ctx.projectId(), target, 0, yaml, null,
                evidence, null, System.currentTimeMillis(), "agent"));
        return "saved: v" + saved.version() + "\nenv: " + target
                + "\nevidence: " + (evidence == null ? "（无成功运行，建议先 deploy_run）" : evidence)
                + "\n---\n" + yaml;
    }

    /** 该环境最近一次成功的运行（作为固化证据）。 */
    private String lastSuccessfulRun(String projectId, String env) {
        return engine.history().stream()
                .filter(h -> h.projectId().equals(projectId) && env.equals(h.env()))
                .filter(h -> h.status() == StageStatus.SUCCEEDED)
                .max(java.util.Comparator.comparingLong(h -> h.createdAt()))
                .map(h -> h.runId())
                .orElse(null);
    }

    private static Integer parsePort(String port) {
        try {
            int value = Integer.parseInt(String.valueOf(port).trim());
            return value > 0 && value < 65536 ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String normalize(String env) {
        String value = env == null || env.isBlank() ? "test" : env.trim().toLowerCase();
        return List.of("dev", "test", "prod").contains(value) ? value : "test";
    }
}
