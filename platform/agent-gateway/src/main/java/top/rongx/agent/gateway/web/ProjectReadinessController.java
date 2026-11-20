package top.rongx.agent.gateway.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.EnvNames;
import top.rongx.agent.domain.port.PipelineDefStore;
import top.rongx.agent.domain.port.PipelineDefinitionInspector;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.domain.port.RepoInspector;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.project.ProjectEnv;
import top.rongx.agent.domain.settings.DeployServerRepository;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.tools.DockerfileTools;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;

/**
 * 「首次发布就绪检查」：把散落在设置页与接入页的前置条件聚合成一张清单。
 *
 * <p>以前用户要知道"还差什么"必须自己拼：仓库接了吗（接入页）、流水线定义有没有
 * （项目页脚手架）、服务器配了吗（设置→服务器）、必填变量齐吗（设置→项目环境）、
 * 这个环境跑通过没有（流水线页）。这里一次回答，并给每一项一个可点击的修复入口，
 * 前端因此只需要一个操作台。
 *
 * <p>只读接口：不触发构建、不写配置；修复动作仍走各自既有接口。
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectReadinessController {

    private static final Logger log = LoggerFactory.getLogger(ProjectReadinessController.class);

    /** 首次发布默认落在 test；与流水线页默认环境保持一致。 */
    private static final String DEFAULT_ENV = "test";

    private final ProjectRepoLocator repoLocator;
    private final PipelineDefStore defStore;
    private final ProjectRepository projects;
    private final RecipeStore recipes;
    private final DeployServerRepository servers;
    private final EnvironmentStore envStore;
    private final PipelineEngine engine;
    private final PipelineDefinitionInspector definitions;
    private final RepoInspector repoInspector;
    private final DockerfileTools dockerfileTools;
    private final ProjectContextResolver contextResolver;
    private final CurrentUser currentUser;
    private final boolean legacyServerConfigured;

    public ProjectReadinessController(ProjectRepoLocator repoLocator,
                                      PipelineDefStore defStore,
                                      ProjectRepository projects,
                                      RecipeStore recipes,
                                      DeployServerRepository servers,
                                      EnvironmentStore envStore,
                                      PipelineEngine engine,
                                      PipelineDefinitionInspector definitions,
                                      RepoInspector repoInspector,
                                      DockerfileTools dockerfileTools,
                                      ProjectContextResolver contextResolver,
                                      CurrentUser currentUser,
                                      @Value("${agent.deploy.server.host:}") String legacyServerHost) {
        this.repoLocator = repoLocator;
        this.defStore = defStore;
        this.projects = projects;
        this.recipes = recipes;
        this.servers = servers;
        this.envStore = envStore;
        this.engine = engine;
        this.definitions = definitions;
        this.repoInspector = repoInspector;
        this.dockerfileTools = dockerfileTools;
        this.contextResolver = contextResolver;
        this.currentUser = currentUser;
        this.legacyServerConfigured = legacyServerHost != null && !legacyServerHost.isBlank();
    }

    /**
     * 首次发布就绪清单。
     *
     * <p>{@code ready} 只由 required 项决定；「首次发布」一项是结果而非前置条件，
     * 所以不参与 ready 计算（还没跑过不代表配置不全）。
     */
    @GetMapping("/{id}/readiness")
    public Map<String, Object> readiness(@PathVariable String id,
                                         @RequestParam(defaultValue = DEFAULT_ENV) String env) {
        // 环境口径统一走 EnvNames：缺省 test、去空格转小写、未知值直接拒绝
        String target = EnvNames.normalize(env);
        Optional<Path> repoDir = repoLocator.repoDir(id).filter(Files::isDirectory);

        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(repoStep(repoDir));

        Definition def = loadDefinition(id, repoDir);
        steps.add(pipelineStep(def, repoDir));
        steps.add(envStep(id, target));
        steps.add(runtimeStep(def, id, repoDir));
        steps.add(envVarsStep(def, id, target));
        steps.add(firstRunStep(id, target));

        int required = 0;
        int done = 0;
        for (Map<String, Object> step : steps) {
            if (!Boolean.TRUE.equals(step.get("required"))) {
                continue;
            }
            required++;
            if (Boolean.TRUE.equals(step.get("ok"))) {
                done++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", id);
        out.put("env", target);
        out.put("target", def.target());
        out.put("ready", required > 0 && done == required);
        out.put("readyCount", done);
        out.put("totalCount", required);
        out.put("steps", steps);
        return out;
    }

    // ---------------------------------------------------------------- 一键修复

    /**
     * 补齐 Docker 镜像发布链路（就绪清单「运行条件」的一键修复）。
     *
     * <p>和 Agent 的 {@code ensure_dockerfile} 是同一套逻辑：按仓库结构生成 Dockerfile，
     * 需要时补 {@code image_package} 阶段与动作配方，并登记为**待确认补丁**（不直接改仓库）。
     * 返回 changeId + diff；前端确认后走 {@code /changes/{id}/confirm + /apply} 提交到
     * {@code agent/fix-*} 分支，再用返回的 sha 发布。
     *
     * <p>只读用户与生产环境不放行：生成 Dockerfile 属于写仓库。
     */
    @PostMapping("/{id}/dockerfile")
    public Map<String, Object> generateDockerfile(@PathVariable String id,
                                                  @RequestParam(defaultValue = DEFAULT_ENV) String env) {
        String userId = currentUser.requireUserId();
        String target = EnvNames.normalize(env);
        if ("prod".equals(target)) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "生产环境不允许生成 Dockerfile：请先在 dev/test 验证");
        }
        String baseSha = repoInspector.status(id).head();
        if (baseSha == null || baseSha.isBlank()) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "仓库没有可用提交：请先把代码提交一次再修复");
        }
        // BUG-27 入口5收敛：环境校验统一走 resolver，不再自行 new ProjectContext
        ProjectContext ctx = contextResolver.resolve(id, target, userId);
        String report = dockerfileTools.ensure(baseSha, null, ctx);
        if (report.startsWith("err:")) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, report.substring(4).trim());
        }
        Map<String, Object> out = new LinkedHashMap<>(parseReport(report));
        out.put("baseSha", baseSha);
        return out;
    }

    /**
     * 解析 {@code ensure_dockerfile} 的 {@code key: value} 报告（diff 之前的部分）。
     *
     * <p>这里只取前端要用的字段；输出格式是该工具的稳定契约（见 DockerfileTools）。
     */
    static Map<String, Object> parseReport(String report) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (report == null) {
            return out;
        }
        for (String line : report.split("\n")) {
            if (line.startsWith("---")) {
                break; // 后面是 diff 正文，不参与解析
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (!key.isEmpty() && !"note".equals(key)) {
                out.put(key, value);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 各检查项

    private Map<String, Object> repoStep(Optional<Path> repoDir) {
        return step("repo", "仓库已接入", repoDir.isPresent(), true,
                repoDir.map(Path::toString).orElse("平台还没有该项目的 git 仓库"),
                repoDir.isPresent() ? null : fixRoute("/projects/intake", "去接入"));
    }

    private Map<String, Object> pipelineStep(Definition def, Optional<Path> repoDir) {
        if (def.yaml() == null) {
            return step("pipeline", "流水线定义", false, true,
                    "还没有流水线定义（平台侧与仓库 ci/pipeline.yml 都没有）",
                    fixScaffold("生成流水线定义"));
        }
        if (def.error() != null) {
            return step("pipeline", "流水线定义", false, true,
                    "定义解析失败：" + def.error(), fixScaffold("重新生成"));
        }
        return step("pipeline", "流水线定义", true, true, def.source(), null);
    }

    private Map<String, Object> envStep(String id, String env) {
        List<ProjectEnv> envs = projects.findEnvs(id);
        boolean ok = envs.stream().anyMatch(e -> env.equals(e.env()));
        String detail = ok
                ? env + " 环境已登记"
                : "项目没有登记 " + env + " 环境（重新接入会自动补齐 dev/test/prod）";
        return step("env", "测试环境", ok, true, detail,
                ok ? null : fixRoute("/projects/intake", "去接入"));
    }

    private Map<String, Object> runtimeStep(Definition def, String id, Optional<Path> repoDir) {
        if (def.error() != null) {
            return step("runtime", "运行条件", false, true,
                    "流水线定义不可用，无法判断运行条件", null);
        }
        if (!def.hasDeploy()) {
            return step("runtime", "运行条件", false, true,
                    "定义里没有 deploy 段：只能构建，不能发布",
                    fixScaffold("重新生成"));
        }
        String target = def.target().isBlank() ? "docker" : def.target();
        return switch (target) {
            case "server" -> serverRuntime();
            case "host" -> step("runtime", "运行条件", true, true,
                    "本机直接运行（Java jar，pid 管理）", null);
            default -> dockerRuntime(id, repoDir);
        };
    }

    private Map<String, Object> dockerRuntime(String id, Optional<Path> repoDir) {
        boolean docker = engine.dockerAvailable();
        boolean dockerfile = hasDockerfile(id, repoDir);
        if (docker && dockerfile) {
            return step("runtime", "运行条件", true, true, "本机 Docker 可用，仓库有 Dockerfile", null);
        }
        String detail = !docker
                ? "本机 Docker 不可用：可以改用「直接部署」（本机进程）"
                : "仓库没有 Dockerfile，镜像无法构建";
        return step("runtime", "运行条件", false, true, detail,
                !docker
                        ? fixRoute("/pipeline?target=host", "改用直接部署")
                        : fixAction("dockerfile", "生成 Dockerfile"));
    }

    private Map<String, Object> serverRuntime() {
        Optional<top.rongx.agent.domain.settings.DeployServer> server = servers.findDefault();
        boolean ok = server.isPresent() || legacyServerConfigured;
        String detail = server
                .map(s -> "默认服务器：" + s.name() + "（" + s.host() + "）")
                .orElse(legacyServerConfigured
                        ? "已通过环境变量配置部署服务器"
                        : "没有可用的部署服务器");
        return step("runtime", "运行条件", ok, true, detail,
                ok ? null : fixRoute("/settings?section=servers", "去配置服务器"));
    }

    private Map<String, Object> envVarsStep(Definition def, String id, String env) {
        List<String> required = def.envRequired();
        if (required.isEmpty()) {
            return step("envvars", "必填变量", true, true, "部署声明未要求额外变量", null);
        }
        Map<String, String> loaded = Map.of();
        try {
            EffectiveEnvironment effective = envStore.effective(id, env);
            loaded = effective == null ? Map.of() : effective.values();
        } catch (RuntimeException e) {
            log.warn("读取项目 {} 环境 {} 的有效变量失败：{}", id, env, e.getMessage());
        }
        final Map<String, String> values = loaded;
        List<String> missing = required.stream()
                .filter(key -> {
                    String value = values.get(key);
                    return value == null || value.isBlank();
                })
                .toList();
        if (missing.isEmpty()) {
            return step("envvars", "必填变量", true, true, required.size() + " 个必填变量已配置", null);
        }
        return step("envvars", "必填变量", false, true,
                "缺少：" + String.join("、", missing),
                fixRoute("/settings?section=envs", "去填写"));
    }

    private Map<String, Object> firstRunStep(String id, String env) {
        Optional<top.rongx.agent.domain.deploy.DeployRecipe> current = recipes.current(id, env);
        if (current.isPresent()) {
            return step("first-run", "首次发布", true, false,
                    "已有 " + env + " 的成功发布记录（固化脚本 v" + current.get().version() + "）", null);
        }
        return step("first-run", "首次发布", false, false,
                "尚无 " + env + " 的成功发布记录（首次发布会自动固化脚本）", null);
    }

    // ---------------------------------------------------------------- 定义读取

    /** 定义文本 + 来源 + 解析结论；yaml 为 null 表示完全没有定义。 */
    private record Definition(String yaml, String source,
                              PipelineDefinitionInspector.Inspection inspected, String error) {
        String target() {
            return inspected == null || inspected.target() == null ? "" : inspected.target();
        }

        boolean hasDeploy() {
            return inspected != null && inspected.hasDeploy();
        }

        List<String> envRequired() {
            return inspected == null || inspected.envRequired() == null
                    ? List.of() : inspected.envRequired();
        }
    }

    /**
     * 读取顺序与运行时一致：仓库 {@code ci/pipeline.yml} 优先，其次平台侧定义。
     * 只读，不生成——生成动作留给用户点「生成流水线定义」。
     */
    private Definition loadDefinition(String id, Optional<Path> repoDir) {
        String yaml = null;
        String source = null;
        Path fromRepo = repoDir.map(dir -> dir.resolve("ci/pipeline.yml")).orElse(null);
        if (fromRepo != null && Files.isRegularFile(fromRepo)) {
            try {
                yaml = Files.readString(fromRepo);
                source = "仓库 ci/pipeline.yml（随代码提交）";
            } catch (Exception e) {
                return new Definition(null, null, null, "读取 " + fromRepo + " 失败：" + e.getMessage());
            }
        }
        if (yaml == null && defStore.exists(id)) {
            yaml = defStore.read(id).orElse(null);
            source = "平台侧 ci/pipeline.yml";
        }
        if (yaml == null) {
            return new Definition(null, null, null, null);
        }
        PipelineDefinitionInspector.Inspection inspected = definitions.inspect(yaml);
        if (inspected.error() != null) {
            return new Definition(yaml, source, null, inspected.error());
        }
        return new Definition(yaml, source, inspected, null);
    }

    /** Dockerfile 可能在用户目录（DIRECT）或平台 verify 工作树（MANAGED）。 */
    private boolean hasDockerfile(String id, Optional<Path> repoDir) {
        if (repoDir.isPresent() && Files.isRegularFile(repoDir.get().resolve("Dockerfile"))) {
            return true;
        }
        return repoLocator.worktreeRoot(id)
                .map(root -> root.resolve("wt-verify").resolve("Dockerfile"))
                .filter(Files::isRegularFile)
                .isPresent();
    }

    // ---------------------------------------------------------------- 视图

    private static Map<String, Object> step(String key, String label, boolean ok,
                                            boolean required, String detail,
                                            Map<String, Object> fix) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("label", label);
        row.put("ok", ok);
        row.put("required", required);
        row.put("detail", detail == null ? "" : detail);
        row.put("fix", fix);
        return row;
    }

    private static Map<String, Object> fixRoute(String target, String label) {
        return Map.of("kind", "route", "target", target, "label", label);
    }

    /** 修复动作交给前端调用既有的脚手架保存接口（POST /projects/{id}/scaffold）。 */
    private static Map<String, Object> fixScaffold(String label) {
        return Map.of("kind", "scaffold", "target", "auto", "label", label);
    }

    /** 平台内可执行的一键修复（当前只有生成 Dockerfile）。 */
    private static Map<String, Object> fixAction(String action, String label) {
        return Map.of("kind", "action", "target", action, "label", label);
    }
}
