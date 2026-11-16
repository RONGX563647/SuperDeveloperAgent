package top.rongx.agent.domain.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具插件索引：把「一次性把全部工具 schema 塞进上下文」改成「常驻少量核心工具 + 按需加载插件」。
 *
 * <p>背景：平台装配了 70+ 个工具，单次请求光工具 schema 就有六十多 KB（约两万 token），
 * 每轮对话都重复消耗。这里按能力把工具切成若干插件（repo / ci / deploy / env / issue …），
 * 默认只暴露核心工具（项目信息、工具检索、插件加载等），其余插件由模型在需要时通过
 * {@code tool_load} 激活；未归类的工具（例如 MCP 工具、技能工具）默认保持可见，避免误伤。
 *
 * <p>本类只做纯数据与规则，不依赖 AgentScope / Spring：运行层用它在
 * {@code onReasoning} 里裁剪工具 schema，工具层用它实现 {@code tool_load}。
 *
 * <p><b>KV cache</b>：插件索引文案必须稳定（固定顺序、无时间戳），这样系统提示在会话内
 * 完全一致，模型侧前缀缓存才能命中；激活状态只影响后续的工具 schema 列表，不改写前缀。
 */
public final class ToolPluginIndex {

    /**
     * 一个工具插件（能力分组）。
     *
     * @param name        插件名（同时作为激活标记写入会话状态）
     * @param title       一句话标题（索引里展示）
     * @param description 用途说明（索引用，尽量短）
     * @param keywords    中文/英文意图关键词，用于按用户输入自动预加载
     * @param tools       插件包含的工具名
     */
    public record Plugin(String name, String title, String description,
                         Set<String> keywords, Set<String> tools) {
        public Plugin {
            keywords = keywords == null ? Set.of() : Set.copyOf(keywords);
            tools = tools == null ? Set.of() : Set.copyOf(tools);
        }
    }

    private final List<Plugin> plugins;
    private final Set<String> alwaysVisible;
    private final Map<String, String> pluginByTool;

    private ToolPluginIndex(List<Plugin> plugins, Set<String> alwaysVisible) {
        this.plugins = List.copyOf(plugins);
        this.alwaysVisible = Set.copyOf(alwaysVisible);
        Map<String, String> byTool = new LinkedHashMap<>();
        for (Plugin plugin : this.plugins) {
            for (String tool : plugin.tools()) {
                byTool.putIfAbsent(tool, plugin.name());
            }
        }
        this.pluginByTool = Map.copyOf(byTool);
    }

    /**
     * 按真实装配出来的工具名构建索引。
     *
     * @param definitions 插件定义（见 {@link ToolPlugins}）
     * @param actualTools 当前工具面真实存在的工具名（只保留存在的，避免索引与事实不符）
     * @param coreTools   始终可见的核心工具
     */
    public static ToolPluginIndex of(Collection<Plugin> definitions, Collection<String> actualTools,
                                     Collection<String> coreTools) {
        Set<String> actual = actualTools == null ? Set.of() : Set.copyOf(actualTools);
        Set<String> core = new LinkedHashSet<>();
        if (coreTools != null) {
            for (String name : coreTools) {
                if (name != null && actual.contains(name)) {
                    core.add(name);
                }
            }
        }
        List<Plugin> kept = new java.util.ArrayList<>();
        Set<String> grouped = new LinkedHashSet<>();
        for (Plugin definition : definitions) {
            Set<String> tools = new LinkedHashSet<>();
            for (String tool : definition.tools()) {
                if (actual.contains(tool) && !grouped.contains(tool)) {
                    tools.add(tool);
                }
            }
            if (tools.isEmpty()) {
                continue;
            }
            kept.add(new Plugin(definition.name(), definition.title(), definition.description(),
                    definition.keywords(), tools));
            grouped.addAll(tools);
        }
        // 未归类（MCP、技能工具、后续新增工具）默认可见：渐进披露不能因漏配而让能力消失
        for (String tool : actual) {
            if (!grouped.contains(tool)) {
                core.add(tool);
            }
        }
        return new ToolPluginIndex(kept, core);
    }

    public List<Plugin> plugins() {
        return plugins;
    }

    public Set<String> alwaysVisible() {
        return alwaysVisible;
    }

    public Optional<String> pluginOf(String toolName) {
        return toolName == null ? Optional.empty() : Optional.ofNullable(pluginByTool.get(toolName));
    }

    public Optional<Plugin> plugin(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String key = name.trim();
        return plugins.stream().filter(p -> p.name().equalsIgnoreCase(key)).findFirst();
    }

    /** 工具是否应在「当前已激活插件」下暴露给模型。 */
    public boolean visible(String toolName, Collection<String> activePlugins) {
        if (toolName == null) {
            return false;
        }
        if (alwaysVisible.contains(toolName)) {
            return true;
        }
        String owner = pluginByTool.get(toolName);
        if (owner == null) {
            return true;
        }
        return activePlugins != null && activePlugins.stream()
                .anyMatch(name -> name.equalsIgnoreCase(owner));
    }

    /** 按用户输入关键词挑选要预加载的插件（大小写不敏感；命中即加载，宁多勿漏）。 */
    public Set<String> route(String text) {
        Set<String> hits = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return hits;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (Plugin plugin : plugins) {
            for (String keyword : plugin.keywords()) {
                if (!keyword.isBlank() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                    hits.add(plugin.name());
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * 提示词里的插件索引（稳定文案）：只给插件名 + 用途 + 代表工具，不给完整 schema。
     *
     * <p>没有插件时返回空串，调用方应直接跳过这一节，避免给只读/调度 Agent 塞无用说明。
     */
    public String promptIndex() {
        if (plugins.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("可用能力插件（默认未加载，需要时先 tool_load(plugin)）：\n");
        for (Plugin plugin : plugins) {
            sb.append("- ").append(plugin.name()).append("：").append(plugin.description())
                    .append("（").append(sample(plugin.tools(), 4)).append("…）\n");
        }
        return sb.toString();
    }

    private static String sample(Set<String> tools, int limit) {
        return tools.stream().limit(limit).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
