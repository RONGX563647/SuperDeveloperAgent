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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.gateway.gitee.GiteeAccountStore;
import top.rongx.agent.gateway.security.PasswordVerifier;

/**
 * Gitee 账号认证 + 仓库浏览。
 *
 * <p>两条认证通道：
 * <ol>
 *   <li><b>OAuth 网页授权</b>（推荐）：{@code GET /gitee/oauth/start} → 引导用户到 Gitee 授权页 →
 *       Gitee 回跳 {@code GET /gitee/oauth/callback?code=...} → 平台换 token 并加密绑定；</li>
 *   <li><b>Personal Access Token</b>：用户在 Gitee 生成 token 直接贴入绑定。</li>
 * </ol>
 * 绑定后所有仓库浏览自动使用绑定 token（无需每次传）。
 *
 * <p>token 安全：绑定后加密落库，接口永不返回明文（只回脱敏掩码）。
 */
@RestController
@RequestMapping("/api/v1/gitee")
public class GiteeBrowserController {

    private static final String API_BASE = "https://gitee.com/api/v5";
    private static final String OAUTH_AUTHORIZE = "https://gitee.com/oauth/authorize";
    private static final String OAUTH_TOKEN = "https://gitee.com/oauth/token";
    /** OAuth scope：仓库读写 + 用户信息 + issues（webhook 预留）。 */
    private static final String OAUTH_SCOPES = "user_info projects issues";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final GiteeAccountStore accounts;
    private final PasswordVerifier passwordVerifier;
    private final top.rongx.agent.gateway.auth.CurrentUser currentUser;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    /** 平台侧兜底 token（可配置；绑定与用户传入都缺省时使用）。 */
    private final String platformToken;

    public GiteeBrowserController(GiteeAccountStore accounts,
                                  PasswordVerifier passwordVerifier,
                                  top.rongx.agent.gateway.auth.CurrentUser currentUser,
                                  @Value("${agent.gitee.client-id:}") String clientId,
                                  @Value("${agent.gitee.client-secret:}") String clientSecret,
                                  @Value("${agent.gitee.redirect-uri:http://localhost:8090/api/v1/gitee/oauth/callback}") String redirectUri,
                                  @Value("${agent.intake.gitee-token:}") String platformToken) {
        this.accounts = accounts;
        this.passwordVerifier = passwordVerifier;
        this.currentUser = currentUser;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.platformToken = platformToken == null ? "" : platformToken;
    }

    /** Gitee 仓库条目。 */
    public record RepoInfo(String fullName, String cloneUrl, boolean privateRepo,
                           String description, String updatedAt) {
    }

    // ---------------------------------------------------------------- OAuth

    /**
     * 发起 OAuth：返回 Gitee 授权页 URL（前端跳转）。
     * state 防 CSRF：随机值，回调时必须回传一致。
     */
    @GetMapping("/oauth/start")
    public Map<String, Object> oauthStart() {
        String userId = currentUser.requireUserId();
        if (clientId == null || clientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "平台未配置 agent.gitee.client-id / client-secret，请使用私人令牌方式绑定");
        }
        String state = newUniqueId();
        OAUTH_STATES.put(state, userId);
        String url = OAUTH_AUTHORIZE + "?client_id=" + clientId
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&response_type=code&scope=" + URLEncoder.encode(OAUTH_SCOPES, StandardCharsets.UTF_8)
                + "&state=" + state;
        return Map.of("authorizeUrl", url, "state", state);
    }

    /** OAuth 回调：code 换 token → 加密绑定 → 跳回前端。 */
    @GetMapping("/oauth/callback")
    public Map<String, Object> oauthCallback(@RequestParam String code,
                                             @RequestParam String state) {
        String userId = OAUTH_STATES.remove(state);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "state 无效或已过期（可能不是你发起的授权）");
        }
        String body = "grant_type=authorization_code"
                + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + "&client_id=" + clientId
                + "&client_secret=" + clientSecret
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
        JsonNode tokenResp = postForm(OAUTH_TOKEN, body);
        String accessToken = tokenResp.path("access_token").asText("");
        if (accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Gitee 授权失败：未返回 access_token");
        }
        JsonNode giteeUser = fetchJson(API_BASE + "/user?access_token=" + accessToken);
        String login = giteeUser.path("login").asText("");
        accounts.bind(userId, login, accessToken, tokenResp.path("scope").asText(""));
        return Map.of("ok", true, "userId", userId, "giteeLogin", login,
                "note", "绑定成功，请关闭此页回到平台");
    }

    // ---------------------------------------------------------------- PAT 绑定

    /** 用私人令牌绑定（登录态用户；token 加密落库）。 */
    @PostMapping("/bind")
    public Map<String, Object> bind(@RequestBody Map<String, String> body) {
        String userId = currentUser.requireUserId();
        String token = body.getOrDefault("token", "").trim();
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "token 不能为空");
        }
        JsonNode giteeUser = fetchJson(API_BASE + "/user?access_token=" + token);
        String login = giteeUser.path("login").asText("");
        if (login.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "token 无效");
        }
        accounts.bind(userId, login, token, "projects");
        return Map.of("ok", true, "giteeLogin", login);
    }

    /** 解绑。 */
    @DeleteMapping("/bind")
    public Map<String, Object> unbind() {
        String userId = currentUser.requireUserId();
        boolean removed = accounts.unbind(userId);
        return Map.of("ok", removed);
    }

    /** 绑定状态（token 脱敏）。 */
    @GetMapping("/bind/status")
    public Map<String, Object> bindStatus() {
        String userId = currentUser.requireUserId();
        var binding = accounts.find(userId);
        if (binding.isPresent()) {
            var b = binding.get();
            return Map.of("bound", true, "giteeLogin", b.giteeLogin(),
                    "tokenMasked", b.tokenMasked(), "boundAt", b.boundAt());
        }
        return Map.of("bound", false);
    }

    // ---------------------------------------------------------------- 仓库浏览

    /**
     * 列出 token 属主的仓库。token 解析优先级：绑定 token > 请求参数 token > 平台兜底。
     * {@code GET /gitee/repos?scope=personal|all}
     */
    @GetMapping("/repos")
    public List<RepoInfo> repos(
                                @RequestParam(required = false) String token,
                                @RequestParam(defaultValue = "personal") String scope) {
        String userId = currentUser.requireUserId();
        String effective = resolveToken(userId, token);
        List<RepoInfo> out = new ArrayList<>();
        int page = 1;
        while (page <= 20) {
            String url = API_BASE + "/user/repos?access_token=" + effective
                    + "&per_page=20&page=" + page + "&sort=updated&direction=desc"
                    + "&visibility=all&affiliation="
                    + (scope.equals("all") ? "owner,collaborator,organization_enterprise" : "owner");
            JsonNode batch = fetchJson(url);
            if (batch == null || !batch.isArray() || batch.isEmpty()) {
                break;
            }
            for (JsonNode r : batch) {
                out.add(new RepoInfo(
                        r.path("full_name").asText(""),
                        r.path("html_url").asText(""),
                        r.path("private").asBoolean(false),
                        r.path("description").asText(""),
                        r.path("updated_at").asText("")));
            }
            if (batch.size() < 20) {
                break;
            }
            page++;
        }
        return out;
    }

    /** token 解析：绑定态优先（用户无感），其次请求参数，最后平台兜底。 */
    private String resolveToken(String userId, String requestToken) {
        if (requestToken != null && !requestToken.isBlank()) {
            return requestToken.trim();
        }
        return accounts.tokenOf(userId)
                .orElseGet(() -> {
                    if (platformToken.isBlank()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "尚未绑定 Gitee 账号：请先在「项目接入」里登录 Gitee（或贴入私人令牌）");
                    }
                    return platformToken;
                });
    }

    // ---------------------------------------------------------------- internals

    /** OAuth 进行中的 state → userId（内存；量小无需持久化）。 */
    private static final java.util.Map<String, String> OAUTH_STATES = new java.util.concurrent.ConcurrentHashMap<>();

    private String newUniqueId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private JsonNode fetchJson(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Gitee API 返回 " + resp.statusCode() + "（token 无效或网络不可达）");
            }
            return mapper.readTree(resp.body());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "调用 Gitee API 失败：" + e.getMessage());
        }
    }

    private JsonNode postForm(String url, String formBody) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Gitee 换取 token 失败（HTTP " + resp.statusCode() + "）：" + resp.body());
            }
            return mapper.readTree(resp.body());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "调用 Gitee OAuth 失败：" + e.getMessage());
        }
    }
}
