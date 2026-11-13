package top.rongx.agent.gateway.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import top.rongx.agent.domain.gitissue.GitIssueIntegration;
import top.rongx.agent.domain.gitissue.GitIssueIntegrationRepository;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * 「设置 → 高级设置 → Git 应用授权」里的 Issue 同步配置。
 *
 * <p>和 OAuth 应用配置放在一起：每个平台一张卡片，配置启用开关、仓库白名单、
 * Webhook Secret、自动建任务、同步方向与冲突策略；Secret 只写不读。
 */
@RestController
@RequestMapping("/api/v1/settings/git-integration")
public class GitIssueIntegrationController {

    private static final List<String> PROVIDERS = List.of("GITEE", "GITHUB", "GITLAB");

    private final GitIssueIntegrationRepository integrations;
    private final CurrentUser currentUser;

    public GitIssueIntegrationController(GitIssueIntegrationRepository integrations,
                                         CurrentUser currentUser) {
        this.integrations = integrations;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<IntegrationView> list() {
        currentUser.checkPermission("settings:read");
        List<IntegrationView> views = new ArrayList<>();
        for (String provider : PROVIDERS) {
            views.add(integrations.find(provider)
                    .map(saved -> view(provider, saved))
                    .orElseGet(() -> view(provider, null)));
        }
        return views;
    }

    @PutMapping("/{provider}")
    public IntegrationView save(@PathVariable String provider,
                                @RequestBody Map<String, Object> body) {
        currentUser.checkPermission("settings:write");
        String p = normalize(provider);
        GitIssueIntegrationRepository.Draft draft = new GitIssueIntegrationRepository.Draft(
                p,
                bool(body, "enabled"),
                str(body, "repoWhitelist"),
                bool(body, "autoCreateTask"),
                str(body, "syncDirection"),
                str(body, "conflictPolicy"));
        String secret = str(body, "webhookSecret");
        GitIssueIntegration saved = integrations.save(draft, secret,
                currentUser.requireUserId());
        return view(p, saved);
    }

    @DeleteMapping("/{provider}")
    public Map<String, Object> delete(@PathVariable String provider) {
        currentUser.checkPermission("settings:write");
        integrations.delete(normalize(provider));
        return Map.of("deleted", true);
    }

    private IntegrationView view(String provider, GitIssueIntegration saved) {
        GitIssueIntegration value = saved != null ? saved
                : new GitIssueIntegration(provider, "", false, "", false, null,
                false, "BIDIRECTIONAL", "GIT_WINS", 0);
        String webhookUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/webhooks/git/" + provider.toLowerCase() + "/issues")
                .toUriString();
        return new IntegrationView(value.provider(), value.enabled(), value.repoWhitelist(),
                value.hasWebhookSecret(), value.webhookSecretMasked(), value.autoCreateTask(),
                value.syncDirection(), value.conflictPolicy(), value.updatedAt(), webhookUrl);
    }

    private static String normalize(String provider) {
        String p = provider == null ? "" : provider.trim().toUpperCase();
        if (!PROVIDERS.contains(p)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "provider 只能是 GITEE / GITHUB / GITLAB");
        }
        return p;
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean bool(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(value));
    }

    public record IntegrationView(
            String provider,
            boolean enabled,
            String repoWhitelist,
            boolean hasWebhookSecret,
            String webhookSecretMasked,
            boolean autoCreateTask,
            String syncDirection,
            String conflictPolicy,
            long updatedAt,
            String webhookUrl) {
    }
}
