package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.settings.CapabilityInstaller;
import top.rongx.agent.domain.settings.CapabilityMarket;
import top.rongx.agent.domain.error.PlatformException;

/**
 * 能力市场工具：让对话 Agent 自己发现并接入外部的 MCP / Skill。
 *
 * <p>只出现在非生产对话的工具面（{@code agentToolkit}），发布 Agent 与生产只读面无此能力。
 * 市场来自远端目录（MCP 官方 Registry / 公开 Skill 仓库）；安装是平台配置写操作，
 * 但不触碰用户仓库，装完提示用户在右侧「能力」工具栏勾选即可生效。
 */
@Component
public class CapabilityMarketTools {

    private final CapabilityMarket market;
    private final CapabilityInstaller installer;

    public CapabilityMarketTools(CapabilityMarket market, CapabilityInstaller installer) {
        this.market = market;
        this.installer = installer;
    }

    @Tool(name = "search_capabilities", description = "Search public MCP servers and skills.",
            readOnly = true, concurrencySafe = true)
    public String searchCapabilities(
            @ToolParam(name = "keyword",
                    description = "关键词（中英文均可），为空则列出全部") String keyword,
            @ToolParam(name = "kind",
                    description = "只看某一类：MCP 或 SKILL；为空表示全部") String kind) {
        CapabilityMarket.Kind type = CapabilityMarket.Kind.parse(kind).orElse(null);
        List<CapabilityMarket.Entry> found;
        try {
            found = market.search(type, keyword);
        } catch (PlatformException | IllegalStateException e) {
            return "err: " + e.getMessage();
        }
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

    @Tool(name = "install_capability", description = "Install a market capability (MCP/skill).",
            readOnly = false, concurrencySafe = false)
    public String installCapability(
            @ToolParam(name = "capabilityId",
                    description = "search_capabilities 返回的完整 id") String capabilityId) {
        try {
            CapabilityInstaller.Result result = installer.install(capabilityId);
            return result.message() + "（kind=%s, name=%s%s）".formatted(
                    result.kind(), result.name(), result.installed() ? ", 已存在" : "");
        } catch (PlatformException | IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }
    }
}
