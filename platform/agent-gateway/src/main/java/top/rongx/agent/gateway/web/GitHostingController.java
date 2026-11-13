package top.rongx.agent.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.domain.settings.GitHostingAccount;
import top.rongx.agent.domain.settings.GitHostingAccountRepository;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.infra.git.GitWorktreeManager;

/**
 * 代码托管接入（GitHub / GitLab / 自定义 Git 服务）。
 *
 * <p>对照 Gitee 的「账号绑定 + 仓库浏览」做了通用化：
 * <ul>
 *   <li>凭据形状统一为 HTTPS + 个人访问令牌（PAT），这也是 Gitea / Drone / Jenkins
 *       等开源方案接入自建 Git 的通用做法；</li>
 *   <li>token 加密落 SQLite，接口只回掩码；</li>
 *   <li>「测试连接」走各平台用户接口；「测试仓库」用 {@code git ls-remote} 验证
 *       私有仓库可访问（自定义服务没有统一 API 时以它为准）。</li>
 * </ul>
 * 保存后的凭据会在「接入项目」clone / 同步时按 URL host 自动匹配。
 */
@RestController
@RequestMapping("/api/v1/settings/git-hosts")
public class GitHostingController {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = OutboundHttp.client(Duration.ofSeconds(10));
    private final GitHostingAccountRepository accounts;
    private final GitWorktreeManager git;
    private final CurrentUser currentUser;

    public GitHostingController(GitHostingAccountRepository accounts,
                                GitWorktreeManager git,
                                CurrentUser currentUser) {
        this.accounts = accounts;
        this.git = git;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<GitHostingAccount> list() {
        return accounts.findAll(requireRead());
    }

    @PostMapping
    public GitHostingAccount save(@RequestBody Map<String, Object> body) {
        String userId = requireWrite();
        return accounts.save(new GitHostingAccountRepository.GitHostingAccountView(
                str(body, "id"),
                str(body, "provider"),
                str(body, "host"),
                str(body, "username")), str(body, "token"), userId);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        accounts.delete(id, requireWrite());
        return Map.of("ok", true);
    }

    /** 测试账号令牌：调用平台用户接口，返回登录名。 */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String id) {
        String userId = requireWrite();
        GitHostingAccount account = accounts.find(id, userId).orElseThrow(
                () -> new IllegalArgumentException("代码托管账号不存在：" + id));
        String token = accounts.tokenOf(id, userId).orElse("");
        long start = System.nanoTime();
        try {
            String login = probeUser(account, token);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("login", login);
            out.put("latencyMs", (System.nanoTime() - start) / 1_000_000L);
            out.put("message", login.isBlank() ? "令牌有效" : "连接正常：" + login);
            return out;
        } catch (ResponseStatusException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("latencyMs", (System.nanoTime() - start) / 1_000_000L);
            out.put("message", e.getReason());
            return out;
        }
    }

    /**
     * 测试仓库可访问性（{@code git ls-remote}）。
     * body: {@code {repoUrl, accountId?}}；不传 accountId 时按 URL host 自动匹配。
     */
    @PostMapping("/test-repo")
    public Map<String, Object> testRepo(@RequestBody Map<String, Object> body) {
        String userId = requireWrite();
        String repoUrl = str(body, "repoUrl");
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("repoUrl 必填");
        }
        String accountId = str(body, "accountId");
        GitWorktreeManager.GitAuth auth = null;
        if (accountId != null && !accountId.isBlank()) {
            GitHostingAccount account = accounts.find(accountId, userId).orElseThrow(
                    () -> new IllegalArgumentException("代码托管账号不存在：" + accountId));
            String token = accounts.tokenOf(accountId, userId).orElse(null);
            auth = new GitWorktreeManager.GitAuth(account.username(), token);
        } else {
            auth = accounts.credentialFor(userId, repoUrl)
                    .map(c -> new GitWorktreeManager.GitAuth(c.username(), c.token()))
                    .orElse(null);
        }
        long start = System.nanoTime();
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<String> refs = git.lsRemote(repoUrl, auth);
            out.put("ok", true);
            out.put("latencyMs", (System.nanoTime() - start) / 1_000_000L);
            out.put("branchCount", refs.size());
            out.put("branches", refs.stream()
                    .map(line -> {
                        int tab = line.indexOf('\t');
                        return tab < 0 ? line : line.substring(tab + 1).replace("refs/heads/", "");
                    })
                    .limit(8).toList());
            out.put("message", refs.isEmpty() ? "仓库可访问，但没有分支" : "仓库可访问，共 " + refs.size() + " 个分支");
        } catch (RuntimeException e) {
            out.put("ok", false);
            out.put("latencyMs", (System.nanoTime() - start) / 1_000_000L);
            out.put("message", e.getMessage());
        }
        return out;
    }

    // ---------------------------------------------------------------- internals

    /** 各平台「当前令牌属于谁」：GitHub /user、GitLab /api/v4/user、自定义（Gitea 风格）/api/v1/user。 */
    private String probeUser(GitHostingAccount account, String token) {
        String provider = account.provider().toUpperCase(Locale.ROOT);
        String host = account.host();
        if ("CUSTOM".equals(provider)) {
            // 自定义服务没有统一用户接口：只确认凭据已保存，可访问性交给「测试仓库」
            return "";
        }
        String url;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "agent-platform");
        if ("GITHUB".equals(provider)) {
            url = "github.com".equalsIgnoreCase(host)
                    ? "https://api.github.com/user"
                    : "https://" + host + "/api/v3/user";
            headers.put("Authorization", "Bearer " + token);
        } else {
            url = "https://" + host + "/api/v4/user";
            headers.put("PRIVATE-TOKEN", token);
        }
        JsonNode node = fetch(url, headers);
        return node.path("login").asText(node.path("username").asText(""));
    }

    private JsonNode fetch(String url, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15));
            headers.forEach(builder::header);
            HttpResponse<String> resp = http.send(builder.GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                // 上游 401/403 是「令牌问题」，不能回 401（平台用它表示登录态失效）
                HttpStatus status = resp.statusCode() == 401 || resp.statusCode() == 403
                        ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
                throw new ResponseStatusException(status,
                        "平台接口返回 " + resp.statusCode() + "（令牌无效或权限不足）");
            }
            return mapper.readTree(resp.body());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "调用代码托管接口失败：" + describe(e));
        }
    }

    /** 超时等异常 message 可能为空，回退到类型名，避免前端只看到 null。 */
    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String requireRead() {
        String userId = currentUser.requireUserId();
        currentUser.checkPermission("settings:read");
        return userId;
    }

    private String requireWrite() {
        String userId = currentUser.requireUserId();
        currentUser.checkPermission("settings:write");
        return userId;
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
