package top.rongx.agent.infra.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.settings.CapabilityMarket;

/**
 * 外部能力市场：MCP 官方 Registry + 公开 Skill 仓库（默认 anthropics/skills）。
 *
 * <p>只做只读投影与安装模板解析：远端不可达时抛 {@link IllegalStateException}，
 * 由设置页提示，不再退回内置虚构清单。结果在进程内缓存 10 分钟，避免每次开页都打远端。
 */
@Component
public class RemoteCapabilityMarket implements CapabilityMarket {

    private static final long TTL_MS = 10 * 60_000L;
    private static final int PAGE_LIMIT = 100;
    private static final String SKILL_CACHE_KEY = "SKILL";
    /** 安装名只允许小写字母、数字、点、下划线与短横线。 */
    private static final Pattern UNSAFE = Pattern.compile("[^a-z0-9._-]");

    private final ObjectMapper json;
    private final String mcpRegistry;
    private final List<String> skillRepos;
    private final HttpClient http;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RemoteCapabilityMarket(
            ObjectMapper json,
            @Value("${agent.market.mcp-registry:https://registry.modelcontextprotocol.io/v0/servers}")
            String mcpRegistry,
            @Value("${agent.market.skill-repos:anthropics/skills}") String skillRepos) {
        this.json = json;
        this.mcpRegistry = trimSlash(mcpRegistry);
        this.skillRepos = splitRepos(skillRepos);
        this.http = client();
    }

    @Override
    public List<Entry> search(Kind kind, String keyword) {
        String q = keyword == null ? "" : keyword.trim();
        if (kind == Kind.SKILL) {
            return filter(skills(), q);
        }
        if (kind == Kind.MCP) {
            return mcp(q);
        }
        List<Entry> out = new ArrayList<>(mcp(q));
        out.addAll(filter(skills(), q));
        return out;
    }

    @Override
    public Optional<Entry> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        if (id.startsWith("mcp:")) {
            String name = id.substring("mcp:".length());
            JsonNode root = read(get(mcpRegistry + "/" + enc(name) + "/versions/latest"));
            return parseServer(root.path("server"), root);
        }
        if (id.startsWith("skill:")) {
            String rest = id.substring("skill:".length());
            int split = rest.indexOf(':');
            if (split <= 0) {
                return Optional.empty();
            }
            String repo = rest.substring(0, split);
            String path = rest.substring(split + 1);
            String content = get(rawUrl(repo, path));
            return toSkill(repo, path, content);
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- MCP Registry

    private List<Entry> mcp(String keyword) {
        String key = "MCP|" + keyword.toLowerCase(Locale.ROOT);
        Cached cached = cache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.entries();
        }
        String url = mcpRegistry + "?version=latest&limit=" + PAGE_LIMIT
                + (keyword.isBlank() ? "" : "&search=" + enc(keyword));
        JsonNode root = read(get(url));
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : root.path("servers")) {
            parseServer(item.path("server"), item).ifPresent(entry -> {
                if (seen.add(entry.name())) {
                    out.add(entry);
                }
            });
        }
        cache.put(key, new Cached(out, System.currentTimeMillis()));
        return out;
    }

    /**
     * 单个 Registry 条目 → 安装模板。
     *
     * <p>优先 stdio 包；没有包时退回无占位符的 http 远端。
     * 两类都没有（或远端 URL 需要用户填参数）则不进入市场，避免装出不可用的服务。
     */
    static Optional<Entry> parseServer(JsonNode server, JsonNode wrapper) {
        String registryName = server.path("name").asText("").trim();
        if (registryName.isEmpty()) {
            return Optional.empty();
        }
        List<String> tags = new ArrayList<>();
        JsonNode publisher = wrapper.path("_meta")
                .path("io.modelcontextprotocol.registry/publisher-provided");
        for (JsonNode tag : publisher.path("tags")) {
            tags.add(tag.asText(""));
        }

        String transport = null;
        String command = null;
        List<String> args = new ArrayList<>();
        String url = null;
        List<String> requiredEnv = new ArrayList<>();

        for (JsonNode pkg : server.path("packages")) {
            String pkgTransport = pkg.path("transport").path("type").asText("");
            // 缺省 transport 按 stdio 处理（registry schema 的默认值）
            if (!pkgTransport.isBlank() && !"stdio".equals(pkgTransport)) {
                continue;
            }
            String registryType = pkg.path("registryType").asText("").toLowerCase(Locale.ROOT);
            String identifier = pkg.path("identifier").asText("");
            String version = pkg.path("version").asText("");
            if (identifier.isEmpty()) {
                continue;
            }
            String hint = pkg.path("runtimeHint").asText("");
            for (JsonNode arg : pkg.path("runtimeArguments")) {
                String value = arg.path("value").asText("");
                if (!value.isBlank()) {
                    args.add(value);
                }
            }
            switch (registryType) {
                case "npm" -> {
                    command = hint.isBlank() ? "npx" : hint;
                    if (!args.contains("-y")) {
                        args.add(0, "-y");
                    }
                    args.add(version.isBlank() ? identifier : identifier + "@" + version);
                }
                case "pypi" -> {
                    command = hint.isBlank() ? "uvx" : hint;
                    args.add(identifier);
                }
                case "docker", "oci" -> {
                    command = hint.isBlank() ? "docker" : hint;
                    args.addAll(List.of("run", "-i", "--rm",
                            version.isBlank() ? identifier : identifier + ":" + version));
                }
                default -> {
                    continue;
                }
            }
            transport = "stdio";
            for (JsonNode env : pkg.path("environmentVariables")) {
                if (env.path("isRequired").asBoolean(false)) {
                    requiredEnv.add(env.path("name").asText(""));
                }
            }
            break;
        }

        if (transport == null) {
            for (JsonNode remote : server.path("remotes")) {
                String candidate = remote.path("url").asText("");
                // 带 {placeholder} 的地址需要用户补参数，直接装会得到一个坏 URL
                if (!candidate.isBlank() && !candidate.contains("{")) {
                    transport = "http";
                    url = candidate;
                    break;
                }
            }
        }
        if (transport == null) {
            return Optional.empty();
        }

        String title = server.path("title").asText("");
        String description = server.path("description").asText("");
        String name = installName(registryName);
        return Optional.of(new Entry(
                "mcp:" + registryName, Kind.MCP, name,
                title.isBlank() ? registryName : title,
                description.isBlank() ? title : description,
                tags.stream().filter(t -> !t.isBlank()).toList(),
                "MCP Registry", transport, command, List.copyOf(args), url, null,
                requiredEnv.stream().filter(e -> !e.isBlank()).toList()));
    }

    // ---------------------------------------------------------------- Skill 仓库

    private List<Entry> skills() {
        Cached cached = cache.get(SKILL_CACHE_KEY);
        if (cached != null && !cached.expired()) {
            return cached.entries();
        }
        List<Entry> all = new ArrayList<>();
        for (String repo : skillRepos) {
            all.addAll(fetchSkills(repo));
        }
        cache.put(SKILL_CACHE_KEY, new Cached(all, System.currentTimeMillis()));
        return all;
    }

    private List<Entry> fetchSkills(String repo) {
        JsonNode root = read(get("https://api.github.com/repos/" + repo + "/git/trees/main?recursive=1"));
        List<String> paths = new ArrayList<>();
        for (JsonNode node : root.path("tree")) {
            String path = node.path("path").asText("");
            // 只取 <name>/SKILL.md 或 skills/<name>/SKILL.md 这一层的技能入口
            if (path.endsWith("/SKILL.md") && path.split("/").length <= 3) {
                paths.add(path);
            }
        }
        List<CompletableFuture<Optional<Entry>>> futures = new ArrayList<>();
        for (String path : paths) {
            HttpRequest request = request(rawUrl(repo, path)).GET().build();
            futures.add(http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(res -> res.statusCode() / 100 == 2
                            ? toSkill(repo, path, res.body()) : Optional.<Entry>empty())
                    // 单个技能拉取失败不影响整份清单
                    .exceptionally(e -> Optional.empty()));
        }
        List<Entry> out = new ArrayList<>();
        for (CompletableFuture<Optional<Entry>> future : futures) {
            future.join().ifPresent(out::add);
        }
        return out;
    }

    static Optional<Entry> toSkill(String repo, String path, String content) {
        if (content == null || content.isBlank() || !content.startsWith("---")) {
            return Optional.empty();
        }
        String[] segments = path.split("/");
        String dirName = segments.length >= 2 ? segments[segments.length - 2] : path;
        SkillsStore.Frontmatter fm = SkillsStore.parseFrontmatter(content);
        String title = fm.name() == null || fm.name().isBlank() ? dirName : fm.name();
        String description = fm.description() == null ? "" : fm.description();
        return Optional.of(new Entry(
                "skill:" + repo + ":" + path, Kind.SKILL, dirName, title, description,
                List.of(), repo, null, null, List.of(), null, content, List.of()));
    }

    // ---------------------------------------------------------------- helpers

    private static List<Entry> filter(List<Entry> entries, String keyword) {
        String q = keyword.toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return entries;
        }
        return entries.stream().filter(e -> (e.id() + " " + e.name() + " " + e.title() + " "
                        + e.description() + " " + String.join(" ", e.tags()) + " " + e.source())
                .toLowerCase(Locale.ROOT).contains(q)).toList();
    }

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("能力市场响应无法解析：" + e.getMessage(), e);
        }
    }

    private String get(String url) {
        try {
            HttpResponse<String> response = http.send(request(url).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("能力市场返回 HTTP " + response.statusCode()
                        + "：" + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("能力市场请求被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("能力市场不可达：" + e.getMessage(), e);
        }
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("User-Agent", "agent-platform");
    }

    private static String rawUrl(String repo, String path) {
        return "https://raw.githubusercontent.com/" + repo + "/main/" + path;
    }

    private static String installName(String registryName) {
        String safe = UNSAFE.matcher(registryName.toLowerCase(Locale.ROOT)).replaceAll("-")
                .replaceAll("-{2,}", "-");
        while (safe.startsWith("-") || safe.startsWith(".")) {
            safe = safe.substring(1);
        }
        if (safe.isEmpty()) {
            safe = "mcp-server";
        }
        return safe.length() > 64 ? safe.substring(0, 64) : safe;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static List<String> splitRepos(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : (raw == null ? "" : raw).split(",")) {
            String repo = part.trim();
            if (repo.matches("[\\w.-]+/[\\w.-]+")) {
                out.add(repo);
            }
        }
        return out.isEmpty() ? List.of("anthropics/skills") : List.copyOf(out);
    }

    /** 与 git 一致读 HTTPS_PROXY / HTTP_PROXY：Java 的 HttpClient 不会自动读代理环境变量。 */
    private static HttpClient client() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String raw = env("HTTPS_PROXY");
        if (raw == null || raw.isBlank()) {
            raw = env("HTTP_PROXY");
        }
        if (raw != null && !raw.isBlank()) {
            try {
                URI uri = URI.create(raw.trim().contains("://") ? raw.trim() : "http://" + raw.trim());
                if (uri.getHost() != null) {
                    builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(),
                            uri.getPort() > 0 ? uri.getPort() : 8080)));
                }
            } catch (IllegalArgumentException e) {
                // 代理变量格式不对时按直连处理，后面请求失败会给出明确错误
            }
        }
        return builder.build();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value != null ? value : System.getenv(name.toLowerCase(Locale.ROOT));
    }

    private record Cached(List<Entry> entries, long at) {
        boolean expired() {
            return System.currentTimeMillis() - at > TTL_MS;
        }
    }
}
