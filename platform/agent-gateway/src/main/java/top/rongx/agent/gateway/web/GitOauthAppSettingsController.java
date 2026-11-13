package top.rongx.agent.gateway.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import top.rongx.agent.domain.settings.GitOauthApp;
import top.rongx.agent.domain.settings.GitOauthAppRepository;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * Git 平台 OAuth 应用配置（设置页）。
 *
 * <p>可配置 Gitee / GitHub / GitLab 三个平台的 Client ID、Client Secret、
 * 回调地址与授权范围；保存后「前往授权」立即使用新配置，无需重启或改环境变量。
 *
 * <p>secret 只写不读：列表只返回掩码与是否已保存；留空保存表示沿用旧值。
 */
@RestController
@RequestMapping("/api/v1/settings/git-oauth-apps")
public class GitOauthAppSettingsController {

    private static final List<String> PROVIDERS = List.of("GITEE", "GITHUB", "GITLAB");

    private final GitOauthAppRepository apps;
    private final CurrentUser currentUser;

    public GitOauthAppSettingsController(GitOauthAppRepository apps, CurrentUser currentUser) {
        this.apps = apps;
        this.currentUser = currentUser;
    }

    /** 三个平台的配置；未保存过的返回默认回调地址与 scope。 */
    @GetMapping
    public List<GitOauthApp> list() {
        currentUser.requireUserId();
        currentUser.checkPermission("settings:read");
        List<GitOauthApp> out = new ArrayList<>();
        for (String provider : PROVIDERS) {
            out.add(apps.find(provider).orElseGet(() -> defaults(provider)));
        }
        return out;
    }

    /** 保存某个平台的应用配置；clientSecret 留空 = 不修改。 */
    @PutMapping("/{provider}")
    public GitOauthApp save(@PathVariable String provider, @RequestBody Map<String, Object> body) {
        currentUser.requireUserId();
        currentUser.checkPermission("settings:write");
        String key = normalize(provider);
        String clientId = str(body, "clientId");
        if (clientId == null || clientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Client ID 不能为空");
        }
        String redirectUri = str(body, "redirectUri");
        if (redirectUri == null || !redirectUri.trim().startsWith("http")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "回调地址必须是完整的 http(s) 地址");
        }
        boolean enabled = !(body.get("enabled") instanceof Boolean b) || b;
        GitOauthAppRepository.GitOauthAppView view = new GitOauthAppRepository.GitOauthAppView(
                key,
                clientId.trim(),
                redirectUri.trim(),
                str(body, "scopes"),
                enabled);
        return apps.save(view, str(body, "clientSecret"));
    }

    /** 清除某个平台的配置；之后回退到环境变量（若未配置则无法发起授权）。 */
    @DeleteMapping("/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String provider) {
        currentUser.requireUserId();
        currentUser.checkPermission("settings:write");
        apps.delete(normalize(provider));
    }

    // ---------------------------------------------------------------- internals

    private static GitOauthApp defaults(String provider) {
        return new GitOauthApp(provider, "", defaultRedirect(provider), defaultScopes(provider),
                false, false, null, 0);
    }

    /** 默认回调地址按当前请求的域名生成，部署后复制到平台后台即可。 */
    private static String defaultRedirect(String provider) {
        String path = switch (provider) {
            case "GITEE" -> "/api/v1/gitee/oauth/callback";
            case "GITHUB" -> "/api/v1/settings/git-hosts/oauth/github/callback";
            default -> "/api/v1/settings/git-hosts/oauth/gitlab/callback";
        };
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath().path(path).toUriString();
        } catch (RuntimeException e) {
            return "http://localhost:8090" + path;
        }
    }

    private static String defaultScopes(String provider) {
        return switch (provider) {
            case "GITEE" -> "user_info projects issues";
            case "GITHUB" -> "repo read:user";
            default -> "read_api read_repository read_user";
        };
    }

    private static String normalize(String provider) {
        String p = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        if (!PROVIDERS.contains(p)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的平台：" + provider + "（仅 GITEE / GITHUB / GITLAB）");
        }
        return p;
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
