package top.rongx.agent.gateway.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.settings.CapabilityCatalog;
import top.rongx.agent.domain.settings.CapabilityInstaller;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * 能力市场（设置页「MCP 市场」「Skill 市场」）。
 *
 * <p>目录来自 {@link CapabilityCatalog} 的内置清单，安装动作统一走
 * {@link CapabilityInstaller}；返回 installed 标记，前端据此显示「已接入 / 接入」。
 */
@RestController
@RequestMapping("/api/v1/settings/capabilities")
public class CapabilityMarketController {

    private final CapabilityInstaller installer;
    private final CurrentUser currentUser;

    public CapabilityMarketController(CapabilityInstaller installer, CurrentUser currentUser) {
        this.installer = installer;
        this.currentUser = currentUser;
    }

    /** 市场列表：kind=MCP|SKILL 可选，q 关键词可选。 */
    @GetMapping("/market")
    public List<Map<String, Object>> market(@RequestParam(required = false) String kind,
                                            @RequestParam(required = false) String q) {
        requireRead();
        CapabilityCatalog.Kind type = CapabilityCatalog.Kind.parse(kind).orElse(null);
        return CapabilityCatalog.search(type, q).stream().map(this::view).toList();
    }

    @PostMapping("/market/{id}/install")
    public CapabilityInstaller.Result install(@PathVariable String id) {
        requireWrite();
        return installer.install(id);
    }

    private Map<String, Object> view(CapabilityCatalog.Capability capability) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", capability.id());
        out.put("kind", capability.kind().name());
        out.put("name", capability.name());
        out.put("title", capability.title());
        out.put("description", capability.description());
        out.put("tags", capability.tags());
        out.put("source", capability.source());
        if (capability.mcp() != null) {
            out.put("transport", capability.mcp().transport());
            out.put("command", capability.mcp().command());
            out.put("args", capability.mcp().args());
            out.put("url", capability.mcp().url());
        }
        out.put("installed", installer.isInstalled(capability.kind().name(), capability.name()));
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
}
