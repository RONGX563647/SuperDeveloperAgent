package top.rongx.agent.domain.env;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次运行解析出的有效环境。
 *
 * <p>{@code values} 是明文，只允许执行面（部署器）读取；接口层必须使用 {@code vars}。
 * 密钥引用已解析的明文也只放在 {@code values}，任何工具/接口都不得返回。
 *
 * @param projectId      项目
 * @param env            环境
 * @param network        部署网络（可空）
 * @param defaultPort    环境默认端口（0 = 未配置）
 * @param healthPath     环境默认健康路径（可空）
 * @param vars           掩码后的变量视图（含来源）
 * @param values         明文变量（环境模板 + 项目覆盖，密钥引用已解析）
 * @param resources      该环境的中间件资源（用于预检）
 * @param templateCount  环境模板贡献的 key 数
 * @param overrideCount  项目覆盖贡献的 key 数
 * @param missingSecrets 无法解析的密钥引用名（部署前必须失败）
 * @param secretVersions 变量 key → 密钥版本（非引用变量不在其中）
 */
public record EffectiveEnvironment(
        String projectId,
        String env,
        String network,
        int defaultPort,
        String healthPath,
        List<EnvVariable> vars,
        Map<String, String> values,
        List<EnvResource> resources,
        int templateCount,
        int overrideCount,
        Set<String> missingSecrets,
        Map<String, Integer> secretVersions) {

    public EffectiveEnvironment {
        vars = vars == null ? List.of() : List.copyOf(vars);
        values = values == null ? Map.of() : Map.copyOf(values);
        resources = resources == null ? List.of() : List.copyOf(resources);
        missingSecrets = missingSecrets == null ? Set.of() : Set.copyOf(missingSecrets);
        secretVersions = secretVersions == null ? Map.of() : Map.copyOf(secretVersions);
    }

    /** 兼容旧构造：无密钥引用。 */
    public EffectiveEnvironment(String projectId, String env, String network, int defaultPort,
                                String healthPath, List<EnvVariable> vars,
                                Map<String, String> values, List<EnvResource> resources,
                                int templateCount, int overrideCount) {
        this(projectId, env, network, defaultPort, healthPath, vars, values, resources,
                templateCount, overrideCount, Set.of(), Map.of());
    }

    /**
     * 用于摘要/快照的材料：密钥引用只暴露“引用名@版本”，不暴露解析后的明文。
     *
     * <p>这样绑定哈希与运行快照不会把密钥值的哈希写进证据链，轮换后也能自然失效。
     */
    public Map<String, String> digestValues() {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> refs = new LinkedHashMap<>();
        for (EnvVariable v : vars) {
            if (v.secretRef() != null) {
                refs.put(v.key(), v.secretRef());
            }
        }
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    String ref = refs.get(e.getKey());
                    if (ref != null) {
                        Integer version = secretVersions.get(e.getKey());
                        out.put(e.getKey(), "ref:" + ref + "@" + (version == null ? 0 : version));
                    } else {
                        out.put(e.getKey(), e.getValue());
                    }
                });
        return out;
    }
}
