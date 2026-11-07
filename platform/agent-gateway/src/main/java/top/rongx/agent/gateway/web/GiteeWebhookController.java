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
    private final String secret;
    private final java.util.Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public GiteeWebhookController(PipelineEngine engine,
                                  @Value("${agent.webhook.gitee-secret:}") String secret) {
        this.engine = engine;
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
        String runId = engine.trigger(project, "verify", Map.of("gitSha", sha));
        return Map.of("runId", runId, "projectId", project, "gitSha", sha,
                "pipeline", "verify", "status", engine.status(runId));
    }

    /** 最近构建历史（含重启前落盘的运行）。 */
    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        return engine.history().stream().limit(50)
                .map(h -> Map.<String, Object>of(
                        "runId", h.runId(), "projectId", h.projectId(),
                        "pipelineId", h.pipelineId(), "gitSha", h.gitSha(),
                        "status", h.status().name(), "stages", h.stageCount()))
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
            throw new IllegalStateException("HMAC 初始化失败", e);
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

    private static String projectName(String body) {
        String full = jsonString(body, "path_with_namespace");
        if (full == null || full.isBlank()) {
            return "RY";
        }
        int slash = full.lastIndexOf('/');
        return slash < 0 ? full : full.substring(slash + 1);
    }
}
