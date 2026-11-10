package top.rongx.agent.domain.env;

import java.util.List;
import java.util.Map;

/**
 * 一次运行解析出的有效环境。
 *
 * <p>{@code values} 是明文，只允许执行面（部署器）读取；接口层必须使用 {@code vars}。
 *
 * @param projectId     项目
 * @param env           环境
 * @param network       部署网络（可空）
 * @param defaultPort   环境默认端口（0 = 未配置）
 * @param healthPath    环境默认健康路径（可空）
 * @param vars          掩码后的变量视图（含来源）
 * @param values        明文变量（环境模板 + 项目覆盖）
 * @param resources     该环境的中间件资源（用于预检）
 * @param templateCount 环境模板贡献的 key 数
 * @param overrideCount 项目覆盖贡献的 key 数
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
        int overrideCount) {

    public EffectiveEnvironment {
        vars = vars == null ? List.of() : List.copyOf(vars);
        values = values == null ? Map.of() : Map.copyOf(values);
        resources = resources == null ? List.of() : List.copyOf(resources);
    }
}
