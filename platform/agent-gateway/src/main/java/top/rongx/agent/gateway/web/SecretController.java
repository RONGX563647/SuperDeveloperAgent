package top.rongx.agent.gateway.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.env.EnvNames;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.SecretEntry;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * 项目密钥库（只存密文，接口只回名称/版本/掩码）。
 *
 * <p>环境变量写 {@code secret://name} 即可引用；部署前解析，Agent 工具永远看不到明文。
 */
@RestController
@RequestMapping("/api/v1/settings/projects/{projectId}/secrets")
public class SecretController {

    private final EnvironmentStore envStore;
    private final CurrentUser currentUser;

    public SecretController(EnvironmentStore envStore, CurrentUser currentUser) {
        this.envStore = envStore;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String projectId,
                                          @RequestParam(defaultValue = "test") String env) {
        currentUser.checkPermission("settings:read");
        return envStore.secrets(projectId, EnvNames.normalize(env)).stream().map(this::view).toList();
    }

    @PostMapping
    public Map<String, Object> save(@PathVariable String projectId,
                                    @RequestBody Map<String, Object> body) {
        currentUser.checkPermission("settings:write");
        String env = EnvNames.normalize(str(body, "env"));
        String name = str(body, "name");
        String value = str(body, "value");
        return view(envStore.saveSecret(projectId, env, name, value));
    }

    @PostMapping("/{name}/rotate")
    public Map<String, Object> rotate(@PathVariable String projectId, @PathVariable String name,
                                      @RequestBody Map<String, Object> body) {
        currentUser.checkPermission("settings:write");
        String env = EnvNames.normalize(str(body, "env"));
        return view(envStore.rotateSecret(projectId, env, name, str(body, "value")));
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> delete(@PathVariable String projectId, @PathVariable String name,
                                      @RequestParam(defaultValue = "test") String env) {
        currentUser.checkPermission("settings:write");
        String normalized = EnvNames.normalize(env);
        envStore.deleteSecret(projectId, normalized, name);
        return Map.of("ok", true, "name", name, "env", normalized);
    }

    private Map<String, Object> view(SecretEntry entry) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", entry.projectId());
        out.put("env", entry.env());
        out.put("name", entry.name());
        out.put("version", entry.version());
        out.put("value", "****");
        out.put("updatedAt", entry.updatedAt());
        return out;
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
