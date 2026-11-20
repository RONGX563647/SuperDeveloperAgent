package top.rongx.agent.gateway.web;

import java.util.ArrayList;
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
import top.rongx.agent.domain.settings.CapabilityState;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;

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

    /**
     * 市场列表（分页 + 懒加载）：kind=MCP|SKILL 可选，q 关键词可选。
     *
     * <p>{@code refresh=true} 强制重新同步远端仓库 / Registry（设置页每次打开或点刷新都会带）；
     * 滚动加载下一页时传 false，复用本次同步的索引。
     */
    @GetMapping("/market")
    public Map<String, Object> market(@RequestParam(required = false) String kind,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(required = false) String source,
                                      @RequestParam(defaultValue = "0") int offset,
                                      @RequestParam(defaultValue = "24") int limit,
                                      @RequestParam(defaultValue = "false") boolean refresh) {
        requireRead();
        CapabilityMarket.Kind type = CapabilityMarket.Kind.parse(kind).orElse(null);
        CapabilityMarket.Page page = market.page(type, q, source, offset, limit, refresh);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", page.items().stream().map(this::view).toList());
        out.put("total", page.total());
        out.put("hasMore", page.hasMore());
        return out;
    }

    /**
     * 已接入的市场来源清单（设置页渲染来源筛选 chips 与「如何接入开源市场」说明）。
     *
     * <p>来源来自配置的 Registry 列表与 Skill 仓库列表，前端不再写死「单一仓库」。
     */
    @GetMapping("/sources")
    public Map<String, Object> sources(@RequestParam(required = false) String kind) {
        requireRead();
        CapabilityMarket.Kind type = CapabilityMarket.Kind.parse(kind).orElse(null);
        List<Map<String, Object>> items = new ArrayList<>();
        for (CapabilityMarket.Source source : market.sources(type)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", source.id());
            row.put("kind", source.kind().name());
            row.put("name", source.name());
            row.put("url", source.url());
            row.put("description", source.description());
            row.put("builtin", source.builtin());
            items.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", items.size());
        return out;
    }

    /** 安装：id 用市场返回的完整标识（含 MCP registry 原始名 / Skill 仓库路径）。 */
    @PostMapping("/market/install")
    public CapabilityInstaller.Result install(@RequestBody InstallRequest request) {
        requireWrite();
        if (request == null || request.id() == null || request.id().isBlank()) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "能力 id 不能为空");
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
        boolean installed = installer.isInstalled(entry.kind().name(), entry.name());
        out.put("installed", installed);
        // 名称之外必须回答「能不能用、为什么不能用」：只给名字时用户装完发现会话里
        // 用不上，只能猜是没装好、缺变量、还是平台没挂上。
        CapabilityState state = state(entry, installed);
        out.put("enabled", state.enabled());
        out.put("available", state.available());
        out.put("reasonCode", state.reasonCode());
        out.put("reason", state.reason());
        out.put("missingEnv", state.missingEnv());
        return out;
    }

    /**
     * 计算能力状态：已安装 + 必需环境变量齐全才算可用。
     *
     * <p>市场列表里的项默认视为「用户已开启」（要装就能装）；
     * 未安装时给出 NOT_INSTALLED，缺变量时直接列出缺哪几个。
     * 这样卡片可以一次说清「接入 / 已接入但缺变量 / 可用」三种状态。
     */
    private CapabilityState state(CapabilityMarket.Entry entry, boolean installed) {
        if (!installed) {
            return CapabilityState.notInstalled(entry.name(), entry.kind().name(), true);
        }
        List<String> missing = missingEnv(entry.requiredEnv());
        if (!missing.isEmpty()) {
            return CapabilityState.missingEnv(entry.name(), entry.kind().name(), missing);
        }
        if (entry.transport() != null
                && (isBlank(entry.command()) && isBlank(entry.url()))) {
            return CapabilityState.transportUnavailable(entry.name(), entry.kind().name(), "");
        }
        return CapabilityState.usable(entry.name(), entry.kind().name());
    }

    /** 必需变量里当前环境缺失的那些（同名变量视为已具备，平台不读取其明文值）。 */
    private static List<String> missingEnv(List<String> required) {
        if (required == null || required.isEmpty()) {
            return List.of();
        }
        return required.stream()
                .filter(name -> name != null && !name.isBlank())
                .filter(name -> System.getenv(name) == null)
                .toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
