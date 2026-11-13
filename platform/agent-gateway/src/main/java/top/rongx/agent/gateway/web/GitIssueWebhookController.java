package top.rongx.agent.gateway.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.domain.gitissue.GitIssueIntegrationRepository;
import top.rongx.agent.gateway.gitissue.GitIssueSyncService;

/**
 * Git Issue Webhook 入口（Gitee / GitHub / GitLab 各一套签名规则）。
 *
 * <p>回调只做三件事：按平台验签 → 事件去重 → 入队后立即返回 200；
 * 解析、映射、唤醒 Agent 全部在异步线程完成，绝不阻塞 Git 的投递。
 */
@RestController
@RequestMapping("/api/v1/webhooks/git")
public class GitIssueWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitIssueWebhookController.class);

    private final GitIssueSyncService syncService;
    private final GitIssueIntegrationRepository integrations;

    public GitIssueWebhookController(GitIssueSyncService syncService,
                                     GitIssueIntegrationRepository integrations) {
        this.syncService = syncService;
        this.integrations = integrations;
    }

    @PostMapping("/{provider}/issues")
    public Map<String, Object> receive(
            @PathVariable String provider,
            @RequestBody String body,
            @RequestHeader(value = "X-GitHub-Event", required = false) String githubEvent,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String githubDelivery,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String githubSignature,
            @RequestHeader(value = "X-Gitee-Event", required = false) String giteeEvent,
            @RequestHeader(value = "X-Gitee-Token", required = false) String giteeToken,
            @RequestHeader(value = "X-Gitee-Timestamp", required = false) String giteeTimestamp,
            @RequestHeader(value = "X-Gitee-Signature", required = false) String giteeSignature,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String gitlabEvent,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String gitlabToken,
            @RequestHeader(value = "X-Gitlab-Event-UUID", required = false) String gitlabUuid) {
        String p = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        String secret = integrations.secretOf(p).orElse("");
        if (secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "未配置 " + p + " Issue 同步的 Webhook Secret，拒绝回调");
        }
        String eventType;
        String eventKey;
        switch (p) {
            case "GITHUB" -> {
                verifyGithub(secret, body, githubSignature);
                eventType = githubEvent == null ? "" : githubEvent;
                eventKey = githubDelivery == null || githubDelivery.isBlank()
                        ? sha256(body) : githubDelivery;
            }
            case "GITEE" -> {
                verifyGitee(secret, body, giteeToken, giteeTimestamp, giteeSignature);
                eventType = giteeEvent == null ? "" : giteeEvent;
                eventKey = sha256(body);
            }
            case "GITLAB" -> {
                if (gitlabToken == null || !constantEquals(gitlabToken, secret)) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "GitLab X-Gitlab-Token 校验失败");
                }
                eventType = gitlabEvent == null ? "" : gitlabEvent;
                eventKey = gitlabUuid == null || gitlabUuid.isBlank()
                        ? sha256(body) : gitlabUuid;
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "provider 只能是 GITEE / GITHUB / GITLAB");
        }
        if (!isIssueEvent(p, eventType)) {
            return Map.of("ok", true, "ignored", true, "event", eventType);
        }
        boolean accepted = syncService.submit(p, eventKey, eventType, body);
        if (!accepted) {
            log.debug("重复的 Git Issue 事件已忽略：{} {}", p, eventKey);
            return Map.of("ok", true, "duplicate", true);
        }
        return Map.of("ok", true, "accepted", true);
    }

    private static boolean isIssueEvent(String provider, String eventType) {
        String type = eventType == null ? "" : eventType.trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "GITHUB" -> type.equals("issues") || type.equals("issue_comment");
            case "GITEE" -> type.contains("issue");
            case "GITLAB" -> type.equals("issue hook") || type.equals("note hook");
            default -> false;
        };
    }

    private static void verifyGithub(String secret, String body, String signature) {
        if (signature == null || !signature.startsWith("sha256=")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "缺少 X-Hub-Signature-256");
        }
        String expected = "sha256=" + hmacHex(secret, body);
        if (!constantEquals(signature, expected)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "GitHub 签名校验失败");
        }
    }

    private static void verifyGitee(String secret, String body, String token, String timestamp,
                                    String signature) {
        if (token != null && constantEquals(token, secret)) {
            return;
        }
        if (signature == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "缺少 X-Gitee-Token / X-Gitee-Signature");
        }
        // 兼容两种常见写法：HMAC(body) 的 hex/base64，以及 Gitee 官方的
        // Base64(HMAC-SHA256(key=secret, data=timestamp + "\n" + secret))。
        if (constantEquals(signature, hmacHex(secret, body))
                || constantEquals(signature, hmacBase64(secret, body))) {
            return;
        }
        if (timestamp != null
                && constantEquals(signature,
                hmacBase64(secret, timestamp + "\n" + secret))) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Gitee 签名校验失败");
    }

    private static String hmacHex(String secret, String data) {
        return HexFormat.of().formatHex(hmac(secret, data));
    }

    private static String hmacBase64(String secret, String data) {
        return Base64.getEncoder().encodeToString(hmac(secret, data));
    }

    private static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 初始化失败", e);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
