package top.rongx.agent.gateway.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.settings.CapabilityInstaller;
import top.rongx.agent.domain.settings.CapabilityMarket;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * 能力市场（设置页「MCP 市场」「Skill 市场」）。
 *
 * <p>目录来自外部源：MCP 官方 Registry 与公开 Skill 仓库（见
 * {@link CapabilityMarket} 的实现），平台不再内置虚构清单。
 * 安装按 id 重新取安装模板，返回 installed 标记供前端显示「已接入 / 接入」。
 */
@RestController
@RequestMapping("/api/v1/settings/capabilities")
public class CapabilityMarketController {

    private final CapabilityMarket market;
    private final CapabilityInstaller installer;
    private final CurrentUser currentUser;

    public CapabilityMarketController(CapabilityMarket market, CapabilityInstaller installer,
                                      CurrentUser currentUser) {
        this.market = market;
        this.installer = installer;
        this.currentUser = currentUser;
    }

    /** 市场列表：kind=MCP|SKILL 可选，q 关键词可选。 */
    @GetMapping("/market")
    public List<Map<String, Object>> market(@RequestParam(required = false) String kind,
                                            @RequestParam(required = false) String q) {
        requireRead();
        CapabilityMarket.Kind type = CapabilityMarket.Kind.parse(kind).orElse(null);
        return market.search(type, q).stream().map(this::view).toList();
    }

    /** 安装：id 用市场返回的完整标识（含 MCP registry 原始名 / Skill 仓库路径）。 */
    @PostMapping("/market/install")
    public CapabilityInstaller.Result install(@RequestBody InstallRequest request) {
        requireWrite();
        if (request == null || request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("能力 id 不能为空");
        }
        return installer.install(request.id());
    }

    private Map<String, Object> view(CapabilityMarket.Entry entry) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entry.id());
        out.put("kind", entry.kind().name());
        out.put("name", entry.name());
        out.put("title", entry.title());
        out.put("description", entry.description());
        out.put("tags", entry.tags());
        out.put("source", entry.source());
        if (entry.transport() != null) {
            out.put("transport", entry.transport());
            out.put("command", entry.command());
            out.put("args", entry.args());
            out.put("url", entry.url());
        }
        out.put("requiredEnv", entry.requiredEnv());
        out.put("installed", installer.isInstalled(entry.kind().name(), entry.name()));
        return out;
    }

    private void requireRead() {
        currentUser.requireUserId();
        currentUser.checkPermission("settings:read");
    }

    private void requireWrite() {
        currentUser.requireUserId();
        currentUser.checkPermission("settings:write");
    }

    public record InstallRequest(String id) {
    }
}
