package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 工具目录查询（只读）。
 *
 * <p>让模型先按关键词缩小候选，再决定调用哪个工具，避免把全部工具 schema 常驻提示词。
 * 目录来自运行时真实装配的 Toolkit；工具面差异（只读面 / 发布面）会如实体现。
 */
@Component
public class ToolCatalogTools {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final AgentToolCatalog catalog;

    public ToolCatalogTools(AgentToolCatalog catalog) {
        this.catalog = catalog;
    }

    @Tool(name = "tool_search",
            description = """
                    搜索平台当前可用的 Agent 工具（只读）：按名称/描述/工具面匹配。
                    先搜再调用，比凭记忆猜工具名更可靠；返回每个工具的 readOnly 与所属工具面。""",
            readOnly = true, concurrencySafe = true)
    public String search(@ToolParam(name = "keyword", description = "关键词，如 deploy、git、env；可空")
                         String keyword,
                         @ToolParam(name = "read_only", required = false,
                                 description = "只返回只读工具，默认 false") Boolean readOnlyOnly,
                         @ToolParam(name = "limit", required = false,
                                 description = "返回条数，默认 10，最大 30") Integer limit) {
        List<AgentToolCatalog.Entry> hits = catalog.search(keyword, readOnlyOnly, limit);
        String body = hits.isEmpty() ? "(无匹配工具)" : AgentToolCatalog.format(hits);
        return body + "\n" + provenance();
    }

    @Tool(name = "tool_get",
            description = "读取某个 Agent 工具的完整参数定义（只读）。name 用 tool_search 返回的名称。",
            readOnly = true, concurrencySafe = true)
    public String get(@ToolParam(name = "name", description = "工具名，如 ci_run") String name) {
        AgentToolCatalog.Entry entry = catalog.entry(name);
        if (entry == null) {
            return "未找到工具 " + name + "。先用 tool_search 搜索。\n" + provenance();
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
                %s
                %s""".formatted(entry.name(), entry.readOnly(),
                String.join(", ", entry.toolkits()),
                entry.description(), params, provenance());
    }

    private String provenance() {
        AgentToolCatalog.Catalog current = catalog.catalog();
        return "source: runtime toolkit；generatedAt: "
                + TIME.format(Instant.ofEpochMilli(current.generatedAt()))
                + "；tools=" + current.entries().size();
    }
}
