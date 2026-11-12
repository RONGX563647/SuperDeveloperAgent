package top.rongx.agent.gateway.web;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.tools.ConfigResolver;

/**
 * 配置解析 API：与 Agent 工具共用同一套优先级和扫描逻辑。
 *
 * <p>返回 {@code status=NEED_USER_INPUT} 时，前端应展示问题并等待用户回答；
 * 用户回答后再把选择写入项目环境覆盖（或创建密钥引用）。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/config")
public class ConfigController {

    private final ConfigResolver resolver;
    private final CurrentUser currentUser;

    public ConfigController(ConfigResolver resolver, CurrentUser currentUser) {
        this.resolver = resolver;
        this.currentUser = currentUser;
    }

    @GetMapping("/resolve")
    public Map<String, Object> resolve(@PathVariable String projectId,
                                       @RequestParam(defaultValue = "test") String env,
                                       @RequestParam(required = false) String keys) {
        currentUser.checkPermission("settings:read");
        String target = ConfigResolver.normalize(env);
        List<String> requested = keys == null || keys.isBlank()
                ? List.of()
                : Arrays.stream(keys.split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty()).toList();
        ProjectContext ctx = new ProjectContext(projectId, target,
                currentUser.requireUserId(), null, null);
        ConfigResolver.Resolution r = resolver.resolve(ctx, target, requested);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.needsUserInput() ? "NEED_USER_INPUT" : "READY");
        out.put("projectId", r.projectId());
        out.put("env", r.env());
        out.put("resolved", r.resolved());
        out.put("resolvedSource", r.resolvedSource());
        out.put("missing", r.missing());
        out.put("conflicts", r.conflicts());
        out.put("scannedFiles", r.scannedFiles());
        List<Map<String, Object>> candidates = r.candidates().entrySet().stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", e.getKey());
                    row.put("options", e.getValue().stream().map(c -> {
                        Map<String, Object> o = new LinkedHashMap<>();
                        o.put("value", c.secret() ? "****" : c.value());
                        o.put("source", c.source());
                        o.put("secret", c.secret());
                        o.put("envHint", c.envHint());
                        return o;
                    }).toList());
                    return row;
                }).toList();
        out.put("candidates", candidates);
        out.put("summary", r.summary());
        return out;
    }
}
