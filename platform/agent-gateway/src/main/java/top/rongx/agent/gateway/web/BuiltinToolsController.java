package top.rongx.agent.gateway.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.tools.AgentToolCatalog;

/**
 * 内置工具目录（设置页/会话能力页「本 Agent 内置工具」）。
 *
 * <p>数据来自真实 Toolkit 的投影 {@link AgentToolCatalog}，与导出到
 * {@code data/api/agent-tools.json} 的是同一份事实源——前端展示的 90+ 个工具
 * 就是本轮对话真正可调用的工具，不是另写的一份清单。
 *
 * <p>内置工具随 Agent 发布，不可在会话里开关（那是 Skill/MCP 的事），因此这里只提供
 * 只读投影：名称、描述、只读标记、所属工具面与参数数量。参数体不下发，避免接口臃肿。
 */
@RestController
@RequestMapping("/api/v1/settings/builtin-tools")
public class BuiltinToolsController {

    private final AgentToolCatalog catalog;
    private final CurrentUser currentUser;

    public BuiltinToolsController(AgentToolCatalog catalog, CurrentUser currentUser) {
        this.catalog = catalog;
        this.currentUser = currentUser;
    }

    /** 工具目录：按工具面分组前的扁平清单，前端自行分组/搜索。 */
    @GetMapping
    public Map<String, Object> list() {
        currentUser.requireUserId();
        currentUser.checkPermission("settings:read");

        List<Map<String, Object>> tools = new ArrayList<>();
        for (AgentToolCatalog.Entry entry : catalog.catalog().entries().values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.name());
            row.put("description", entry.description() == null ? "" : entry.description());
            row.put("readOnly", entry.readOnly());
            row.put("toolkits", entry.toolkits());
            row.put("parameterCount", entry.parameters() == null ? 0 : entry.parameters().size());
            tools.add(row);
        }
        tools.sort(Comparator.comparing(m -> (String) m.get("name")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", tools.size());
        out.put("tools", tools);
        return out;
    }
}
