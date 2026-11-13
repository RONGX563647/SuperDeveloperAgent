package top.rongx.agent.gateway.gitissue;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.settings.GitHostingAccount;
import top.rongx.agent.domain.settings.GitHostingAccountRepository;
import top.rongx.agent.gateway.gitee.GiteeAccountStore;

/**
 * Gitee / GitHub / GitLab Issue 开放接口的最小封装（零新增依赖，JDK HttpClient）。
 *
 * <p>凭据按用户解析：Gitee 取账号绑定，GitHub / GitLab 取代码托管账号；
 * 未绑定账号时抛 {@link IllegalStateException}，由调用方降级为「本地任务 + 同步失败」。
 */
@Component
public class GitIssueRemoteClient {

    private static final Logger log = LoggerFactory.getLogger(GitIssueRemoteClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final GiteeAccountStore gitee;
    private final GitHostingAccountRepository hosts;

    public GitIssueRemoteClient(GiteeAccountStore gitee, GitHostingAccountRepository hosts) {
        this.gitee = gitee;
        this.hosts = hosts;
    }

    /** 解析某用户对指定平台/仓库可用的凭据。 */
    public Optional<Credentials> credentials(String userId, String provider, String repo) {
        String p = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        String r = cleanRepo(repo);
        if (p.isBlank() || r.isBlank()) {
            return Optional.empty();
        }
        if ("GITEE".equals(p)) {
            return gitee.tokenOf(userId).map(token -> new Credentials(p, r, "gitee.com", token));
        }
        List<GitHostingAccount> matches = hosts.findAll(userId).stream()
                .filter(a -> p.equalsIgnoreCase(a.provider()))
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        GitHostingAccount account = matches.get(0);
        return hosts.tokenOf(account.id(), userId)
                .map(token -> new Credentials(p, r, account.host(), token));
    }

    /** 创建 Issue，返回远端编号/地址/状态。 */
    public RemoteIssue create(Credentials c, String title, String body, String labels,
                              String kind) {
        return switch (c.provider()) {
            case "GITEE" -> createGitee(c, title, body, labels, kind);
            case "GITHUB" -> createGithub(c, title, body, labels, kind);
            case "GITLAB" -> createGitlab(c, title, body, labels);
            default -> throw new IllegalStateException("不支持的平台：" + c.provider());
        };
    }

    /** 更新标题/描述/标签/状态。 */
    public void update(Credentials c, String number, String title, String body, String state,
                       String labels, String kind) {
        switch (c.provider()) {
            case "GITEE" -> updateGitee(c, number, title, body, state, labels, kind);
            case "GITHUB" -> updateGithub(c, number, title, body, state, labels, kind);
            case "GITLAB" -> updateGitlab(c, number, title, body, state, labels);
            default -> throw new IllegalStateException("不支持的平台：" + c.provider());
        }
    }

    /** 追加评论。 */
    public void comment(Credentials c, String number, String body) {
        switch (c.provider()) {
            case "GITEE" -> send("POST",
                    "https://gitee.com/api/v5/repos/" + c.repo() + "/issues/" + number
                            + "/comments?access_token=" + enc(c.token()),
                    headers(), jsonBody(Map.of("body", body)));
            case "GITHUB" -> send("POST",
                    "https://api.github.com/repos/" + c.repo() + "/issues/" + number + "/comments",
                    githubHeaders(c.token()), jsonBody(Map.of("body", body)));
            case "GITLAB" -> send("POST",
                    gitlabBase(c) + "/projects/" + enc(c.repo()) + "/issues/" + number + "/notes",
                    gitlabHeaders(c.token()), jsonBody(Map.of("body", body)));
            default -> throw new IllegalStateException("不支持的平台：" + c.provider());
        }
    }

    // ---------------------------------------------------------------- 各平台实现

    private RemoteIssue createGitee(Credentials c, String title, String body, String labels,
                                    String kind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        if (labels != null && !labels.isBlank()) {
            payload.put("labels", labels);
        }
        // 企业版可用「缺陷 / 需求」原生类型；个人仓库不支持时去掉重试
        payload.put("issue_type", giteeType(kind));
        String url = "https://gitee.com/api/v5/repos/" + c.repo() + "/issues?access_token="
                + enc(c.token());
        try {
            return parseIssue(send("POST", url, headers(), jsonBody(payload)));
        } catch (IllegalStateException e) {
            if (!typeUnsupported(e)) {
                throw e;
            }
            log.info("Gitee 仓库不支持自定义任务类型，改用标签区分（{}）", c.repo());
            payload.remove("issue_type");
            return parseIssue(send("POST", url, headers(), jsonBody(payload)));
        }
    }

    private void updateGitee(Credentials c, String number, String title, String body, String state,
                             String labels, String kind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        payload.put("state", "CLOSED".equalsIgnoreCase(state) ? "closed" : "open");
        if (labels != null && !labels.isBlank()) {
            payload.put("labels", labels);
        }
        payload.put("issue_type", giteeType(kind));
        String url = "https://gitee.com/api/v5/repos/" + c.repo() + "/issues/" + number
                + "?access_token=" + enc(c.token());
        try {
            send("PATCH", url, headers(), jsonBody(payload));
        } catch (IllegalStateException e) {
            if (!typeUnsupported(e)) {
                throw e;
            }
            log.info("Gitee 仓库不支持自定义任务类型，改用标签区分（{}）", c.repo());
            payload.remove("issue_type");
            send("PATCH", url, headers(), jsonBody(payload));
        }
    }

    private RemoteIssue createGithub(Credentials c, String title, String body, String labels,
                                     String kind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        List<String> list = splitLabels(labels);
        if (!list.isEmpty()) {
            payload.put("labels", list);
        }
        payload.put("type", githubType(kind));
        String url = "https://api.github.com/repos/" + c.repo() + "/issues";
        try {
            return parseIssue(send("POST", url, githubHeaders(c.token()), jsonBody(payload)));
        } catch (IllegalStateException e) {
            if (!typeUnsupported(e)) {
                throw e;
            }
            // 个人仓库 / 未配置 Issue 类型：去掉 type 重试，labels 仍能区分
            log.info("GitHub 未启用 Issue 类型，改用标签区分（{}）", c.repo());
            payload.remove("type");
            return parseIssue(send("POST", url, githubHeaders(c.token()), jsonBody(payload)));
        }
    }

    private void updateGithub(Credentials c, String number, String title, String body, String state,
                              String labels, String kind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        payload.put("state", "CLOSED".equalsIgnoreCase(state) ? "closed" : "open");
        List<String> list = splitLabels(labels);
        if (!list.isEmpty()) {
            payload.put("labels", list);
        }
        payload.put("type", githubType(kind));
        String url = "https://api.github.com/repos/" + c.repo() + "/issues/" + number;
        try {
            send("PATCH", url, githubHeaders(c.token()), jsonBody(payload));
        } catch (IllegalStateException e) {
            if (!typeUnsupported(e)) {
                throw e;
            }
            log.info("GitHub 未启用 Issue 类型，改用标签区分（{}）", c.repo());
            payload.remove("type");
            send("PATCH", url, githubHeaders(c.token()), jsonBody(payload));
        }
    }

    private RemoteIssue createGitlab(Credentials c, String title, String body, String labels) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("description", body);
        if (labels != null && !labels.isBlank()) {
            payload.put("labels", labels);
        }
        String resp = send("POST", gitlabBase(c) + "/projects/" + enc(c.repo()) + "/issues",
                gitlabHeaders(c.token()), jsonBody(payload));
        return parseIssue(resp);
    }

    private void updateGitlab(Credentials c, String number, String title, String body, String state,
                              String labels) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("description", body);
        if ("CLOSED".equalsIgnoreCase(state)) {
            payload.put("state_event", "close");
        } else {
            payload.put("state_event", "reopen");
        }
        if (labels != null && !labels.isBlank()) {
            payload.put("labels", labels);
        }
        send("PUT", gitlabBase(c) + "/projects/" + enc(c.repo()) + "/issues/" + number,
                gitlabHeaders(c.token()), jsonBody(payload));
    }

    // ---------------------------------------------------------------- PR / MR

    /** 仓库默认分支（读取远端仓库信息）；失败时回退 main，避免自动修复停在分支查询。 */
    public String defaultBranch(Credentials c) {
        try {
            String resp = switch (c.provider()) {
                case "GITEE" -> send("GET", "https://gitee.com/api/v5/repos/" + c.repo()
                        + "?access_token=" + enc(c.token()), headers(), null);
                case "GITHUB" -> send("GET", "https://api.github.com/repos/" + c.repo(),
                        githubHeaders(c.token()), null);
                case "GITLAB" -> send("GET", gitlabBase(c) + "/projects/" + enc(c.repo()),
                        gitlabHeaders(c.token()), null);
                default -> "";
            };
            if (resp.isBlank()) {
                return "main";
            }
            String branch = json.readTree(resp).path("default_branch").asText("");
            return branch.isBlank() ? "main" : branch;
        } catch (Exception e) {
            log.warn("读取默认分支失败（{}/{}）：{}", c.provider(), c.repo(), e.getMessage());
            return "main";
        }
    }

    /**
     * 按源分支查找已打开的 PR / MR，返回网页链接。
     *
     * <p>创建 PR 前先查一次、创建失败后再查一次，保证「响应超时/用户重试」时不会重复建 PR。
     * 查询失败（网络/权限/字段缺失）返回 empty，不阻塞正常的创建流程。
     */
    public Optional<String> findPullRequest(Credentials c, String head) {
        if (c == null || head == null || head.isBlank()) {
            return Optional.empty();
        }
        try {
            String resp;
            switch (c.provider()) {
                case "GITEE" -> resp = send("GET", "https://gitee.com/api/v5/repos/" + c.repo()
                        + "/pulls?state=open&head=" + enc(head) + "&access_token="
                        + enc(c.token()), headers(), null);
                case "GITHUB" -> resp = send("GET", "https://api.github.com/repos/" + c.repo()
                        + "/pulls?state=open&head=" + enc(ownerOf(c.repo()) + ":" + head)
                        + "&per_page=100", githubHeaders(c.token()), null);
                case "GITLAB" -> resp = send("GET", gitlabBase(c) + "/projects/" + enc(c.repo())
                        + "/merge_requests?state=opened&source_branch=" + enc(head)
                        + "&per_page=100", gitlabHeaders(c.token()), null);
                default -> {
                    return Optional.empty();
                }
            }
            JsonNode array = json.readTree(resp);
            if (array == null || !array.isArray()) {
                return Optional.empty();
            }
            boolean gitlab = "GITLAB".equals(c.provider());
            for (JsonNode node : array) {
                String branch = gitlab
                        ? node.path("source_branch").asText("")
                        : node.path("head").path("ref").asText("");
                if (!head.equals(branch)) {
                    continue;
                }
                String url = gitlab
                        ? node.path("web_url").asText("")
                        : node.path("html_url").asText("");
                if (!url.isBlank()) {
                    return Optional.of(url);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("查询已有 PR 失败（{}/{} head={}）：{}", c.provider(), c.repo(), head,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 查询 PR/MR 的远端状态：OPEN / MERGED / CLOSED / UNKNOWN。
     *
     * <p>合并追踪器据此把「等待合并」推进为「已完成」。任何解析/网络失败都返回 UNKNOWN，
     * 由下一轮轮询重试，绝不让运行状态倒退。
     */
    public String pullRequestState(Credentials c, String prUrl) {
        if (c == null || prUrl == null || prUrl.isBlank()) {
            return "UNKNOWN";
        }
        try {
            switch (c.provider()) {
                case "GITHUB" -> {
                    String path = prUrl.replaceFirst("^https?://[^/]+/", "")
                            .replace("/pull/", "/pulls/");
                    JsonNode node = json.readTree(send("GET",
                            "https://api.github.com/repos/" + path,
                            githubHeaders(c.token()), null));
                    if (node.path("merged").asBoolean(false)) {
                        return "MERGED";
                    }
                    return "closed".equalsIgnoreCase(node.path("state").asText(""))
                            ? "CLOSED" : "OPEN";
                }
                case "GITLAB" -> {
                    String iid = trailingNumber(prUrl);
                    if (iid.isBlank()) {
                        return "UNKNOWN";
                    }
                    JsonNode node = json.readTree(send("GET", gitlabBase(c) + "/projects/"
                                    + enc(c.repo()) + "/merge_requests/" + iid,
                            gitlabHeaders(c.token()), null));
                    String state = node.path("state").asText("").toLowerCase(Locale.ROOT);
                    return switch (state) {
                        case "merged" -> "MERGED";
                        case "closed" -> "CLOSED";
                        case "opened", "open" -> "OPEN";
                        default -> "UNKNOWN";
                    };
                }
                case "GITEE" -> {
                    String number = trailingNumber(prUrl);
                    if (number.isBlank()) {
                        return "UNKNOWN";
                    }
                    JsonNode node = json.readTree(send("GET",
                            "https://gitee.com/api/v5/repos/" + c.repo() + "/pulls/" + number
                                    + "?access_token=" + enc(c.token()), headers(), null));
                    String state = node.path("state").asText("").toLowerCase(Locale.ROOT);
                    if ("merged".equals(state) || !node.path("merged_at").asText("").isBlank()) {
                        return "MERGED";
                    }
                    return "closed".equals(state) ? "CLOSED" : "OPEN";
                }
                default -> {
                    return "UNKNOWN";
                }
            }
        } catch (Exception e) {
            log.warn("查询 PR 状态失败（{}/{} url={}）：{}", c.provider(), c.repo(), prUrl,
                    e.getMessage());
            return "UNKNOWN";
        }
    }

    /** 取 URL 末尾的数字（GitLab MR iid / Gitee PR 编号）。 */
    private static String trailingNumber(String url) {
        int end = url.length();
        while (end > 0 && !Character.isDigit(url.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isDigit(url.charAt(start - 1))) {
            start--;
        }
        return end == start ? "" : url.substring(start, end);
    }

    /**
     * 创建 PR / MR，返回网页链接。
     *
     * <p>只创建、不合并：最终合并必须由人工完成（平台永不自动 merge）。
     */
    public String openPullRequest(Credentials c, String head, String base, String title,
                                  String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String url;
        switch (c.provider()) {
            case "GITEE" -> {
                payload.put("title", title);
                payload.put("head", head);
                payload.put("base", base);
                payload.put("body", body);
                url = "https://gitee.com/api/v5/repos/" + c.repo() + "/pulls?access_token="
                        + enc(c.token());
            }
            case "GITHUB" -> {
                payload.put("title", title);
                payload.put("head", head);
                payload.put("base", base);
                payload.put("body", body);
                url = "https://api.github.com/repos/" + c.repo() + "/pulls";
            }
            case "GITLAB" -> {
                payload.put("title", title);
                payload.put("source_branch", head);
                payload.put("target_branch", base);
                payload.put("description", body);
                url = gitlabBase(c) + "/projects/" + enc(c.repo()) + "/merge_requests";
            }
            default -> throw new IllegalStateException("不支持的平台：" + c.provider());
        }
        String resp = "GITHUB".equals(c.provider())
                ? send("POST", url, githubHeaders(c.token()), jsonBody(payload))
                : "GITLAB".equals(c.provider())
                ? send("POST", url, gitlabHeaders(c.token()), jsonBody(payload))
                : send("POST", url, headers(), jsonBody(payload));
        return parsePullRequestUrl(resp, head, base);
    }

    private String parsePullRequestUrl(String resp, String head, String base) {
        try {
            JsonNode node = json.readTree(resp);
            String url = node.has("html_url") ? node.path("html_url").asText("")
                    : node.path("web_url").asText("");
            if (url.isBlank()) {
                throw new IllegalStateException("PR 已创建但未返回链接（" + head + " → " + base + "）");
            }
            return url;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 PR 响应失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- HTTP

    private String send(String method, String url, Map<String, String> headers, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT);
        headers.forEach(builder::header);
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> resp = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("%s %s 返回 %d：%s"
                        .formatted(method, safeUrl(url), resp.statusCode(), snippet(resp.body())));
            }
            return resp.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("调用 Git 接口失败：" + e.getMessage(), e);
        }
    }

    private Map<String, String> headers() {
        return Map.of("Content-Type", "application/json; charset=utf-8");
    }

    private static Map<String, String> githubHeaders(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("Accept", "application/vnd.github+json");
        h.put("Authorization", "Bearer " + token);
        h.put("X-GitHub-Api-Version", "2022-11-28");
        h.put("User-Agent", "agent-platform");
        return h;
    }

    private static Map<String, String> gitlabHeaders(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("PRIVATE-TOKEN", token);
        return h;
    }

    private String jsonBody(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("请求体序列化失败：" + e.getMessage(), e);
        }
    }

    private RemoteIssue parseIssue(String resp) {
        try {
            JsonNode node = json.readTree(resp);
            String number = node.has("number") ? node.path("number").asText()
                    : node.path("iid").asText();
            String url = node.has("html_url") ? node.path("html_url").asText()
                    : node.path("web_url").asText("");
            String state = normalizeState(node.path("state").asText("open"));
            return new RemoteIssue(number, url, state);
        } catch (Exception e) {
            throw new IllegalStateException("解析 Git 响应失败：" + e.getMessage(), e);
        }
    }

    private static String gitlabBase(Credentials c) {
        return "https://" + c.host() + "/api/v4";
    }

    private static String safeUrl(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?access_token=***";
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static List<String> splitLabels(String labels) {
        if (labels == null || labels.isBlank()) {
            return new ArrayList<>();
        }
        return java.util.Arrays.stream(labels.split("[,\\n]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    static String normalizeState(String state) {
        String s = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
        return "closed".equals(s) ? "CLOSED" : "OPEN";
    }

    /** 平台分类 → GitHub Issue 原生类型（GitHub 默认类型为 Bug / Feature / Task）。 */
    static String githubType(String kind) {
        return "FEAT".equalsIgnoreCase(kind) ? "Feature" : "Bug";
    }

    /** 平台分类 → Gitee 企业版任务类型（个人仓库仅支持默认「任务」，失败会自动降级）。 */
    static String giteeType(String kind) {
        return "FEAT".equalsIgnoreCase(kind) ? "需求" : "缺陷";
    }

    /** GitHub 个人仓库或未配置 Issue 类型时返回 400/422，可去掉 type 重试。 */
    private static boolean typeUnsupported(IllegalStateException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("返回 400") || message.contains("返回 422");
    }

    /** 仓库 owner（GitHub head 过滤需要 {@code owner:branch} 形式）。 */
    private static String ownerOf(String repo) {
        int slash = repo == null ? -1 : repo.indexOf('/');
        return slash <= 0 ? "" : repo.substring(0, slash);
    }

    /** 规范化仓库标识：允许粘贴完整 URL 或 owner/repo.git。 */
    public static String cleanRepo(String repo) {
        if (repo == null) {
            return "";
        }
        String r = repo.trim();
        if (r.startsWith("http://") || r.startsWith("https://")) {
            try {
                r = URI.create(r).getPath();
            } catch (RuntimeException ignored) {
                // 非标准 URL 时按原样处理
            }
        }
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        if (r.endsWith(".git")) {
            r = r.substring(0, r.length() - 4);
        }
        return r;
    }

    public record Credentials(String provider, String repo, String host, String token) {
    }

    public record RemoteIssue(String number, String url, String state) {
    }
}
