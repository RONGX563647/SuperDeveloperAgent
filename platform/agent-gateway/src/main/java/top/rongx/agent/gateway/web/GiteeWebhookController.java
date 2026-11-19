package top.rongx.agent.gateway.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.conversation.TaskStatus;
import top.rongx.agent.domain.conversation.WorkspaceTask;
import top.rongx.agent.domain.conversation.WorkspaceTaskRepository;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.IntakeException;

/**
 * Gitee webhook 接入：push 事件自动触发流水线。
 *
 * <p>安全：校验 {@code X-Gitee-Token}（Gitee WebHook 密码）或
 * {@code X-Gitee-Signature} HMAC-SHA256；未配置密钥时拒绝，不静默放行。
 *
 * <p>幂等：同一个 commit sha 的 push 只触发一次（内存去重，重启后由历史文件去重）。
 */
@RestController
@RequestMapping("/api/v1/webhooks/gitee")
public class GiteeWebhookController {

    private final PipelineEngine engine;
    private final ProjectRepository projects;
    private final WorkspaceTaskRepository tasks;
    private final String secret;
    private final java.util.Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 事件去重键：同一 issue/PR 的同一动作只建一次任务（内存去重，重启后由任务表去重）。 */
    private final java.util.Set<String> seenEvents = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public GiteeWebhookController(PipelineEngine engine,
                                  ProjectRepository projects,
                                  WorkspaceTaskRepository tasks,
                                  @Value("${agent.webhook.gitee-secret:}") String secret) {
        this.engine = engine;
        this.projects = projects;
        this.tasks = tasks;
        this.secret = secret;
        // 通过 domain 端口恢复去重集合（不依赖执行面具体类）
        engine.history().forEach(h -> seen.add(h.projectId() + ":" + h.gitSha()));
    }

    /**
     * Gitee push hook：请求体里取 {@code after}（提交 sha）、
     * {@code project.path_with_namespace}（项目名）。
     */
    @PostMapping("/push")
    public Map<String, Object> push(
            @RequestHeader(value = "X-Gitee-Token", required = false) String token,
            @RequestHeader(value = "X-Gitee-Signature", required = false) String signature,
            @RequestBody String body) {
        verify(token, signature, body);
        String sha = jsonString(body, "after");
        String project = projectName(body);
        if (sha == null || !sha.matches("[0-9a-f]{7,40}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "after 不是合法 sha");
        }
        String key = project + ":" + sha;
        if (!seen.add(key)) {
            return Map.of("duplicate", true, "sha", sha);
        }
        // 只触发已托管仓库的构建：按 repoUrl 反查 projectId，未注册仓库直接拒绝
        String projectId = projectIdOf(project);
        String runId;
        try {
            runId = engine.trigger(projectId, "verify", Map.of("gitSha", sha));
        } catch (RuntimeException e) {
            // 触发被前置校验拒绝（例如该提交里没有 ci/pipeline.yml）：放开去重键，
            // 让补齐定义后重推 / 重投这次 push 能重试，而不是永远被当成"重复"忽略。
            seen.remove(key);
            throw e;
        }
        return Map.of("runId", runId, "projectId", projectId, "gitSha", sha,
                "pipeline", "verify", "status", engine.status(runId));
    }

    /**
     * 事件旁路：issue / PR 事件经确定性 Fast Path 过滤后登记成工作区任务。
     *
     * <p>与 push 共用同一套鉴权；<b>不调用 LLM</b>：来源 + 事件类型 + 动作白名单命中才建任务，
     * 其余事件直接忽略。建任务之后由调度 Agent 在正常对话里判断「建工单 / 关联已有 / 忽略」，
     * 真正改代码仍要走子会话的权限与人工确认。
     */
    @PostMapping("/event")
    public Map<String, Object> event(
            @RequestHeader(value = "X-Gitee-Token", required = false) String token,
            @RequestHeader(value = "X-Gitee-Signature", required = false) String signature,
            @RequestHeader(value = "X-Gitee-Event", required = false) String eventHeader,
            @RequestBody String body) {
        verify(token, signature, body);
        String event = eventType(eventHeader, body);
        String action = jsonString(body, "action");
        if (!allowlisted(event, action)) {
            return Map.of("ignored", true, "event", event);
        }
        String projectId = projectIdOf(projectName(body));
        // issue 用 number，Gitee MR 可能是 iid，兜底 id；数字未加引号也要能取到
        String number = jsonScalar(body, "number");
        if (number == null) {
            number = jsonScalar(body, "iid");
        }
        if (number == null) {
            number = jsonScalar(body, "id");
        }
        String title = jsonString(body, "title");
        String sourceRef = projectId + ":" + event + ":" + (number == null ? "" : number)
                + ":" + action;
        // 内存集合挡热重复；任务表按 source_ref 兜底重启 / 多副本
        if (seenEvents.contains(sourceRef) || alreadyRecorded(projectId, sourceRef)) {
            return Map.of("duplicate", true, "sourceRef", sourceRef);
        }
        if (!seenEvents.add(sourceRef)) {
            return Map.of("duplicate", true, "sourceRef", sourceRef);
        }
        long now = System.currentTimeMillis();
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        WorkspaceTask task;
        try {
            task = tasks.saveTask(new WorkspaceTask("task-" + suffix, projectId, null,
                    "[" + event + "] #" + (number == null ? "" : number) + " "
                            + (title == null ? "" : title),
                    "由调度 Agent 判断：建工单 / 关联已有 / 忽略",
                    TaskStatus.TODO, 3, now, now, sourceRef, event));
        } catch (RuntimeException e) {
            seenEvents.remove(sourceRef);
            throw e;
        }
        return Map.of("taskId", task.id(), "projectId", projectId,
                "triggerType", event, "sourceRef", sourceRef);
    }

    /** 任务表里已有同一 source_ref 即视为已受理（重启 / 多副本仍幂等）。 */
    private boolean alreadyRecorded(String projectId, String sourceRef) {
        return tasks.findTasksByProject(projectId).stream()
                .anyMatch(t -> sourceRef.equals(t.sourceRef()));
    }

    /** 事件类型归一化：优先用 Gitee 请求头，退回 body 里的 hook_name。 */
    private static String eventType(String header, String body) {
        String raw = header == null || header.isBlank() ? jsonString(body, "hook_name") : header;
        return raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT)
                .replace(' ', '_').replace('-', '_');
    }

    /** Fast Path 白名单：只有 issue / PR 的 open / reopen 需要进入任务板。 */
    private static boolean allowlisted(String event, String action) {
        if (action == null) {
            return false;
        }
        String act = action.trim().toLowerCase(java.util.Locale.ROOT);
        boolean opening = "open".equals(act) || "reopen".equals(act);
        return opening && ("issue_hook".equals(event) || "merge_request_hook".equals(event));
    }

    private String projectIdOf(String project) {
        return projects.findAll().stream()
                .filter(p -> p.repoUrl() != null && p.repoUrl().endsWith("/" + project + ".git")
                        || project.equals(p.id()))
                .map(top.rongx.agent.domain.project.Project::id)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "仓库未托管：" + project + "（请先在 agent.projects 配置）"));
    }

    /** 最近构建历史（含重启前落盘的运行）。 */
    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        return engine.history().stream().limit(50)
                .map(h -> Map.<String, Object>of(
                        "runId", h.runId(), "projectId", h.projectId(),
                        "pipelineId", h.pipelineId(), "gitSha", h.gitSha(),
                        "status", h.status().name(), "createdAt", h.createdAt(),
                        "stages", h.stageCount()))
                .toList();
    }

    private void verify(String token, String signature, String body) {
        if (secret == null || secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "未配置 agent.webhook.gitee-secret，拒绝未鉴权 webhook");
        }
        if (token != null && constantEquals(token, secret)) {
            return;
        }
        if (signature != null && constantEquals(signature, hmac(body))) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "webhook 签名校验失败");
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "HMAC 初始化失败", e);
        }
    }

    private static boolean constantEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonString(String body, String key) {
        int i = body.indexOf("\"" + key + "\"");
        if (i < 0) {
            return null;
        }
        int colon = body.indexOf(':', i);
        int first = body.indexOf('"', colon + 1);
        if (first < 0) {
            return null;
        }
        int last = body.indexOf('"', first + 1);
        return last < 0 ? null : body.substring(first + 1, last);
    }

    /** 取标量字段：带引号的字符串或未加引号的数字都支持（issue 的 number 两种写法都有）。 */
    private static String jsonScalar(String body, String key) {
        int i = body.indexOf("\"" + key + "\"");
        if (i < 0) {
            return null;
        }
        int colon = body.indexOf(':', i);
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
            start++;
        }
        if (start >= body.length()) {
            return null;
        }
        if (body.charAt(start) == '"') {
            int end = body.indexOf('"', start + 1);
            return end < 0 ? null : body.substring(start + 1, end);
        }
        int end = start;
        while (end < body.length() && ",}] \n\r\t".indexOf(body.charAt(end)) < 0) {
            end++;
        }
        return end == start ? null : body.substring(start, end);
    }

    private static String projectName(String body) {
        String full = jsonString(body, "path_with_namespace");
        if (full == null || full.isBlank()) {
            // 不再静默兜底到某个项目：未知仓库的 push 必须被拒绝，防止误触发构建
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "webhook 缺少 project.path_with_namespace，无法确定项目");
        }
        int slash = full.lastIndexOf('/');
        return slash < 0 ? full : full.substring(slash + 1);
    }
}
