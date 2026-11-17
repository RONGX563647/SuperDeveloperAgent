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
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.settings.CapabilityMarket;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.domain.error.PlatformException;

/**
 * 外部能力市场：MCP 官方 Registry + Awesome-MCP-ZH 精选 + 公开 Skill 仓库
 * （默认 anthropics/skills 与 anbeime/skill）。
 *
 * <p>只做只读投影与安装模板解析：远端不可达时抛 {@link IllegalStateException}，
 * 由设置页提示，不再退回内置虚构清单。结果在进程内缓存 10 分钟，避免每次开页都打远端。
 *
 * <p>Skill 安装会拉取整个技能目录（SKILL.md + scripts/ + references/ 中的文本文件），
 * 不是只写一个 SKILL.md；MCP 精选清单来自 {@code yzfly/Awesome-MCP-ZH} 中可直接运行的服务器。
 */
@Component
public class RemoteCapabilityMarket implements CapabilityMarket {

    private static final Logger log = LoggerFactory.getLogger(RemoteCapabilityMarket.class);

    private static final long TTL_MS = 10 * 60_000L;
    private static final int PAGE_LIMIT = 100;
    private static final String AWESOME_SOURCE = "Awesome-MCP-ZH";
    private static final String AWESOME_PREFIX = "awesome-mcp:";
    /** 单个技能最多下载的文件数与体积上限，避免一个仓库把市场拖垮。 */
    private static final int MAX_FILES = 80;
    private static final long MAX_FILE_BYTES = 256 * 1024;
    private static final long MAX_TOTAL_BYTES = 4 * 1024 * 1024;
    /** 安装名只允许小写字母、数字、点、下划线与短横线。 */
    private static final Pattern UNSAFE = Pattern.compile("[^a-z0-9._-]");
    /** 技能包里的文本文件：脚本/配置/文档直接落盘；图片等二进制跳过。 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "json", "yaml", "yml", "py", "js", "mjs", "cjs",
            "ts", "tsx", "jsx", "sh", "bash", "ps1", "html", "css", "scss", "csv",
            "toml", "ini", "xml", "sql", "java", "go", "rs", "rb", "php", "vue");
    private static final Set<String> SKIP_SEGMENTS = Set.of(
            "_template", "__macosx", "node_modules", ".git", "dist", "build");

    /** Awesome-MCP-ZH 中可直接用 npx / uvx 跑起来的高频服务（避免只给链接不能装）。 */
    private static final List<Entry> AWESOME_MCP = awesomeMcp();

    private final ObjectMapper json;
    private final String mcpRegistry;
    private final List<String> skillRepos;
    private final HttpClient http;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, RepoTree> trees = new ConcurrentHashMap<>();
    /** 懒加载：技能索引只含仓库路径，SKILL.md 正文按页拉取。 */
    private final Map<String, Optional<Entry>> skillEntries = new ConcurrentHashMap<>();
    private volatile SkillIndex skillIndex;

    public RemoteCapabilityMarket(
            ObjectMapper json,
            @Value("${agent.market.mcp-registry:https://registry.modelcontextprotocol.io/v0/servers}")
            String mcpRegistry,
            @Value("${agent.market.skill-repos:anthropics/skills,anbeime/skill}")
            String skillRepos) {
        this.json = json;
        this.mcpRegistry = trimSlash(mcpRegistry);
        this.skillRepos = splitRepos(skillRepos);
        this.http = client();
    }

    @Override
    public List<Entry> search(Kind kind, String keyword) {
        if (kind == null) {
            List<Entry> out = new ArrayList<>(mcpPage(keyword, 0, Integer.MAX_VALUE).items());
            out.addAll(skillPage(keyword, 0, Integer.MAX_VALUE).items());
            return out;
        }
        return page(kind, keyword, 0, Integer.MAX_VALUE, false).items();
    }

    /**
     * 市场分页：{@code refresh=true} 时清掉本地缓存并重新同步两个技能仓库 / MCP Registry。
     *
     * <p>技能索引只解析仓库文件树（每个仓库 1 次请求），本页的 SKILL.md 才按需下载，
     * 因此 100+ 技能也不会在打开市场时全部拉一遍。
     */
    @Override
    public Page page(Kind kind, String keyword, int offset, int limit, boolean refresh) {
        int from = Math.max(0, offset);
        int size = Math.max(1, Math.min(limit, 200));
        if (refresh) {
            cache.keySet().removeIf(key -> kind == null || key.startsWith(kind.name() + "|"));
            if (kind == null || kind == Kind.SKILL) {
                trees.clear();
                skillEntries.clear();
                skillIndex = null;
            }
        }
        if (kind == Kind.SKILL) {
            return skillPage(keyword, from, size);
        }
        if (kind == Kind.MCP) {
            return mcpPage(keyword, from, size);
        }
        List<Entry> merged = new ArrayList<>(mcpPage(keyword, 0, Integer.MAX_VALUE).items());
        merged.addAll(skillPage(keyword, 0, Integer.MAX_VALUE).items());
        int end = (int) Math.min((long) from + size, merged.size());
        int start = Math.min(from, merged.size());
        return new Page(List.copyOf(merged.subList(start, end)), merged.size(), end < merged.size());
    }

    @Override
    public Optional<Entry> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        if (id.startsWith(AWESOME_PREFIX)) {
            return AWESOME_MCP.stream().filter(e -> e.id().equals(id)).findFirst();
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
            RepoTree tree = tree(repo);
            String content = get(rawUrl(repo, tree.branch(), path));
            // 安装时把技能目录里的脚本/参考资料一起带回，避免装出一个跑不起来的空壳
            return toSkill(repo, path, content)
                    .map(entry -> withBundle(entry, repo, tree, path));
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- MCP Registry

    /**
     * MCP 市场：Awesome-MCP-ZH 精选（离线也可用）与官方 Registry 合并，按 name 去重。
     *
     * <p>Registry 不可达时保留精选清单；两边都空才把错误抛给设置页。
     */
    private Page mcpPage(String keyword, int offset, int limit) {
        List<Entry> all = mcpAll(keyword);
        int start = Math.min(Math.max(0, offset), all.size());
        int end = (int) Math.min((long) start + Math.max(1, limit), all.size());
        return new Page(List.copyOf(all.subList(start, end)), all.size(), end < all.size());
    }

    private List<Entry> mcpAll(String keyword) {
        String q = keyword == null ? "" : keyword;
        String key = "MCP|" + q.toLowerCase(Locale.ROOT);
        Cached cached = cache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.entries();
        }
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Entry entry : AWESOME_MCP) {
            if (matches(entry, q) && seen.add(entry.name())) {
                out.add(entry);
            }
        }
        RuntimeException failure = null;
        try {
            String url = mcpRegistry + "?version=latest&limit=" + PAGE_LIMIT
                    + (q.isBlank() ? "" : "&search=" + enc(q));
            JsonNode root = read(get(url));
            for (JsonNode item : root.path("servers")) {
                parseServer(item.path("server"), item).ifPresent(entry -> {
                    if (matches(entry, q) && seen.add(entry.name())) {
                        out.add(entry);
                    }
                });
            }
        } catch (RuntimeException e) {
            failure = e;
        }
        if (out.isEmpty() && failure != null) {
            throw failure;
        }
        if (failure != null) {
            log.warn("MCP Registry 不可用，仅显示精选清单：{}", failure.getMessage());
        }
        cache.put(key, new Cached(List.copyOf(out), System.currentTimeMillis()));
        return out;
    }

    private static boolean matches(Entry entry, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String q = keyword.toLowerCase(Locale.ROOT);
        return (entry.id() + " " + entry.name() + " " + entry.title() + " "
                + entry.description() + " " + String.join(" ", entry.tags()) + " "
                + entry.source()).toLowerCase(Locale.ROOT).contains(q);
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

    /**
     * 技能分页：索引阶段只读仓库文件树，本页涉及的 SKILL.md 才下载。
     *
     * <p>这样打开市场时不会为了 100+ 技能把每个 SKILL.md 全拉一遍；滚动到下一页再拉下一页。
     */
    private Page skillPage(String keyword, int offset, int limit) {
        List<SkillRef> all = skillIndex();
        String q = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<SkillRef> matched = q.isEmpty() ? all
                : all.stream().filter(ref -> matchesRef(ref, q)).toList();
        int start = Math.min(Math.max(0, offset), matched.size());
        int end = (int) Math.min((long) start + Math.max(1, limit), matched.size());
        // 本页 SKILL.md 并发拉取，滚动下一页不会退化成逐个等待
        List<CompletableFuture<Optional<Entry>>> futures = matched.subList(start, end).stream()
                .map(ref -> CompletableFuture.supplyAsync(() -> skillEntry(ref)))
                .toList();
        List<Entry> items = new ArrayList<>();
        for (CompletableFuture<Optional<Entry>> future : futures) {
            future.join().ifPresent(items::add);
        }
        return new Page(List.copyOf(items), matched.size(), end < matched.size());
    }

    /** 技能索引：仓库 + 技能目录路径 + 安装名；不含 SKILL.md 正文。 */
    private List<SkillRef> skillIndex() {
        SkillIndex local = skillIndex;
        if (local != null && !local.expired()) {
            return local.refs();
        }
        synchronized (this) {
            local = skillIndex;
            if (local != null && !local.expired()) {
                return local.refs();
            }
            Map<String, SkillRef> byName = new LinkedHashMap<>();
            RuntimeException failure = null;
            for (String repo : skillRepos) {
                try {
                    RepoTree tree = tree(repo);
                    for (String path : tree.paths()) {
                        if (!isSkillEntry(path)) {
                            continue;
                        }
                        String[] segments = path.split("/");
                        String dirName = segments[segments.length - 2];
                        SkillRef ref = new SkillRef(repo, tree.branch(), path,
                                installName(dirName));
                        // 两个仓库可能有同名技能：先到先得，避免安装时撞目录
                        byName.putIfAbsent(ref.name(), ref);
                    }
                } catch (RuntimeException e) {
                    failure = e;
                    log.warn("技能仓库不可用 repo={}：{}", repo, e.getMessage());
                }
            }
            if (byName.isEmpty() && failure != null) {
                throw failure;
            }
            SkillIndex built = new SkillIndex(List.copyOf(byName.values()),
                    System.currentTimeMillis());
            skillIndex = built;
            return built.refs();
        }
    }

    /** 单个技能的 SKILL.md 正文（按路径缓存，滚动回上一页不重复下载）。 */
    private Optional<Entry> skillEntry(SkillRef ref) {
        Optional<Entry> cached = skillEntries.get(ref.path());
        if (cached != null) {
            return cached;
        }
        try {
            String content = get(rawUrl(ref.repo(), ref.branch(), ref.path()));
            Optional<Entry> entry = toSkill(ref.repo(), ref.path(), content);
            skillEntries.put(ref.path(), entry);
            return entry;
        } catch (RuntimeException e) {
            log.debug("技能详情拉取失败 path={}：{}", ref.path(), e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean matchesRef(SkillRef ref, String keyword) {
        return (ref.repo() + " " + ref.path() + " " + ref.name())
                .toLowerCase(Locale.ROOT).contains(keyword);
    }

    /** 技能入口：任意层级的 {@code <dir>/SKILL.md}，排除模板/依赖/系统缓存目录。 */
    static boolean isSkillEntry(String path) {
        if (path == null || !path.endsWith("/SKILL.md")) {
            return false;
        }
        String[] segments = path.split("/");
        if (segments.length < 2 || segments.length > 6) {
            return false;
        }
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            if (segment.isBlank() || segment.startsWith("._")
                    || SKIP_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
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
                "skill:" + repo + ":" + path, Kind.SKILL, installName(dirName), title,
                description, List.of(), repo, null, null, List.of(), null, content, List.of()));
    }

    /**
     * 把技能目录下的文本文件装进 Entry（安装时一次写完）。
     *
     * <p>跳过二进制/大文件；拉取失败时退回只有 SKILL.md 的条目，至少技能描述可用。
     */
    private Entry withBundle(Entry base, String repo, RepoTree tree, String path) {
        String dir = path.endsWith("/SKILL.md")
                ? path.substring(0, path.length() - "/SKILL.md".length()) : "";
        Map<String, String> files = new LinkedHashMap<>();
        String skillMd = base.content() == null ? "" : base.content();
        files.put("SKILL.md", skillMd);
        long total = skillMd.length();
        try {
            for (String candidate : tree.paths()) {
                if (files.size() >= MAX_FILES || total >= MAX_TOTAL_BYTES) {
                    break;
                }
                String relative = relativePath(dir, candidate);
                if (relative == null || "SKILL.md".equals(relative) || !isTextFile(candidate)) {
                    continue;
                }
                byte[] bytes = getBytes(rawUrl(repo, tree.branch(), candidate));
                if (bytes.length > MAX_FILE_BYTES) {
                    continue;
                }
                files.put(relative, new String(bytes, StandardCharsets.UTF_8));
                total += bytes.length;
            }
        } catch (RuntimeException e) {
            log.warn("技能包下载不完整，仅安装 SKILL.md repo={} path={}：{}",
                    repo, path, e.getMessage());
        }
        return new Entry(base.id(), base.kind(), base.name(), base.title(), base.description(),
                base.tags(), base.source(), base.transport(), base.command(), base.args(),
                base.url(), base.content(), base.requiredEnv(), Map.copyOf(files));
    }

    private static String relativePath(String dir, String path) {
        if (dir.isEmpty()) {
            return path.contains("/") ? null : path;
        }
        String prefix = dir + "/";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : null;
    }

    private static boolean isTextFile(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return false;
        }
        return TEXT_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 仓库文件树：优先 main，404 时退回 master；结果缓存 10 分钟。 */
    private RepoTree tree(String repo) {
        RepoTree cached = trees.get(repo);
        if (cached != null && !cached.expired()) {
            return cached;
        }
        RuntimeException failure = null;
        for (String branch : List.of("main", "master")) {
            try {
                JsonNode root = read(get("https://api.github.com/repos/" + repo
                        + "/git/trees/" + branch + "?recursive=1"));
                List<String> paths = new ArrayList<>();
                for (JsonNode node : root.path("tree")) {
                    if ("blob".equals(node.path("type").asText(""))) {
                        paths.add(node.path("path").asText(""));
                    }
                }
                RepoTree tree = new RepoTree(List.copyOf(paths), branch,
                        System.currentTimeMillis());
                trees.put(repo, tree);
                return tree;
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        throw failure == null
                ? new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "技能仓库不可达：" + repo) : failure;
    }

    private byte[] getBytes(String url) {
        try {
            HttpRequest request = request(url).GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "下载失败 HTTP " + response.statusCode() + "：" + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "下载被中断", e);
        } catch (IOException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "下载失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "能力市场响应无法解析：" + e.getMessage(), e);
        }
    }

    private String get(String url) {
        try {
            HttpResponse<String> response = http.send(request(url).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "能力市场返回 HTTP " + response.statusCode()
                        + "：" + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "能力市场请求被中断", e);
        } catch (IOException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "能力市场不可达：" + e.getMessage(), e);
        }
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("User-Agent", "agent-platform");
    }

    private static String rawUrl(String repo, String branch, String path) {
        return "https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + path;
    }

    /** Awesome-MCP-ZH 里高频、可直接 npx / uvx 启动的服务（精选，不含需要额外路径参数的服务）。 */
    private static List<Entry> awesomeMcp() {
        List<Entry> out = new ArrayList<>();
        out.add(mcpEntry("playwright", "Playwright 浏览器自动化",
                "让 Agent 打开网页、点击、填表、截图并读取页面结构。",
                List.of("浏览器", "自动化"), "npx",
                List.of("-y", "@playwright/mcp@latest")));
        out.add(mcpEntry("chrome-devtools", "Chrome DevTools",
                "通过 Chrome DevTools 协议调试页面、抓取网络请求与性能数据。",
                List.of("浏览器", "调试"), "npx",
                List.of("-y", "chrome-devtools-mcp@latest")));
        out.add(mcpEntry("git", "Git 仓库",
                "读取提交历史、diff、分支与文件内容，适合代码审查场景。",
                List.of("Git", "代码"), "uvx", List.of("mcp-server-git")));
        out.add(mcpEntry("fetch", "网页抓取",
                "把网页转成 Markdown 供模型阅读，适合资料检索与总结。",
                List.of("网页", "检索"), "uvx", List.of("mcp-server-fetch")));
        out.add(mcpEntry("memory", "长期记忆",
                "基于知识图谱的持久记忆，跨会话保存实体与关系。",
                List.of("记忆", "知识图谱"), "npx",
                List.of("-y", "@modelcontextprotocol/server-memory")));
        out.add(mcpEntry("sequential-thinking", "顺序思考",
                "提供结构化的分步推理工具，复杂任务可显式拆解。",
                List.of("推理", "规划"), "npx",
                List.of("-y", "@modelcontextprotocol/server-sequential-thinking")));
        out.add(mcpEntry("everything", "MCP 全能力示例",
                "官方示例服务器，覆盖工具/资源/提示词，用来验证客户端接入。",
                List.of("示例", "诊断"), "npx",
                List.of("-y", "@modelcontextprotocol/server-everything")));
        out.add(mcpEntry("time", "时间与时区",
                "获取指定时区的当前时间并进行时间换算。",
                List.of("时间", "工具"), "uvx", List.of("mcp-server-time")));
        out.add(mcpEntry("puppeteer", "Puppeteer 浏览器",
                "无头浏览器自动化，适合截图、抓取与端到端操作。",
                List.of("浏览器", "自动化"), "npx",
                List.of("-y", "@modelcontextprotocol/server-puppeteer")));
        out.add(mcpEntry("github", "GitHub",
                "读写仓库、Issue、PR 与代码搜索；需要 GitHub PAT。",
                List.of("GitHub", "代码"), "npx",
                List.of("-y", "@modelcontextprotocol/server-github"),
                List.of("GITHUB_PERSONAL_ACCESS_TOKEN")));
        out.add(mcpEntry("brave-search", "Brave 搜索",
                "通过 Brave Search API 做网页与新闻检索；需要 API Key。",
                List.of("搜索", "网页"), "npx",
                List.of("-y", "@modelcontextprotocol/server-brave-search"),
                List.of("BRAVE_API_KEY")));
        out.add(mcpEntry("notion", "Notion",
                "读写 Notion 页面与数据库；需要 Notion Integration Token。",
                List.of("文档", "协作"), "npx",
                List.of("-y", "@notionhq/notion-mcp-server"),
                List.of("NOTION_API_KEY")));
        out.add(mcpEntry("slack", "Slack",
                "读取频道消息、发送通知；需要 Bot Token 与 Team ID。",
                List.of("协作", "消息"), "npx",
                List.of("-y", "@modelcontextprotocol/server-slack"),
                List.of("SLACK_BOT_TOKEN", "SLACK_TEAM_ID")));
        out.add(mcpEntry("excel", "Excel 表格",
                "读取与写入 Excel 工作簿，适合报表与数据整理。",
                List.of("表格", "办公"), "npx",
                List.of("-y", "@negokaz/excel-mcp-server")));
        out.add(mcpEntry("markitdown", "文档转 Markdown",
                "把 PDF/Office 等文档转成 Markdown，便于模型阅读。",
                List.of("文档", "转换"), "uvx", List.of("markitdown-mcp")));
        out.add(mcpEntry("amap", "高德地图",
                "地址解析、路线规划与周边检索；需要高德 Web 服务 Key。",
                List.of("地图", "出行"), "npx",
                List.of("-y", "@amap/amap-maps-mcp-server"),
                List.of("AMAP_MAPS_API_KEY")));
        out.add(mcpEntry("antv-chart", "AntV 图表",
                "根据数据生成折线图、柱状图、饼图等可视化图片。",
                List.of("图表", "可视化"), "npx",
                List.of("-y", "@antv/mcp-server-chart")));
        out.add(mcpEntry("context7", "Context7 文档检索",
                "按库名检索最新版开发文档与示例，减少过时 API 幻觉。",
                List.of("文档", "检索"), "npx",
                List.of("-y", "@upstash/context7-mcp")));
        return List.copyOf(out);
    }

    private static Entry mcpEntry(String name, String title, String description,
                                  List<String> tags, String command, List<String> args) {
        return mcpEntry(name, title, description, tags, command, args, List.of());
    }

    private static Entry mcpEntry(String name, String title, String description,
                                  List<String> tags, String command, List<String> args,
                                  List<String> requiredEnv) {
        return new Entry(AWESOME_PREFIX + name, Kind.MCP, name, title, description, tags,
                AWESOME_SOURCE, "stdio", command, List.copyOf(args), null, null,
                List.copyOf(requiredEnv));
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
        return out.isEmpty()
                ? List.of("anthropics/skills", "anbeime/skill") : List.copyOf(out);
    }

    /** 与 git 一致读 HTTPS_PROXY / HTTP_PROXY：Java 的 HttpClient 不会自动读代理环境变量。 */
    private static HttpClient client() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL);
        SSLContext ssl = sslContext();
        if (ssl != null) {
            builder.sslContext(ssl);
        }
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
            } catch (PlatformException | IllegalArgumentException e) {
                // 代理变量格式不对时按直连处理，后面请求失败会给出明确错误
            }
        }
        return builder.build();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value != null ? value : System.getenv(name.toLowerCase(Locale.ROOT));
    }

    /**
     * 信任 JVM 默认 cacerts 与操作系统根证书库的并集。
     *
     * <p>GitHub 加速器、企业 SSL 中间人等注入的根证书通常只装在系统证书库，
     * JVM 只读 cacerts 时会报 PKIX path building failed，表现为市场整体不可达。
     */
    private static SSLContext sslContext() {
        List<X509TrustManager> managers = new ArrayList<>();
        X509TrustManager jvm = trustManager(null);
        if (jvm != null) {
            managers.add(jvm);
        }
        KeyStore roots = systemRoots();
        if (roots != null) {
            X509TrustManager system = trustManager(roots);
            if (system != null) {
                managers.add(system);
            }
        }
        if (managers.size() <= 1) {
            // 没有可合并的系统证书库时交给 HttpClient 自带的默认校验
            return null;
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new CompositeTrustManager(managers)}, null);
            return context;
        } catch (Exception e) {
            return null;
        }
    }

    /** store 传 null 表示 JVM 默认信任库（受 javax.net.ssl.trustStore 影响）。 */
    private static X509TrustManager trustManager(KeyStore store) {
        try {
            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    return x509;
                }
            }
        } catch (Exception e) {
            // 单个信任库不可用时忽略，由另一方兜底
        }
        return null;
    }

    /** Windows 读 Windows-ROOT；macOS 读 KeychainStore；Linux 的系统库本身已在 cacerts 中。 */
    private static KeyStore systemRoots() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String type = os.contains("win") ? "Windows-ROOT"
                : os.contains("mac") ? "KeychainStore" : null;
        if (type == null) {
            return null;
        }
        try {
            KeyStore store = KeyStore.getInstance(type);
            store.load(null, null);
            return store;
        } catch (Exception e) {
            return null;
        }
    }

    /** 任一信任库认可证书链即通过，实现 cacerts 与系统根证书取并集。 */
    static final class CompositeTrustManager implements X509TrustManager {

        private final List<X509TrustManager> delegates;

        CompositeTrustManager(List<X509TrustManager> delegates) {
            this.delegates = List.copyOf(delegates);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            verify(chain, authType, false);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            verify(chain, authType, true);
        }

        private void verify(X509Certificate[] chain, String authType, boolean server)
                throws CertificateException {
            CertificateException failure = null;
            for (X509TrustManager delegate : delegates) {
                try {
                    if (server) {
                        delegate.checkServerTrusted(chain, authType);
                    } else {
                        delegate.checkClientTrusted(chain, authType);
                    }
                    return;
                } catch (CertificateException e) {
                    failure = e;
                }
            }
            throw failure == null ? new CertificateException("没有可用的信任库") : failure;
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegates.stream()
                    .flatMap(manager -> Arrays.stream(manager.getAcceptedIssuers()))
                    .toArray(X509Certificate[]::new);
        }
    }

    private record Cached(List<Entry> entries, long at) {
        boolean expired() {
            return System.currentTimeMillis() - at > TTL_MS;
        }
    }

    private record RepoTree(List<String> paths, String branch, long at) {
        boolean expired() {
            return System.currentTimeMillis() - at > TTL_MS;
        }
    }

    private record SkillRef(String repo, String branch, String path, String name) {
    }

    private record SkillIndex(List<SkillRef> refs, long at) {
        boolean expired() {
            return System.currentTimeMillis() - at > TTL_MS;
        }
    }
}
