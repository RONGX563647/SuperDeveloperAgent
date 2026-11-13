package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.settings.CapabilityCatalog;
import top.rongx.agent.domain.settings.CapabilityInstaller;

/**
 * 能力市场工具：让对话 Agent 自己发现并接入公开的 MCP / Skill。
 *
 * <p>只出现在非生产对话的工具面（{@code agentToolkit}），发布 Agent 与生产只读面无此能力。
 * 安装是平台配置写操作，但不触碰用户仓库；装完提示用户在右侧「能力」工具栏勾选即可生效。
 */
@Component
public class CapabilityMarketTools {

    private final CapabilityInstaller installer;

    public CapabilityMarketTools(CapabilityInstaller installer) {
        this.installer = installer;
    }

    @Tool(name = "search_capabilities",
            description = """
                    查找公开的 MCP 服务 / Skill 能力（内置市场，只读）。
                    当用户问"有没有能操作浏览器的工具""找个日志分析技能"时先调用本工具；
                    返回 id、类型、名称、用途与是否已接入。接入请接着调用 install_capability。""",
            readOnly = true, concurrencySafe = true)
    public String searchCapabilities(
            @ToolParam(name = "keyword",
                    description = "关键词（中英文均可），为空则列出全部") String keyword,
            @ToolParam(name = "kind",
                    description = "只看某一类：MCP 或 SKILL；为空表示全部") String kind) {
        CapabilityCatalog.Kind type = CapabilityCatalog.Kind.parse(kind).orElse(null);
        List<CapabilityCatalog.Capability> found = CapabilityCatalog.search(type, keyword);
        if (found.isEmpty()) {
            return "没有匹配的能力，换个关键词试试（例如 browser / 日志 / 测试）。";
        }
        return found.stream().map(c -> {
            boolean installed = installer.isInstalled(c.kind().name(), c.name());
            return "- %s [%s] %s（%s）%s".formatted(
                    c.id(), c.kind(), c.title(), c.name(), installed ? " · 已接入" : "");
        }).collect(Collectors.joining("\n",
                "找到 %d 个能力：\n".formatted(found.size()), ""));
    }

    @Tool(name = "install_capability",
            description = """
                    把市场里的能力接入平台（写操作：新增 MCP 服务或写入 Skill 目录）。
                    参数用 search_capabilities 返回的 id，例如 mcp-playwright / skill-code-review。
                    接入后告诉用户：可在对话右侧「能力」工具栏勾选本会话是否调用；设置页可改默认启停。""",
            readOnly = false, concurrencySafe = false)
    public String installCapability(
            @ToolParam(name = "capabilityId",
                    description = "市场能力 id，例如 mcp-playwright / skill-log-analysis") String capabilityId) {
        try {
            CapabilityInstaller.Result result = installer.install(capabilityId);
            return result.message() + "（kind=%s, name=%s%s）".formatted(
                    result.kind(), result.name(), result.installed() ? ", 已存在" : "");
        } catch (IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }
    }

    /** 供提示词/调试使用的能力概览（非工具）。 */
    public Map<String, Long> summary() {
        return CapabilityCatalog.all().stream()
                .collect(Collectors.groupingBy(c -> c.kind().name(), Collectors.counting()));
    }
}
