package top.rongx.agent.gateway.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvBinding;
import top.rongx.agent.domain.env.EnvBindingPort;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.EnvResource;
import top.rongx.agent.domain.env.EnvVariable;
import top.rongx.agent.domain.env.ProjectEnvFiles;
import top.rongx.agent.domain.env.StackAdapter;
import top.rongx.agent.domain.port.ProjectRepoLocator;

/**
 * Agent 环境绑定：扫描项目变量名 → 生成映射草案 → 人工确认 → 运行期校验。
 *
 * <p>只读取 `.env.example` 的 key 名，不读取 `.env` 的值；草案里不包含任何明文。
 */
@Service
public class EnvBindingService implements EnvBindingPort {

    private static final long TTL_MS = 24 * 60 * 60 * 1000L;

    private final EnvironmentStore envStore;
    private final ProjectRepoLocator repoLocator;
    private final ObjectMapper json = new ObjectMapper();

    public EnvBindingService(EnvironmentStore envStore, ProjectRepoLocator repoLocator) {
        this.envStore = envStore;
        this.repoLocator = repoLocator;
    }

    /** 生成草案（DRAFT），返回给设置页展示。 */
    public EnvBinding draft(String projectId, String env, String actor) {
        EffectiveEnvironment effective = envStore.effective(projectId, env);
        Map<String, EnvVariable> known = new LinkedHashMap<>();
        for (EnvVariable v : effective.vars()) {
            known.put(v.key(), v);
        }
        ProjectEnvFiles.Declared declared = scanDeclared(projectId);
        Map<String, String> sourceValues = declared.values();
        Set<String> required = declared.required();
        List<Map<String, Object>> keys = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        // 草案展示项目声明过的全部变量（含模板里的可选项），但只有必填项才计入 missing。
        Set<String> allKeys = new LinkedHashSet<>(declared.keys());
        allKeys.addAll(required);
        for (String key : allKeys) {
            EnvVariable v = known.get(key);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("source", v == null ? "UNKNOWN" : v.source());
            row.put("secret", v == null ? looksSecret(key) : v.secret());
            row.put("required", required.contains(key));
            // 只放值的哈希：既能发现"配置被改过"，又不在绑定记录里留下明文
            String plain = effective.values().get(key);
            if (plain == null || plain.isBlank()) {
                plain = sourceValues.get(key);
            }
            row.put("valueHash", plain == null || plain.isBlank() ? "" : sha256(plain));
            if (required.contains(key) && (plain == null || plain.isBlank())) {
                missing.add(key);
            }
            keys.add(row);
        }
        List<Map<String, Object>> warnings = new ArrayList<>();
        Set<String> projectKeys = new HashSet<>();
        for (EnvVariable v : envStore.overrides(projectId, env)) {
            projectKeys.add(v.key());
        }
        for (EnvResource r : effective.resources()) {
            // 容器没启动不算异常（部署时会自动启动）；只有缺连接信息才提示。
            if (r.missingFields().isEmpty() || !StackAdapter.isReferenced(r, effective.values())
                    || !StackAdapter.declaredByProject(r, projectKeys, sourceValues)) {
                continue;
            }
            Map<String, Object> warning = new LinkedHashMap<>();
            warning.put("code", "E-702");
            warning.put("resourceId", r.id());
            warning.put("message", r.name() + "（" + r.host() + ":" + r.port() + "）缺少连接信息："
                    + String.join("、", r.missingFields()));
            warnings.add(warning);
        }
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("projectId", projectId);
        mapping.put("env", env);
        mapping.put("keys", keys);
        mapping.put("missing", missing);
        mapping.put("warnings", warnings);
        mapping.put("effectiveHash", valuesDigest(effective.values()));
        String mappingJson;
        try {
            mappingJson = json.writeValueAsString(mapping);
        } catch (Exception e) {
            throw new IllegalStateException("生成映射草案失败：" + e.getMessage(), e);
        }
        String hash = sha256(projectId + "|" + env + "|" + mappingJson);
        EnvBinding binding = new EnvBinding(null, projectId, env, mappingJson, hash,
                actor == null ? "agent" : actor, null, 0, 0, "DRAFT");
        return envStore.saveBinding(binding);
    }

    /** 人工确认；24 小时内有效。 */
    public EnvBinding confirm(String bindingId, String confirmedBy) {
        EnvBinding binding = requireBinding(bindingId);
        if (!"DRAFT".equals(binding.status())) {
            return binding;
        }
        envStore.confirmBinding(bindingId, confirmedBy, System.currentTimeMillis() + TTL_MS);
        return requireBinding(bindingId);
    }

    @Override
    public java.util.Optional<EnvBinding> latestConfirmed(String projectId, String env) {
        return envStore.latestConfirmedBinding(projectId, env);
    }

    /** 运行期校验：必须已确认、未过期、项目/环境一致。 */
    public EnvBinding requireForRun(String bindingId, String projectId, String env) {
        EnvBinding binding = requireBinding(bindingId);
        if (!binding.projectId().equals(projectId) || !binding.env().equals(env)) {
            throw new IllegalArgumentException("E-703 环境绑定与本次运行不匹配（binding="
                    + binding.projectId() + "/" + binding.env() + "，run=" + projectId + "/" + env + "）");
        }
        if (!binding.confirmed()) {
            throw new IllegalArgumentException("E-703 环境绑定尚未人工确认：请先在设置页确认映射草案");
        }
        if (binding.expired()) {
            throw new IllegalArgumentException("E-703 环境绑定已过期：请重新生成并确认");
        }
        String expected = bindingHash(binding.mappingJson());
        if (expected != null) {
            String actual = valuesDigest(envStore.effective(projectId, env).values());
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException(
                        "E-703 环境变量在确认后已变更：请重新生成并确认绑定");
            }
        }
        return binding;
    }

    /** 读取草案里记录的 effectiveHash；旧记录没有该字段时返回 null（兼容）。 */
    private String bindingHash(String mappingJson) {
        try {
            Object value = json.readValue(mappingJson, Map.class).get("effectiveHash");
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 对 key + 值哈希做摘要，部署时与运行日志里的 env-snapshot 使用同一算法。 */
    static String valuesDigest(Map<String, String> values) {
        StringBuilder canonical = new StringBuilder();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> canonical.append(e.getKey()).append('=').append(sha256(e.getValue())).append('\n'));
        return sha256(canonical.toString());
    }

    private EnvBinding requireBinding(String bindingId) {
        return envStore.binding(bindingId).orElseThrow(() ->
                new IllegalArgumentException("环境绑定不存在：" + bindingId));
    }

    /**
     * 项目真正必需的变量名：以源码声明为准（entrypoint 的 {@code require}、
     * compose 的 {@code ${VAR:?}}），不再把 {@code .env.example} 里的可选项当必填。
     */
    public List<String> requiredKeys(String projectId) {
        return List.copyOf(scanDeclared(projectId).required());
    }

    /** 源码声明的变量值（只读；供自检判断"源码已有值"）。 */
    public Map<String, String> sourceValues(String projectId) {
        return scanDeclared(projectId).values();
    }

    private ProjectEnvFiles.Declared scanDeclared(String projectId) {
        Path repo = repoLocator.repoDir(projectId).orElse(null);
        if (repo == null) {
            return ProjectEnvFiles.Declared.empty();
        }
        return ProjectEnvFiles.scan(repo);
    }

    /** 变量名是否像敏感信息（设置页据此决定是否掩码）。 */
    public static boolean looksSecret(String key) {
        String k = key.toUpperCase(java.util.Locale.ROOT);
        return k.contains("PASSWORD") || k.contains("SECRET") || k.contains("TOKEN")
                || k.endsWith("_KEY");
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算映射哈希失败", e);
        }
    }
}
