package top.rongx.agent.tools;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.settings.SkillCatalog;
import top.rongx.agent.domain.tool.ToolPluginIndex;
import top.rongx.agent.domain.tool.ToolPlugins;

/**
 * 工具/技能的渐进式发现（只读）。
 *
 * <p>目的：不让 70+ 个工具的 schema 常驻上下文。模型先用 {@code tool_search} 缩小候选，
 * 用 {@code tool_plugins} 看能力分组，用 {@code tool_load} 激活插件；技能同理，
 * {@code skill_load} 只读取被点名的 SKILL.md 正文。
 *
 * <p>目录来自运行时真实装配的 Toolkit（{@link ToolPluginIndex} 经 RuntimeContext 传入），
 * 因此只读面 / 发布面的差异会被如实体现。输出保持稳定文案，不写时间戳，避免污染 KV 前缀。
 */
@Component
public class ToolCatalogTools {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
    /** 单个 SKILL.md 最多返回的字符数：正文超长时截断，避免一次塞爆上下文。 */
    private static final int SKILL_MAX_CHARS = 12_000;

    private final AgentToolCatalog catalog;
    private final SkillCatalog skills;

    public ToolCatalogTools(AgentToolCatalog catalog, SkillCatalog skills) {
        this.catalog = catalog;
        this.skills = skills;
    }

    @Tool(name = "tool_search", description = "Search available agent tools.",
            readOnly = true, concurrencySafe = true)
    public String search(@ToolParam(name = "keyword", description = "关键词，如 deploy、git、env；可空")
                         String keyword,
                         @ToolParam(name = "read_only", required = false,
                                 description = "只返回只读工具，默认 false") Boolean readOnlyOnly,
                         @ToolParam(name = "limit", required = false,
                                 description = "返回条数，默认 10，最大 30") Integer limit) {
        List<AgentToolCatalog.Entry> hits = catalog.search(keyword, readOnlyOnly, limit);
        if (hits.isEmpty()) {
            return "(无匹配工具) 换关键词，或用 tool_plugins 查看能力分组。";
        }
        return AgentToolCatalog.format(hits)
                + "\n提示：调用前先用 tool_load 加载对应插件；参数细节用 tool_get。";
    }

    @Tool(name = "tool_get", description = "Read a tool's full parameter schema.",
            readOnly = true, concurrencySafe = true)
    public String get(@ToolParam(name = "name", description = "工具名，如 ci_run") String name) {
        AgentToolCatalog.Entry entry = catalog.entry(name);
        if (entry == null) {
            return "未找到工具 " + name + "。先用 tool_search 搜索。";
        }
        String params;
        try {
            params = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(entry.parameters());
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            params = String.valueOf(entry.parameters());
        }
        return """
                tool: %s
                readOnly: %s
                toolkits: %s
                description:
                %s
                parameters:
                %s""".formatted(entry.name(), entry.readOnly(),
                String.join(", ", entry.toolkits()),
                entry.description(), params);
    }

    @Tool(name = "tool_plugins", description = "List tool plugins and load state.",
            readOnly = true, concurrencySafe = true)
    public String plugins(RuntimeContext rc) {
        ToolPluginIndex index = index(rc);
        if (index.plugins().isEmpty()) {
            return "当前工具面没有分组插件，全部工具已直接可用。";
        }
        Set<String> active = activePlugins(rc);
        StringBuilder out = new StringBuilder("工具插件（已加载标记 *）：\n");
        for (ToolPluginIndex.Plugin plugin : index.plugins()) {
            out.append(active.contains(plugin.name()) ? "* " : "- ")
                    .append(plugin.name()).append("：").append(plugin.description())
                    .append("（").append(String.join(", ", plugin.tools())).append("）\n");
        }
        out.append("核心工具（始终可用）：").append(String.join(", ", index.alwaysVisible()));
        return out.toString();
    }

    @Tool(name = "tool_load", description = "Load one or more tool plugins.",
            readOnly = true, concurrencySafe = true)
    public String load(@ToolParam(name = "plugins", description = "插件名，多个用逗号分隔，如 deploy,env")
                       String plugins, RuntimeContext rc) {
        ToolPluginIndex index = index(rc);
        if (index.plugins().isEmpty()) {
            return "当前工具面没有分组插件，全部工具已直接可用。";
        }
        if (plugins == null || plugins.isBlank()) {
            return "请传插件名。可用插件："
                    + index.plugins().stream().map(ToolPluginIndex.Plugin::name)
                            .collect(Collectors.joining(", "));
        }
        Set<String> active = activePlugins(rc);
        List<String> loaded = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String raw : plugins.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            Optional<ToolPluginIndex.Plugin> plugin = index.plugin(name);
            if (plugin.isEmpty()) {
                unknown.add(name);
                continue;
            }
            if (active.add(plugin.get().name())) {
                loaded.add(plugin.get().name());
            }
        }
        boolean changed = persist(rc, active);
        StringBuilder out = new StringBuilder();
        if (!loaded.isEmpty()) {
            out.append("已加载插件：").append(String.join(", ", loaded))
                    .append("（工具已可用，无需重复加载）\n");
        }
        if (!unknown.isEmpty()) {
            out.append("未找到插件：").append(String.join(", ", unknown))
                    .append("；可用：").append(index.plugins().stream()
                            .map(ToolPluginIndex.Plugin::name).collect(Collectors.joining(", ")))
                    .append('\n');
        }
        if (loaded.isEmpty() && unknown.isEmpty()) {
            out.append("这些插件之前已加载，可直接调用其中的工具。\n");
        }
        if (!changed) {
            out.append("（当前会话状态不可用，本轮加载只在本次调用内生效）\n");
        }
        return out.toString().trim();
    }

    @Tool(name = "skill_load", description = "Load an enabled skill's SKILL.md.",
            readOnly = true, concurrencySafe = true)
    public String loadSkill(@ToolParam(name = "name", description = "技能目录名，如 docx") String name) {
        if (name == null || name.isBlank()) {
            return "请传技能目录名。可用技能："
                    + String.join(", ", skills.enabledNames());
        }
        String key = name.trim();
        if (!skills.enabledNames().contains(key)) {
            return "技能 " + key + " 未启用或不存在。可用技能：" + String.join(", ", skills.enabledNames());
        }
        return skills.content(key)
                .map(text -> text.length() > SKILL_MAX_CHARS
                        ? text.substring(0, SKILL_MAX_CHARS)
                                + "\n…（SKILL.md 已截断，共 " + text.length() + " 字；如需剩余内容请说明）"
                        : text)
                .orElse("技能 " + key + " 没有可读取的 SKILL.md。");
    }

    /** RuntimeContext 里的插件索引；缺失时退回静态目录（例如老调用方没带索引）。 */
    private ToolPluginIndex index(RuntimeContext rc) {
        ToolPluginIndex index = rc == null ? null : rc.get(ToolPluginIndex.class);
        if (index != null) {
            return index;
        }
        Set<String> names = catalog.catalog().entries().keySet();
        return ToolPluginIndex.of(ToolPlugins.standard(), names, ToolPlugins.CORE);
    }

    private Set<String> activePlugins(RuntimeContext rc) {
        AgentState state = rc == null ? null : rc.getAgentState();
        if (state == null) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(state.getToolContext().getActivatedGroups());
    }

    /** 把激活状态写回会话状态；返回是否写入成功（无状态时只影响本轮）。 */
    private boolean persist(RuntimeContext rc, Set<String> active) {
        AgentState state = rc == null ? null : rc.getAgentState();
        if (state == null) {
            return false;
        }
        state.getToolContext().setActivatedGroups(new ArrayList<>(active));
        return true;
    }
}
