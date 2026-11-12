package top.rongx.agent.gateway.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.env.EnvNames;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.EnvVariable;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * 项目环境变量（部署时注入容器，不进 pipeline.yml / 仓库）。
 *
 * <p>存储已统一到 {@code project_env_overrides}（环境模板 + 项目覆盖模型）：
 * 这里写进去的变量会与「设置 → 环境模板」合并成有效变量，部署器注入的是同一份结果，
 * 不再出现"页面看到的"和"部署注入的"两套数据。
 *
 * <p>安全约定：
 * <ul>
 *   <li>值 AES-GCM 加密落库；敏感项读取只返回掩码（{@code ****abcd}），非敏感项返回明文；</li>
 *   <li>写操作仅 {@code settings:write}，读操作 {@code settings:read}；</li>
 *   <li>部署器通过 domain 的 {@code effective(projectId, env).values()} 取明文，
 *       网关层不提供"查看明文"接口。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/settings/projects")
public class ProjectEnvController {

    private final EnvironmentStore envStore;
    private final CurrentUser currentUser;

    public ProjectEnvController(EnvironmentStore envStore, CurrentUser currentUser) {
        this.envStore = envStore;
        this.currentUser = currentUser;
    }

    /** 某项目某环境的覆盖项（敏感项掩码）。 */
    @GetMapping("/{projectId}/envs")
    public List<Map<String, Object>> list(@PathVariable String projectId,
                                          @RequestParam(defaultValue = "test") String env) {
        currentUser.checkPermission("settings:read");
        return envStore.overrides(projectId, EnvNames.normalize(env)).stream().map(this::view).toList();
    }

    /**
     * 新增或修改变量。
     *
     * <p>body：{@code {env, key, value, secret}}；value 为空表示"保持原值"，
     * 用于只切换敏感标记的场景。新建时 value 必填。
     */
    @PutMapping("/{projectId}/envs")
    public Map<String, Object> save(@PathVariable String projectId,
                                    @RequestBody Map<String, Object> body) {
        currentUser.checkPermission("settings:write");
        String env = EnvNames.normalize(str(body, "env"));
        String key = str(body, "key");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("变量名必填");
        }
        boolean secret = !(body.get("secret") instanceof Boolean b) || b;
        EnvVariable saved = envStore.saveOverride(projectId, env, key.trim(),
                str(body, "value"), secret);
        return view(saved);
    }

    /** 删除变量；不存在时也返回成功（幂等）。 */
    @DeleteMapping("/{projectId}/envs/{key}")
    public Map<String, Object> delete(@PathVariable String projectId, @PathVariable String key,
                                      @RequestParam(defaultValue = "test") String env) {
        currentUser.checkPermission("settings:write");
        envStore.deleteOverride(projectId, EnvNames.normalize(env), key);
        return Map.of("ok", true, "key", key, "env", EnvNames.normalize(env));
    }

    private Map<String, Object> view(EnvVariable v) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", v.projectId());
        out.put("env", v.env());
        out.put("key", v.key());
        out.put("secret", v.secret());
        out.put("value", v.value());
        out.put("source", v.source());
        out.put("valueKind", v.valueKind());
        out.put("secretRef", v.secretRef());
        out.put("updatedBy", null);
        out.put("updatedAt", v.updatedAt());
        return out;
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
