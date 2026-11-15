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
import top.rongx.agent.domain.env.EnvBinding;
import top.rongx.agent.domain.env.EnvBindingPort;
import top.rongx.agent.domain.env.EnvNames;
import top.rongx.agent.domain.port.PipelineDefStore;
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
    private final EnvBindingPort bindings;
    private final PipelineDefStore defStore;

    public DeployTools(RecipeStore recipes, PipelineEngine engine, EnvBindingPort bindings,
                       PipelineDefStore defStore) {
        this.recipes = recipes;
        this.engine = engine;
        this.bindings = bindings;
        this.defStore = defStore;
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

    /**
     * 平台侧流水线的部署段配置：端口 / 容器端口 / 健康检查。
     *
     * <p>这些值原先只能手改 {@code <workspace>/<project>/ci/pipeline.yml}，Agent 反而要用户
     * 代为修改（"端口被占用"这类最常见的前置失败因此卡住自动化）。这里把改动收进受控工具：
     * 只改 deploy 段与 health stage 的 url，动作与阶段顺序保持不变。
     */
    @Tool(name = "ci_configure_deploy",
            description = """
                    修改平台侧流水线定义的部署配置（写操作，仅 dev/test）：
                    target（docker/host/server）、port、container_port、health_path、health_url。
                    适用场景：部署端口被占用（例如 dev 实例占了 8090）需要换端口；
                    健康检查路径与项目实际端点不一致（Spring Boot 通常是 /actuator/health）。
                    修改后会重建 health stage 的 url（未显式给 health_url 时按 port + health_path 生成），
                    并让该环境旧的固化脚本失效，避免旧脚本覆盖新配置。
                    默认值：container_port 跟随 port；health_path 不传则保留原值。
                    先调用 ci_definition 查看当前定义；若仓库根也有 ci/pipeline.yml，它优先于本定义。
                    生产环境拒绝执行。""",
            readOnly = false, concurrencySafe = false)
    public String configureDeploy(@ToolParam(name = "target", required = false,
                                     description = "部署目标：docker / host / server") String target,
                                  @ToolParam(name = "port", required = false,
                                          description = "对外端口（1-65535），例如 8180") Integer port,
                                  @ToolParam(name = "container_port", required = false,
                                          description = "容器内端口；缺省跟随 port") Integer containerPort,
                                  @ToolParam(name = "health_path", required = false,
                                          description = "健康检查路径，例如 /actuator/health") String healthPath,
                                  @ToolParam(name = "health_url", required = false,
                                          description = "完整健康检查 URL；缺省按 http://127.0.0.1:<port><health_path> 生成")
                                  String healthUrl,
                                  ProjectContext ctx) {
        if (ctx.isProduction()) {
            return "err: 生产环境不允许修改流水线配置；请先在 dev/test 验证";
        }
        String yaml = defStore.read(ctx.projectId()).orElse("");
        if (yaml.isBlank()) {
            return "err: 平台侧还没有 ci/pipeline.yml；请先生成流水线定义再改部署配置";
        }
        String wireTarget = target == null || target.isBlank() ? null : target.trim().toLowerCase();
        if (wireTarget != null && !List.of("docker", "host", "server").contains(wireTarget)) {
            return "err: target 只能是 docker / host / server，实际为 " + target;
        }
        if (port != null && (port <= 0 || port > 65535)) {
            return "err: port 必须是 1-65535，实际为 " + port;
        }
        if (containerPort != null && (containerPort <= 0 || containerPort > 65535)) {
            return "err: container_port 必须是 1-65535，实际为 " + containerPort;
        }
        String path = healthPath == null ? null : healthPath.trim();
        if (path != null && !path.matches("/[A-Za-z0-9/._-]*")) {
            return "err: health_path 必须以 / 开头且只含字母、数字、/ . _ -";
        }
        String url = healthUrl == null ? null : healthUrl.trim();
        if (url != null && !url.matches("https?://[^\\s\"']+")) {
            return "err: health_url 必须是以 http:// 或 https:// 开头的完整 URL";
        }
        if (wireTarget == null && port == null && containerPort == null
                && path == null && url == null) {
            return "err: 至少提供一个要修改的字段（target / port / container_port / health_path / health_url）";
        }
        String updated;
        try {
            updated = applyDeployConfig(yaml, wireTarget, port, containerPort, path, url);
        } catch (IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }
        if (!defStore.write(ctx.projectId(), updated, null)) {
            return "err: 流水线定义写入失败，请检查工作区目录权限：" + defStore.rootOf(ctx.projectId());
        }
        for (String env : List.of("dev", "test", "prod")) {
            recipes.invalidate(ctx.projectId(), env);
        }
        Map<String, Object> deploy = deployOf(updated);
        return """
                saved: %s/ci/pipeline.yml
                deploy: %s
                next: 重新 ci_dry_run 确认定义已生效（旧的固化脚本已失效）
                evidence: project:%s"""
                .formatted(defStore.rootOf(ctx.projectId()), deploy, ctx.projectId());
    }

    /** 读-改-写 deploy 段与 health stage 的 url；动作与阶段顺序不变。 */
    static String applyDeployConfig(String yaml, String target, Integer port,
                                    Integer containerPort, String healthPath, String healthUrl) {
        Object loaded;
        try {
            loaded = new org.yaml.snakeyaml.Yaml().load(yaml);
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            // ParserException / ScannerException 是 RuntimeException 但不是 IllegalArgumentException：
            // 不包装就会绕过工具层 "err: ..." 契约，把原始堆栈抛给模型，且可能继续写坏定义。
            throw new IllegalArgumentException(
                    "现有流水线定义不是合法 YAML：" + e.getMessage(), e);
        }
        if (!(loaded instanceof Map<?, ?> rootRaw)) {
            throw new IllegalArgumentException("现有流水线定义不是合法 YAML 映射");
        }
        Map<String, Object> root = copyMap(rootRaw);
        Map<String, Object> deploy = root.get("deploy") instanceof Map<?, ?> deployRaw
                ? copyMap(deployRaw) : new LinkedHashMap<>();
        if (target != null) {
            deploy.put("target", target);
        }
        if (port != null) {
            deploy.put("port", port);
            deploy.put("containerPort", containerPort == null ? port : containerPort);
        } else if (containerPort != null) {
            deploy.put("containerPort", containerPort);
        }
        if (healthPath != null) {
            deploy.put("healthPath", healthPath);
        }
        root.put("deploy", deploy);

        int effectivePort = port != null ? port : intOf(deploy.get("port"));
        String effectivePath = healthPath != null ? healthPath : textOf(deploy.get("healthPath"));
        // 只在端口/路径被改动时重建 URL；只改 target 时保留原 URL（可能指向远程服务器）
        String effectiveUrl = healthUrl;
        if (effectiveUrl == null && (port != null || healthPath != null)
                && effectivePort > 0 && effectivePath != null && !effectivePath.isBlank()) {
            effectiveUrl = "http://127.0.0.1:" + effectivePort + effectivePath;
        }
        if (effectiveUrl != null && root.get("stages") instanceof List<?> stages) {
            for (Object stageRaw : stages) {
                if (stageRaw instanceof Map<?, ?> stage && "health".equals(textOf(stage.get("type")))) {
                    ((Map<String, Object>) stage).put("url", effectiveUrl);
                }
            }
        }
        return new org.yaml.snakeyaml.Yaml().dump(root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static int intOf(Object raw) {
        return raw instanceof Number number ? number.intValue() : 0;
    }

    private static String textOf(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deployOf(String yaml) {
        Object loaded = new org.yaml.snakeyaml.Yaml().load(yaml);
        if (loaded instanceof Map<?, ?> root && root.get("deploy") instanceof Map<?, ?> deploy) {
            return copyMap(deploy);
        }
        return Map.of();
    }

    @Tool(name = "deploy_run",
            description = """
                    触发一次流水线运行（写操作，仅 dev/test）。
                    优先使用传入的 binding_id；未传时自动复用该环境最近一次已确认且未过期的绑定
                    （变量哈希变化则由 requireForRun 拒绝，需重新生成草案并人工确认）。
                    没有任何已确认绑定时，必须先调用 env_mapping_draft，由人在设置页确认。
                    服务器（prod）不允许用本工具触发：必须先在测试环境跑通并固化，再到部署面板确认发布。""",
            readOnly = false, concurrencySafe = false)
    public String run(@ToolParam(name = "pipeline_id", description = "流水线名（release / verify）")
                      String pipelineId,
                      @ToolParam(name = "git_sha", description = "要构建的提交 sha（7-40 位十六进制）") String sha,
                      @ToolParam(name = "env", description = "环境：dev / test（prod 请走部署面板确认）")
                      String env,
                      @ToolParam(name = "binding_id", description = "人工确认过的环境绑定 id（env_mapping_draft 生成）")
                      String bindingId,
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
        String effectiveBindingId = bindingId;
        if (effectiveBindingId == null || effectiveBindingId.isBlank()) {
            effectiveBindingId = bindings.latestConfirmed(ctx.projectId(), target)
                    .map(EnvBinding::id)
                    .orElse(null);
        }
        if (effectiveBindingId == null || effectiveBindingId.isBlank()) {
            return "err: 缺少 binding_id：请先调用 env_mapping_draft，等人在设置页确认后再触发"
                    + "（同一环境已有未过期的确认绑定时可省略，本次没有）";
        }
        // 绑定无效 / 触发被拒都是真失败：抛出让 Agent 与前端都看到失败态（而不是一段普通文本）
        EnvBinding binding = bindings.requireForRun(effectiveBindingId.trim(), ctx.projectId(), target);
        // 触发前必须做 dry-run：预检不通过就不要创建运行（否则只会留下一条失败历史）。
        // reportHash 一并传给引擎做二次校验，避免"预检后配方/提交又变了"。
        PipelineEngine.DryRunReport dryRun;
        try {
            dryRun = engine.dryRun(ctx.projectId(), pipelineId, target, sha);
        } catch (RuntimeException e) {
            return "err: dry-run 执行失败：" + e.getMessage();
        }
        if (!dryRun.ok()) {
            return "err: E-708 dry-run 未通过：" + String.join("；", dryRun.blockers());
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("gitSha", sha);
        params.put("env", target);
        params.put("actor", "agent");
        params.put("bindingId", binding.id());
        params.put("confirmedBy", binding.confirmedBy() == null ? "" : binding.confirmedBy());
        params.put("dryRunHash", dryRun.reportHash());
        params.put("dryRunFingerprint", dryRun.fingerprint());
        String runId = engine.trigger(ctx.projectId(), pipelineId, params);
        return "runId: " + runId + "\nenv: " + target + "\nstatus: " + engine.status(runId);
    }

    @Tool(name = "deploy_save_recipe",
            description = """
                    把「已经跑通的动作序列」固化成该环境的可复现脚本（写操作）。
                    只在确实跑通过之后调用；脚本会记录当前证据运行，供后续重放与上服务器判断。
                    actions 只能取：backend_build / backend_test / frontend_build / image_package。
                    需要部署时传 with_deploy=true，并显式指定 target（docker / host / server）：
                    固化脚本会按该目标与 health_path 渲染，避免回放到 host/server 时报 E-504。
                    可自动放行：dev/test 下无需额外确认单；生产环境拒绝执行。""",
            readOnly = false, concurrencySafe = false)
    public String saveRecipe(@ToolParam(name = "env", description = "环境：dev / test") String env,
                             @ToolParam(name = "actions",
                                     description = "动作序列，逗号分隔，例如 backend_build,backend_test,image_package")
                             String actions,
                             @ToolParam(name = "with_deploy", description = "是否包含部署与健康检查（true/false）")
                             String withDeploy,
                             @ToolParam(name = "target", required = false,
                                     description = "部署目标：docker / host / server（默认 docker）")
                             String target,
                             @ToolParam(name = "health_path", required = false,
                                     description = "健康检查路径，默认 /healthz")
                             String healthPath,
                             @ToolParam(name = "port", description = "部署端口，默认 8080") String port,
                             ProjectContext ctx) {
        String targetEnv = normalize(env);
        if ("prod".equals(targetEnv)) {
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
        String wireTarget = target == null || target.isBlank()
                ? "docker" : target.trim().toLowerCase();
        if (deploy && !List.of("docker", "host", "server").contains(wireTarget)) {
            return "err: target 只能是 docker / host / server，实际为 " + wireTarget;
        }
        if (deploy) {
            steps.add(RecipeRenderer.Step.deploy());
            steps.add(RecipeRenderer.Step.health());
            steps.add(RecipeRenderer.Step.rollback());
        }
        Integer parsedPort = parsePort(port);
        int actualPort = parsedPort == null ? 8080 : parsedPort;
        String path = healthPath == null || healthPath.isBlank() ? "/healthz" : healthPath.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        RecipeRenderer.RenderTarget renderTarget = deploy
                ? new RecipeRenderer.RenderTarget(wireTarget, actualPort, actualPort, path)
                : null;
        String yaml = RecipeRenderer.render("release", steps, renderTarget);

        String evidence = lastSuccessfulRun(ctx.projectId(), targetEnv);
        DeployRecipe saved = recipes.save(new DeployRecipe(ctx.projectId(), targetEnv, 0, yaml, null,
                evidence, null, System.currentTimeMillis(), "agent"));
        return "saved: v" + saved.version() + "\nenv: " + targetEnv
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
        if (!EnvNames.ALL.contains(value)) {
            throw new IllegalArgumentException(
                    "未知环境：" + env + "（可选 dev / test / prod；不会自动回落到 test）");
        }
        return value;
    }
}
