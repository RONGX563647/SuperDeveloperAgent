package top.rongx.agent.domain.env;

import java.util.List;

/**
 * 一个 (项目, 环境) 的变量来源解释：每个键最终由哪一层生效、哪些层参与过。
 *
 * <p>{@code value} 与 {@link EnvVariable#value()} 一样是展示值（敏感项掩码）。
 * {@code layers} 按优先级从低到高排列：RESOURCE → TEMPLATE → OVERRIDE。
 *
 * @param projectId 项目
 * @param env       环境
 * @param vars      变量解释列表（按 key 排序）
 */
public record EnvResolution(String projectId, String env, List<Var> vars) {

    public EnvResolution {
        vars = vars == null ? List.of() : List.copyOf(vars);
    }

    /**
     * 单个键的解释。
     *
     * @param key    变量名
     * @param source 最终生效的来源（RESOURCE / TEMPLATE / OVERRIDE）
     * @param secret 是否敏感（值已掩码）
     * @param value  展示值
     * @param layers 参与过该键的层（低 → 高）
     */
    public record Var(String key, String source, boolean secret, String value,
                      List<String> layers) {
        public Var {
            layers = layers == null ? List.of() : List.copyOf(layers);
        }
    }
}
