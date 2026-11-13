package top.rongx.agent.gateway.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.PipelineEngine.LogSlice;
import top.rongx.agent.domain.port.PipelineEngine.RunMeta;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.gateway.deploy.DeployGate;

/** 流水线运行查询（CI 的"构建历史/阶段视图"）。 */
@RestController
@RequestMapping("/api/v1/ci")
public class PipelineController {

    private final PipelineEngine engine;
    private final boolean legacyServerConfigured;
    private final top.rongx.agent.domain.settings.DeployServerRepository deployServers;
    private final DeployGate deployGate;
    private final top.rongx.agent.gateway.settings.EnvBindingService bindings;
    private final top.rongx.agent.gateway.auth.CurrentUser currentUser;

    public PipelineController(PipelineEngine engine,
                              @Value("${agent.deploy.server.host:}") String serverHost,
                              top.rongx.agent.domain.settings.DeployServerRepository deployServers,
                              DeployGate deployGate,
                              top.rongx.agent.gateway.settings.EnvBindingService bindings,
                              top.rongx.agent.gateway.auth.CurrentUser currentUser) {
        this.engine = engine;
        this.legacyServerConfigured = serverHost != null && !serverHost.isBlank();
        this.deployServers = deployServers;
        this.deployGate = deployGate;
        this.bindings = bindings;
        this.currentUser = currentUser;
    }

    @GetMapping("/pipelines")
    public Map<String, Object> pipelines(String projectId) {
        return Map.of("engine", engine.id(), "pipelines", engine.pipelines(projectId));
    }

    /**
     * 阶段预览：点击"运行"之前先看清这次到底会跑哪些阶段。
     *
     * <p>「检查构建」会裁掉部署/健康检查；「发布」在仓库没有 Dockerfile 时也会裁掉镜像。
     */
    @GetMapping("/pipelines/{pipelineId}/preview")
    public Map<String, Object> preview(@PathVariable String pipelineId,
                                       @RequestParam String projectId,
                                       @RequestParam(required = false) String env,
                                       @RequestParam(required = false) String gitSha) {
        return Map.of(
                "pipelineId", pipelineId,
                "env", env == null ? "" : env,
                // 指定提交时按该提交的定义预览（仓库定义会随提交变化）
                "gitSha", gitSha == null ? "" : gitSha,
                "stages", engine.preview(projectId, pipelineId, env, gitSha).stream()
                        .map(s -> {
                            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                            row.put("id", s.id());
                            row.put("type", s.type());
                            row.put("action", s.action() == null ? "" : s.action());
                            row.put("runner", s.runner() == null ? "" : s.runner());
                            row.put("workdir", s.workdir() == null ? "" : s.workdir());
                            // 跳过原因非空：这次不会执行（例如 verify 不部署/不产镜像）
                            row.put("skipReason", s.skipReason() == null ? "" : s.skipReason());
                            // 命令级预览：脚本由配方确定性渲染，执行日志首行与之一致
                            row.put("shell", s.shell() == null ? "" : s.shell());
                            row.put("command", s.command() == null ? "" : s.command());
                            row.put("script", s.script() == null ? "" : s.script());
                            row.put("scriptSha", s.scriptSha() == null ? "" : s.scriptSha());
                            row.put("builtin", s.builtin());
                            return row;
                        })
                        .toList());
    }

    private static Map<String, Object> dryRunView(PipelineEngine.DryRunReport report) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", report.projectId());
        out.put("env", report.env());
        out.put("pipelineId", report.pipelineId());
        out.put("gitSha", report.gitSha());
        out.put("reportHash", report.reportHash());
        out.put("fingerprint", report.fingerprint());
        out.put("ok", report.ok());
        out.put("blockers", report.blockers());
        out.put("warnings", report.warnings());
        out.put("createdAt", report.createdAt());
        out.put("stages", report.stages().stream().map(s -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.id());
            row.put("type", s.type());
            row.put("action", s.action() == null ? "" : s.action());
            row.put("runnable", s.runnable());
            row.put("reason", s.reason() == null ? "" : s.reason());
            row.put("command", s.command() == null ? "" : s.command());
            row.put("scriptSha", s.scriptSha() == null ? "" : s.scriptSha());
            row.put("builtin", s.builtin());
            return row;
        }).toList());
        return out;
    }

    @PostMapping("/pipelines/{pipelineId}/dry-run")
    public Map<String, Object> dryRun(@PathVariable String pipelineId,
                                      @RequestBody Map<String, String> params) {
        String projectId = params.get("projectId");
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 必填");
        }
        currentUser.checkPermission("ci:run");
        String env = params.getOrDefault("env", "test");
        PipelineEngine.DryRunReport report = engine.dryRun(projectId, pipelineId, env,
                params.get("gitSha"));
        return dryRunView(report);
    }

    @PostMapping("/pipelines/{pipelineId}/run")
    public Map<String, Object> run(@PathVariable String pipelineId,
                                   @RequestBody Map<String, String> params) {
        // projectId 必填：不再默认到某个写死的项目（防误触发）
        String projectId = params.get("projectId");
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 必填");
        }
        String sha = params.get("gitSha");
        // 环境：缺省 test（绝不让"没传环境"落到生产）
        String env = params.get("env");
        String normalizedEnv = env == null || env.isBlank() ? "test" : env.trim().toLowerCase();
        // 按版本部署：复用已存在的镜像 tag，跳过构建/打包，只跑 deploy + health。
        // 此时 gitSha 可由版本账本反查，调用方不必传（传了也不采信，防止"tag 与提交不一致"）。
        String deployImage = params.get("deployImage");
        boolean imageOnly = deployImage != null && !deployImage.isBlank();
        if (imageOnly) {
            deployImage = deployImage.trim();
            if (!deployImage.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
                throw new IllegalArgumentException("镜像 tag 非法：" + deployImage);
            }
            // 只允许部署本项目/本环境登记过的版本，防止借接口启动任意本机镜像
            sha = engine.gitShaForImage(projectId, normalizedEnv, deployImage).orElse(null);
            if (sha == null) {
                throw new IllegalArgumentException("镜像版本不存在或不属于 " + projectId
                        + " / " + normalizedEnv + "：" + deployImage);
            }
            // 预检前移：镜像是否还在、Docker 是否可用、环境密钥/必填项是否齐全，
            // 不通过直接 400，避免"触发成功 → 跑一半才失败"。
            java.util.List<String> blockers = engine.deployPreflight(projectId, normalizedEnv, deployImage);
            if (!blockers.isEmpty()) {
                throw new IllegalArgumentException(String.join("；", blockers));
            }
        } else if (sha == null || sha.isBlank()) {
            throw new IllegalArgumentException("gitSha 必填");
        }
        // 阶段门控：先 dry-run（只读预检），不通过不允许 apply；指定的报告哈希必须与当前一致。
        // 按版本部署没有待执行的构建脚本，门控换成上一步的"版本归属"校验。
        PipelineEngine.DryRunReport dryRun = null;
        if (!imageOnly) {
            dryRun = engine.dryRun(projectId, pipelineId, normalizedEnv, sha);
            if (!dryRun.ok()) {
                throw new IllegalArgumentException("E-708 dry-run 未通过："
                        + String.join("；", dryRun.blockers()));
            }
            String requestedHash = params.get("dryRunHash");
            if (requestedHash != null && !requestedHash.isBlank()
                    && !requestedHash.equals(dryRun.reportHash())) {
                throw new IllegalArgumentException("E-706 dry-run 报告已过期（代码/配方/环境/密钥版本已变化），请重新 dry-run");
            }
        }
        // 服务器（prod）操作：测试证据 + 一次性人工确认令牌，两个入口（这里 / deploy/replay）共用同一校验
        deployGate.requireIfNeeded(projectId, normalizedEnv, sha, params.get("confirmToken"));
        // Agent 触发：必须带已人工确认的环境绑定（普通 UI 触发不强制）
        String bindingId = params.get("bindingId");
        String actor = currentUser.requireUserId();
        String confirmedBy = null;
        if (bindingId != null && !bindingId.isBlank()) {
            confirmedBy = bindings.requireForRun(bindingId, projectId, normalizedEnv).confirmedBy();
        }
        Map<String, String> triggerParams = new LinkedHashMap<>();
        triggerParams.put("gitSha", sha);
        triggerParams.put("env", normalizedEnv);
        if (dryRun != null) {
            triggerParams.put("dryRunHash", dryRun.reportHash());
            triggerParams.put("dryRunFingerprint", dryRun.fingerprint());
        }
        if (imageOnly) {
            triggerParams.put("deployImage", deployImage);
        }
        triggerParams.put("actor", bindingId == null || bindingId.isBlank() ? "ui:" + actor : "agent");
        if (bindingId != null && !bindingId.isBlank()) {
            triggerParams.put("bindingId", bindingId);
            triggerParams.put("confirmedBy", confirmedBy == null ? "" : confirmedBy);
        }
        // 可选：本次运行的部署目标覆盖（docker / host / server），空则由 pipeline.yml 决定
        String deployTarget = params.get("deployTarget");
        if (deployTarget != null && !deployTarget.isBlank()) {
            triggerParams.put("deployTarget", deployTarget.trim().toLowerCase());
        }
        String runId = engine.trigger(projectId, pipelineId, triggerParams);
        return Map.of("runId", runId, "env", normalizedEnv, "status", engine.status(runId));
    }

    /**
     * 镜像版本列表（按环境）：来自持久化的版本账本，不再从构建历史反推。
     *
     * <p>删除构建历史不会丢掉版本记录；第一条是「当前版本」，第二条是「上一版」，
     * 两者都来自同一次发布，因此不会出现"同一次运行多个当前版本"。
     */
    @GetMapping("/deploy/images")
    public Map<String, Object> deployImages(@RequestParam String projectId,
                                            @RequestParam(defaultValue = "test") String env) {
        java.util.List<PipelineEngine.ImageVersion> rows = engine.deployImages(projectId, env);
        java.util.List<Map<String, Object>> images = new java.util.ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            PipelineEngine.ImageVersion v = rows.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tag", v.tag());
            row.put("digest", v.digest() == null ? "" : v.digest());
            row.put("sizeBytes", v.sizeBytes());
            row.put("version", v.version() == null ? "" : v.version());
            row.put("runId", v.runId());
            row.put("gitSha", v.gitSha());
            row.put("status", v.status());
            row.put("createdAt", v.createdAt());
            // 部署审计：最近一次发布到本环境的时间与操作者
            row.put("deployedAt", v.deployedAt());
            row.put("deployedBy", v.deployedBy() == null ? "" : v.deployedBy());
            row.put("current", i == 0);
            row.put("previous", i == 1);
            images.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("env", env);
        out.put("dockerAvailable", engine.dockerAvailable());
        out.put("images", images);
        return out;
    }

    /** 可选部署目标（前端下拉用）；available=false 时给出不可用原因。 */
    @GetMapping("/deploy/targets")
    public List<Map<String, Object>> deployTargets() {
        var defaultServer = deployServers.findDefault();
        boolean serverReady = defaultServer.isPresent() || legacyServerConfigured;
        String serverDetail = defaultServer
                .map(s -> "默认服务器：" + s.name() + "（" + s.host() + "）")
                .orElse(legacyServerConfigured
                        ? "已通过环境变量配置" : "未配置：请到「设置 → 服务器」添加");
        // Docker 是否可用要真实探测（带缓存），否则界面显示可用、触发后才在部署阶段失败
        boolean dockerReady = engine.dockerAvailable();
        return List.of(
                Map.of("target", "docker", "label", "本机 Docker 容器", "available", dockerReady,
                        "detail", dockerReady
                                ? "替换式发布；需要 Dockerfile 与镜像"
                                : "本机 Docker 不可用：请确认已安装并启动 Docker"),
                Map.of("target", "host", "label", "本机直接运行", "available", true,
                        "detail", "仅 Java jar；pid 文件管理"),
                Map.of("target", "server", "label", "远程服务器", "available",
                        serverReady, "detail", serverDetail));
    }

    /**
     * 停止开发环境本机前后端（停掉后可用 IDE 启动）。
     *
     * <p>与流水线"直接部署"配套：平台托管的本机进程随时可以交还给 IDE。
     */
    @PostMapping("/deploy/host/stop")
    public Map<String, Object> stopLocalDev(@RequestParam String projectId,
                                            @RequestParam(defaultValue = "dev") String env) {
        boolean stopped = engine.stopLocalDev(projectId, env);
        return Map.of("stopped", stopped,
                "message", stopped
                        ? "已停止本机前后端进程，可用 IDE 启动"
                        : "本机没有平台托管的前后端进程");
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> status(@PathVariable String runId,
                                      @RequestParam(defaultValue = "true") boolean withLog) {
        return toView(runId, withLog);
    }

    /**
     * 增量日志片段：从 offset 起返回新增文本。
     *
     * <p>前端按 offset 续拉、追加渲染，避免每次轮询都重传/重绘整段日志。
     * 返回 {@code {offset, end, text, complete}}：complete 表示运行为终态且已读到末尾。
     */
    @GetMapping("/runs/{runId}/log")
    public Map<String, Object> log(@PathVariable String runId,
                                   @RequestParam(defaultValue = "0") int offset) {
        if (!engine.hasRun(runId)) {
            throw new IllegalArgumentException("运行不存在：" + runId);
        }
        LogSlice slice = engine.logFrom(runId, offset);
        boolean complete = engine.status(runId).isTerminal() && slice.end() == slice.offset();
        return Map.of(
                "offset", slice.offset(),
                "end", slice.end(),
                "text", slice.text(),
                "complete", complete);
    }

    @PostMapping("/runs/{runId}/cancel")
    public Map<String, Object> cancel(@PathVariable String runId) {
        engine.cancel(runId);
        return toView(runId, true);
    }

    /** 构建历史（按触发时间倒序；projectId 可选过滤，offset/limit 分页）。 */
    @GetMapping("/history")
    public List<Map<String, Object>> history(String projectId,
                                             @RequestParam(defaultValue = "0") int offset,
                                             @RequestParam(defaultValue = "50") int limit) {
        int from = Math.max(0, offset);
        int size = Math.min(Math.max(1, limit), 200);
        return engine.history().stream()
                .filter(h -> projectId == null || projectId.isBlank()
                        || h.projectId().equals(projectId))
                .skip(from)
                .limit(size)
                .map(h -> Map.<String, Object>of(
                        "runId", h.runId(),
                        "projectId", h.projectId(),
                        "env", h.env(),
                        "pipelineId", h.pipelineId(),
                        "gitSha", h.gitSha(),
                        "status", h.status().name(),
                        "createdAt", h.createdAt(),
                        "seq", h.seq(),
                        "stageCount", h.stageCount(),
                        // 各阶段耗时之和：列表直接显示「这次跑了多久」，不用再点进去
                        "durationMs", h.durationMs()))
                .toList();
    }

    /** 从失败阶段继续（复用保留的构建现场，只重跑该阶段及之后）。 */
    @PostMapping("/runs/{runId}/retry")
    public Map<String, Object> retry(@PathVariable String runId,
                                     @RequestParam String stageId) {
        try {
            engine.retryStage(runId, stageId);
            return Map.of("runId", runId, "retrying", true);
        } catch (IllegalArgumentException e) {
            return Map.of("runId", runId, "retrying", false, "message", e.getMessage());
        }
    }

    /** 下载文件产物（jar / dist 等）：只允许读取本次运行的产物归档目录。 */
    @GetMapping("/runs/{runId}/artifacts/{index}/file")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadArtifact(
            @PathVariable String runId, @PathVariable int index) {
        java.nio.file.Path file = engine.artifactFile(runId, index).orElse(null);
        if (file == null) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getFileName() + "\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(new org.springframework.core.io.FileSystemResource(file));
    }

    /** 删除一条构建历史（滑动删除）；运行中返回 400。 */
    @DeleteMapping("/history/{runId}")
    public Map<String, Object> deleteHistory(@PathVariable String runId) {
        try {
            return Map.of("deleted", engine.deleteHistory(runId));
        } catch (IllegalArgumentException e) {
            return Map.of("deleted", false, "message", e.getMessage());
        }
    }

    private Map<String, Object> toView(String runId, boolean withLog) {
        StageStatus status = engine.status(runId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", runId);
        view.put("status", status);
        view.put("stages", engine.stages(runId).stream().map(this::stageView).toList());
        view.put("artifacts", engine.artifacts(runId).stream().map(this::artifactView).toList());
        // 增量日志模式下（withLog=false）不重复传输整段日志
        view.put("log", withLog ? engine.logTail(runId) : "");
        // 运行元信息：触发者 / 环境绑定 / 定义来源（Agent 固化脚本时可直接看出）
        RunMeta meta = engine.runMeta(runId);
        if (meta != null) {
            view.put("meta", Map.of(
                    "actor", meta.actor() == null ? "" : meta.actor(),
                    "bindingId", meta.bindingId() == null ? "" : meta.bindingId(),
                    "confirmedBy", meta.confirmedBy() == null ? "" : meta.confirmedBy(),
                    "definitionSource", meta.definitionSource() == null ? "" : meta.definitionSource(),
                    "definitionNote", meta.definitionNote() == null ? "" : meta.definitionNote()));
        }
        return view;
    }

    private Map<String, Object> stageView(StageResult s) {
        return Map.of("id", s.stageId(), "status", s.status(), "exitCode", s.exitCode(),
                "errorCode", s.errorCode() == null ? "" : s.errorCode(),
                "summary", s.errorSummary() == null ? "" : s.errorSummary(),
                "durationMs", s.durationMs(),
                "artifacts", s.artifacts().size());
    }

    private Map<String, Object> artifactView(ArtifactRef a) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", a.kind());
        view.put("ref", a.ref());
        view.put("digest", a.digest() == null ? "" : a.digest());
        view.put("sizeBytes", a.sizeBytes());
        // 部署产物的附加信息（本机模式带 backendPort / frontendPort，供页面展示）
        view.put("meta", a.meta());
        return view;
    }
}
