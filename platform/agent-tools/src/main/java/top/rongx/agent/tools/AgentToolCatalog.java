package top.rongx.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RecipeException;

/**
 * 工具目录：把装配好的 Toolkit 导出成机器可读的 {@code agent-tools.json}。
 *
 * <p>目的：Agent 与外部 MCP 都能先"查目录"再决定调用哪个工具，避免把 60+ 个工具的
 * schema 全量塞进提示词，也让"平台现在有哪些工具"有唯一事实源。
 *
 * <p>数据来自真实 Toolkit（而不是扫源码/注解），因此各工具面的差异（只读面没有写工具）
 * 会被如实记录。目录在应用就绪后生成一次，写盘失败不影响运行。
 */
@Component
public class AgentToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(AgentToolCatalog.class);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Toolkit bean 名 → 对外展示的工具面名称。 */
    private static final Map<String, String> TOOLKITS = Map.of(
            "readOnlyToolkit", "read-only",
            "agentToolkit", "full",
            "supervisorToolkit", "supervisor",
            "deployToolkit", "deploy");

    private final ApplicationContext context;
    private final Path catalogPath;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile Catalog catalog;

    public AgentToolCatalog(ApplicationContext context,
                            @Value("${agent.tools.catalog-path:./data/api/agent-tools.json}")
                            String catalogPath) {
        this.context = context;
        this.catalogPath = Path.of(catalogPath).toAbsolutePath().normalize();
    }

    /** 目录快照；未生成时懒生成。 */
    public Catalog catalog() {
        Catalog current = catalog;
        return current != null ? current : build();
    }

    public List<Entry> search(String keyword, Boolean readOnlyOnly, Integer limit) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        int max = limit == null ? 10 : Math.max(1, Math.min(30, limit));
        boolean onlyReadOnly = Boolean.TRUE.equals(readOnlyOnly);
        List<Entry> hits = new ArrayList<>();
        for (Entry e : catalog().entries.values()) {
            if (onlyReadOnly && !e.readOnly) {
                continue;
            }
            if (!kw.isEmpty() && !e.haystack().contains(kw)) {
                continue;
            }
            hits.add(e);
            if (hits.size() >= max) {
                break;
            }
        }
        return hits;
    }

    public Entry entry(String name) {
        return name == null ? null : catalog().entries.get(name.trim());
    }

    public String json() {
        return catalog().json;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void export() {
        try {
            String json = json();
            Path parent = catalogPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(catalogPath, json, StandardCharsets.UTF_8);
            log.info("工具目录已导出：{}（{} 个工具）", catalogPath, catalog().entries.size());
        }
        catch (IOException | RuntimeException e) {
            log.warn("工具目录导出失败（不影响运行）：{}", e.toString());
        }
    }

    private synchronized Catalog build() {
        Catalog current = catalog;
        if (current != null) {
            return current;
        }
        Map<String, MutableEntry> merged = new LinkedHashMap<>();
        for (Map.Entry<String, String> face : TOOLKITS.entrySet()) {
            Toolkit toolkit;
            try {
                toolkit = context.getBean(face.getKey(), Toolkit.class);
            }
            catch (RuntimeException e) {
                continue;
            }
            for (String name : toolkit.getToolNames()) {
                AgentTool tool = toolkit.getTool(name);
                if (tool == null) {
                    continue;
                }
                MutableEntry entry = merged.computeIfAbsent(name, n -> new MutableEntry(
                        n, tool.getDescription(), tool.isReadOnly(), tool.getParameters()));
                entry.toolkits.add(face.getValue());
            }
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (MutableEntry m : merged.values()) {
            entries.put(m.name, new Entry(m.name, m.description, m.readOnly,
                    List.copyOf(m.toolkits), m.parameters,
                    (m.name + " " + m.description + " " + String.join(" ", m.toolkits))
                            .toLowerCase(Locale.ROOT)));
        }
        long now = System.currentTimeMillis();
        String json = render(entries, now);
        Catalog built = new Catalog(now, entries, json);
        catalog = built;
        return built;
    }

    private String render(Map<String, Entry> entries, long generatedAt) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Entry e : entries.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.name);
            row.put("description", e.description);
            row.put("readOnly", e.readOnly);
            row.put("toolkits", e.toolkits);
            row.put("parameters", e.parameters);
            tools.add(row);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", TIME.format(Instant.ofEpochMilli(generatedAt)));
        root.put("count", tools.size());
        root.put("tools", tools);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        }
        catch (IOException e) {
            throw new RecipeException(ErrorCode.RECIPE_INVALID_STATE, "工具目录序列化失败", e);
        }
    }

    /** 单个工具（对外只读视图）；haystack 预计算，搜索时不再拼串。 */
    public record Entry(String name, String description, boolean readOnly,
                        List<String> toolkits, Map<String, Object> parameters, String haystack) {
    }

    /** 当前目录：生成时间 + 工具条目。 */
    public record Catalog(long generatedAt, Map<String, Entry> entries, String json) {
    }

    private static final class MutableEntry {
        final String name;
        final String description;
        final boolean readOnly;
        final Map<String, Object> parameters;
        final Set<String> toolkits = new LinkedHashSet<>();

        MutableEntry(String name, String description, boolean readOnly,
                     Map<String, Object> parameters) {
            this.name = name;
            this.description = description;
            this.readOnly = readOnly;
            this.parameters = parameters == null ? Map.of() : parameters;
        }
    }

    /**
     * 供工具层复用的格式化：一行一个工具，描述只保留首句（<=100 字）。
     *
     * <p>这里是 {@code tool_search} 的返回体：过去的实现会带上多行完整描述，
     * 单次搜索就能吃掉几千 token。详细参数交给 {@code tool_get}。
     */
    public static String format(Collection<Entry> entries) {
        return entries.stream()
                .map(e -> "- %s%s — %s".formatted(
                        e.name, e.readOnly ? " [readOnly]" : "", brief(e.description)))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("(无匹配工具)");
    }

    /** 描述首句；超长截断。用于压缩 search 结果体积。 */
    private static String brief(String description) {
        if (description == null) {
            return "";
        }
        String text = description.strip();
        int cut = text.indexOf('\n');
        if (cut >= 0) {
            text = text.substring(0, cut);
        }
        return text.length() > 100 ? text.substring(0, 100) + "…" : text;
    }
}
