package top.rongx.agent.gateway.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.deploy.DeployRecipe;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.gateway.deploy.DeployGate;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.DeployException;

/**
 * 环境视角的部署接口：每个环境跑通过什么、重放、脚本版本、以及"上服务器"的确认。
 *
 * <p>分工：流水线负责"怎么跑"，这里负责"在哪个环境跑、有没有资格跑"。
 * 高危操作只有一个——在服务器（prod）上操作，需要 {测试证据 + 一次性人工确认令牌}。
 */
@RestController
@RequestMapping("/api/v1/deploy")
public class DeployController {

    private static final Logger log = LoggerFactory.getLogger(DeployController.class);

    private final RecipeStore recipes;
    private final PipelineEngine engine;
    private final DeployGate gate;
    private final CurrentUser currentUser;
    private final top.rongx.agent.domain.env.EnvironmentStore envStore;

    public DeployController(RecipeStore recipes, PipelineEngine engine,
                            DeployGate gate, CurrentUser currentUser,
                            top.rongx.agent.domain.env.EnvironmentStore envStore) {
        this.recipes = recipes;
        this.engine = engine;
        this.gate = gate;
        this.currentUser = currentUser;
        this.envStore = envStore;
    }

    /** 端口租约：本次部署实际使用的宿主端口（排障 / 审计）。 */
    @GetMapping("/ports")
    public List<Map<String, Object>> ports(@RequestParam String projectId,
                                           @RequestParam(defaultValue = "test") String env) {
        currentUser.checkPermission("settings:read");
        return envStore.leases(projectId, env.trim().toLowerCase()).stream()
                .map(l -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("projectId", l.projectId());
                    row.put("env", l.env());
                    row.put("containerPort", l.containerPort());
                    row.put("hostPort", l.hostPort());
                    row.put("runId", l.runId());
                    row.put("updatedAt", l.updatedAt());
                    return row;
                })
                .toList();
    }

    /** 每个环境的状态：脚本版本、证据运行、能否上服务器。 */
    @GetMapping("/{projectId}/envs")
    public List<Map<String, Object>> envs(@PathVariable String projectId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String env : DeployRecipe.order()) {
            Optional<DeployRecipe> current = recipes.current(projectId, env);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("env", env);
            row.put("recipeVersion", current.map(DeployRecipe::version).orElse(0));
            row.put("evidenceRunId", current.map(DeployRecipe::evidenceRunId).orElse(""));
            StageStatus evidence = current.filter(DeployRecipe::hasEvidence)
                    .map(r -> engine.status(r.evidenceRunId()))
                    .orElse(null);
            row.put("evidenceStatus", evidence == null ? "" : evidence.name());
            row.put("gitSha", current.map(DeployRecipe::gitSha).orElse(""));
            row.put("createdAt", current.map(DeployRecipe::createdAt).orElse(0L));
            row.put("recipeCount", recipes.versions(projectId, env).size());
            if ("prod".equals(env)) {
                String problem = gate.evidenceProblem(projectId, env, null);
                row.put("promotable", problem == null);
                row.put("reason", problem == null ? "" : problem);
            }
            out.add(row);
        }
        return out;
    }

    /**
     * 上服务器前的确认：先校验测试证据，再签发一次性令牌。
     *
     * <p>前端拿到 token 后带着它触发流水线；令牌与 project+env+sha 绑定，用过即废。
     */
    @PostMapping("/{projectId}/confirm")
    public Map<String, Object> confirm(@PathVariable String projectId,
                                       @RequestParam String env,
                                       @RequestParam String sha) {
        String userId = currentUser.requireUserId();
        if (!sha.matches("[0-9a-f]{7,40}")) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "sha 非法（需 7-40 位小写十六进制）");
        }
        String problem = gate.evidenceProblem(projectId, env, sha);
        Map<String, Object> out = new LinkedHashMap<>();
        if (problem != null) {
            out.put("allowed", false);
            out.put("reason", problem);
            return out;
        }
        var test = recipes.current(projectId, "test");
        out.put("allowed", true);
        out.put("confirmToken", gate.issue(userId, projectId, env, sha));
        out.put("changeId", DeployGate.changeId(projectId, env, sha));
        out.put("evidence", Map.of(
                "env", "test",
                "version", test.map(DeployRecipe::version).orElse(0),
                "runId", test.map(DeployRecipe::evidenceRunId).orElse(""),
                "gitSha", test.map(DeployRecipe::gitSha).orElse(""),
                "status", test.map(r -> engine.status(r.evidenceRunId()).name()).orElse("")));
        log.info("签发上服务器确认令牌：project={} env={} sha={} user={}", projectId, env, sha, userId);
        return out;
    }

    /**
     * 重放：用该环境已固化的脚本确定性跑一次（不调用模型）。
     *
     * <p>{@code deployTarget} 必须跟着重放一起进来：固化脚本里记的是"当初跑通时的目标"，
     * 用户在面板上选了「打包成镜像」，若这里不接收就会被静默丢弃、按脚本里的 host 执行。
     */
    /** 兼容旧调用（不携带版本信息）：委托给带版本参数的重载。 */
    public Map<String, Object> replay(String projectId,
                                      String env,
                                      String sha,
                                      String confirmToken,
                                      String deployTarget) {
        return replay(projectId, env, sha, confirmToken, deployTarget, null, null);
    }

    /**
     * 重放并携带发布版本信息。
     *
     * <p>{@code version} / {@code tag} 必须跟着重放一起下发：test 环境点主发布按钮时，
     * 有固化脚本会走重放路径（{@code replayEnv}），若这里不接收，用户填的版本号与
     * 打 tag 开关就在网关这段被丢掉——引擎按快照构建跑，git tag 不打、历史里
     * version/tagStatus 全空，表现就是「打了 tag 却显示是空的」。
     */
    @PostMapping("/{projectId}/replay")
    public Map<String, Object> replay(@PathVariable String projectId,
                                      @RequestParam String env,
                                      @RequestParam String sha,
                                      @RequestParam(required = false) String confirmToken,
                                      @RequestParam(required = false) String deployTarget,
                                      @RequestParam(required = false) String version,
                                      @RequestParam(required = false) String tag) {
        currentUser.requireUserId();
        String target = normalizeTarget(deployTarget);
        // prod 允许用"测试环境固化脚本"晋升（证据由 DeployGate 校验）；其他环境必须自己有脚本
        boolean hasScript = recipes.current(projectId, env).isPresent()
                || ("prod".equals(env) && recipes.current(projectId, "test").isPresent());
        if (!hasScript) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "环境 " + env + " 还没有固化脚本：请先在该环境跑通一次（平台会自动固化）");
        }
        gate.requireIfNeeded(projectId, env, sha, confirmToken);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("gitSha", sha);
        params.put("env", env);
        // 目标随重放下发：不传时引擎按固化脚本里的 target 走
        if (target != null) {
            params.put("deployTarget", target);
        }
        // 版本号 + 打 tag：与 /ci/pipelines/{id}/run 同一套参数名，引擎统一在 trigger 里读。
        if (version != null && !version.isBlank()) {
            params.put("version", version.trim());
        }
        if (tag != null && !tag.isBlank()) {
            params.put("tag", tag);
        }
        String runId = engine.trigger(projectId, releasePipeline(projectId), params);
        return Map.of("runId", runId, "env", env, "status", engine.status(runId).name(),
                "deployTarget", target == null ? "" : target);
    }

    /** 部署目标只接受 docker / host / server；非法值直接拒绝，避免静默回落成脚本里的旧目标。 */
    private static String normalizeTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        if (!value.equals("docker") && !value.equals("host") && !value.equals("server")) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT,
                    "deployTarget 只能是 docker / host / server：" + raw);
        }
        return value;
    }

    /**
     * 回放用的流水线名：优先 release，其次定义里实际的发布流水线。
     * 以前固定 "release"，项目把 name 改成别的（或只剩自定义流水线）时回放直接不在白名单。
     */
    private String releasePipeline(String projectId) {
        List<String> names = engine.pipelines(projectId);
        if (names.contains("release")) {
            return "release";
        }
        return names.stream()
                .filter(name -> !"verify".equals(name))
                .findFirst()
                .orElse("release");
    }

    /** 脚本版本列表（回滚脚本用，不含部署）。 */
    @GetMapping("/{projectId}/recipes")
    public List<Map<String, Object>> recipes(@PathVariable String projectId,
                                             @RequestParam String env) {
        return recipes.versions(projectId, env).stream()
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("version", r.version());
                    row.put("evidenceRunId", r.evidenceRunId());
                    row.put("gitSha", r.gitSha());
                    row.put("createdAt", r.createdAt());
                    row.put("createdBy", r.createdBy());
                    row.put("content", r.pipelineYaml());
                    return row;
                })
                .toList();
    }

    /** 把某个历史版本重新设为生效（只切脚本，不触发部署）。 */
    @PostMapping("/{projectId}/recipes/activate")
    public Map<String, Object> activate(@PathVariable String projectId,
                                        @RequestParam String env,
                                        @RequestParam int version) {
        currentUser.requireUserId();
        if (!recipes.activate(projectId, env, version)) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "版本不存在：v" + version);
        }
        return Map.of("ok", true, "env", env, "version", version);
    }
}
