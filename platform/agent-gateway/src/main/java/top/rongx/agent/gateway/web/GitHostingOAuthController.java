package top.rongx.agent.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.domain.settings.GitHostingAccountRepository;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * GitHub / GitLab OAuth 应用授权（与 Gitee 对齐的三段式流程）。
 *
 * <pre>
 * GET  /api/v1/settings/git-hosts/oauth/{github|gitlab}/start      → 返回授权页 URL
 * GET  /api/v1/settings/git-hosts/oauth/{github|gitlab}/callback   → code 换 token 并加密保存
 * DELETE /api/v1/settings/git-hosts/oauth/{github|gitlab}          → 解绑该平台账号
 * </pre>
 *
 * <p>与「私人令牌」方式共用同一张 {@code git_hosting_accounts} 表：授权成功后
 * 后续 clone / fetch 会按 URL host 自动匹配凭据，用户无需再粘贴 token。
 *
 * <p>安全：state 随机值防 CSRF，回调必须回传一致；token AES-GCM 加密落库，
 * 接口只回掩码。回调路径已在 Sa-Token 白名单中放行（浏览器跳转没有 Authorization 头）。
 */
@RestController
@RequestMapping("/api/v1/settings/git-hosts/oauth")
public class GitHostingOAuthController {

    private static final String GITHUB_AUTHORIZE = "https://github.com/login/oauth/authorize";
    private static final String GITHUB_TOKEN = "https://github.com/login/oauth/access_token";
    private static final String GITHUB_USER = "https://api.github.com/user";
    private static final String GITHUB_SCOPES = "repo read:user";

    private static final String GITLAB_AUTHORIZE = "https://gitlab.com/oauth/authorize";
    private static final String GITLAB_TOKEN = "https://gitlab.com/oauth/token";
    private static final String GITLAB_USER = "https://gitlab.com/api/v4/user";
    private static final String GITLAB_SCOPES = "read_api read_repository read_user";

    /** 进行中的授权：state → (userId, provider)。进程内即可，量小且短时有效。 */
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = OutboundHttp.client(Duration.ofSeconds(10));
    private final GitHostingAccountRepository accounts;
    private final CurrentUser currentUser;

    private final String githubClientId;
    private final String githubClientSecret;
    private final String githubRedirectUri;
    private final String gitlabClientId;
    private final String gitlabClientSecret;
    private final String gitlabRedirectUri;

    public GitHostingOAuthController(GitHostingAccountRepository accounts,
                                     CurrentUser currentUser,
                                     @Value("${agent.github.client-id:}") String githubClientId,
                                     @Value("${agent.github.client-secret:}") String githubClientSecret,
                                     @Value("${agent.github.redirect-uri:http://localhost:8090/api/v1/settings/git-hosts/oauth/github/callback}") String githubRedirectUri,
                                     @Value("${agent.gitlab.client-id:}") String gitlabClientId,
                                     @Value("${agent.gitlab.client-secret:}") String gitlabClientSecret,
                                     @Value("${agent.gitlab.redirect-uri:http://localhost:8090/api/v1/settings/git-hosts/oauth/gitlab/callback}") String gitlabRedirectUri) {
        this.accounts = accounts;
        this.currentUser = currentUser;
        this.githubClientId = githubClientId;
        this.githubClientSecret = githubClientSecret;
        this.githubRedirectUri = githubRedirectUri;
        this.gitlabClientId = gitlabClientId;
        this.gitlabClientSecret = gitlabClientSecret;
        this.gitlabRedirectUri = gitlabRedirectUri;
    }

    private record State(String userId, String provider) {
    }

    private record ProviderConfig(String provider, String host, String authorizeUrl,
                                  String tokenUrl, String userUrl, String scopes,
                                  String clientId, String clientSecret, String redirectUri) {
    }

    // ---------------------------------------------------------------- 授权

    /** 发起授权：返回授权页 URL（前端 window.open）。 */
    @GetMapping("/{provider}/start")
    public Map<String, Object> start(@PathVariable String provider) {
        String userId = currentUser.requireUserId();
        ProviderConfig cfg = config(provider);
        if (blank(cfg.clientId()) || blank(cfg.clientSecret())) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "平台未配置 " + cfg.provider() + " OAuth 应用（client-id / client-secret），"
                            + "请改用「新增接入」填写个人访问令牌");
        }
        String state = UUID.randomUUID().toString().replace("-", "");
        STATES.put(state, new State(userId, cfg.provider()));
        String url = cfg.authorizeUrl() + "?client_id=" + encode(cfg.clientId())
                + "&redirect_uri=" + encode(cfg.redirectUri())
                + "&response_type=code&scope=" + encode(cfg.scopes())
                + "&state=" + state;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authorizeUrl", url);
        out.put("state", state);
        out.put("provider", cfg.provider());
        return out;
    }

    /** 回调：code 换 token → 加密保存为托管账号。 */
    @GetMapping("/{provider}/callback")
    public Map<String, Object> callback(@PathVariable String provider,
                                        @RequestParam String code,
                                        @RequestParam String state) {
        State pending = STATES.remove(state);
        if (pending == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "state 无效或已过期（可能不是你发起的授权）");
        }
        ProviderConfig cfg = config(provider);
        if (!cfg.provider().equalsIgnoreCase(pending.provider())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "授权来源与回调平台不一致");
        }
        String token = exchangeToken(cfg, code);
        String login = fetchLogin(cfg, token);
        var account = accounts.save(new GitHostingAccountRepository.GitHostingAccountView(
                null, cfg.provider(), cfg.host(), login), token, pending.userId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("provider", cfg.provider());
        out.put("login", login);
        out.put("accountId", account.id());
        out.put("note", "授权成功，请关闭此页回到平台");
        return out;
    }

    /** 解绑：删除该平台下当前用户的全部授权账号。 */
    @DeleteMapping("/{provider}")
    public Map<String, Object> unbind(@PathVariable String provider) {
        String userId = currentUser.requireUserId();
        currentUser.checkPermission("settings:write");
        String normalized = config(provider).provider();
        int removed = 0;
        for (var account : accounts.findAll(userId)) {
            if (normalized.equalsIgnoreCase(account.provider())) {
                accounts.delete(account.id(), userId);
                removed++;
            }
        }
        return Map.of("ok", removed > 0, "removed", removed);
    }

    // ---------------------------------------------------------------- internals

    private ProviderConfig config(String provider) {
        String key = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "github" -> new ProviderConfig("GITHUB", "github.com", GITHUB_AUTHORIZE,
                    GITHUB_TOKEN, GITHUB_USER, GITHUB_SCOPES,
                    githubClientId, githubClientSecret, githubRedirectUri);
            case "gitlab" -> new ProviderConfig("GITLAB", "gitlab.com", GITLAB_AUTHORIZE,
                    GITLAB_TOKEN, GITLAB_USER, GITLAB_SCOPES,
                    gitlabClientId, gitlabClientSecret, gitlabRedirectUri);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的 OAuth 平台：" + provider + "（仅 github / gitlab）");
        };
    }

    private String exchangeToken(ProviderConfig cfg, String code) {
        String body = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&client_id=" + encode(cfg.clientId())
                + "&client_secret=" + encode(cfg.clientSecret())
                + "&redirect_uri=" + encode(cfg.redirectUri());
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.tokenUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header("User-Agent", "agent-platform")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        cfg.provider() + " 换取 token 失败（HTTP " + resp.statusCode() + "）");
            }
            JsonNode node = mapper.readTree(resp.body());
            String token = node.path("access_token").asText("");
            if (token.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        cfg.provider() + " 授权失败：未返回 access_token");
            }
            return token;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "调用 " + cfg.provider() + " OAuth 失败：" + describe(e));
        }
    }

    private String fetchLogin(ProviderConfig cfg, String token) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.userUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .header("User-Agent", "agent-platform")
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        cfg.provider() + " 用户接口返回 " + resp.statusCode() + "（令牌可能权限不足）");
            }
            JsonNode node = mapper.readTree(resp.body());
            // GitHub 用 login，GitLab 用 username
            return node.path("login").asText(node.path("username").asText(""));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "读取 " + cfg.provider() + " 用户信息失败：" + describe(e));
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
