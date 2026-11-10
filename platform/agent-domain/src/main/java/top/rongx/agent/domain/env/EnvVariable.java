package top.rongx.agent.domain.env;

/**
 * 环境变量/项目覆盖的展示视图。
 *
 * <p>{@code value} 对敏感项是掩码（{@code ****abcd}），非敏感项是明文；
 * 真实明文只在 {@link EffectiveEnvironment#values()} 中，仅供部署器使用。
 *
 * @param projectId 项目 id（环境模板变量为 null）
 * @param env       环境
 * @param key       变量名
 * @param secret    是否敏感
 * @param value     展示值
 * @param source    RESOURCE / TEMPLATE / OVERRIDE
 * @param updatedAt 修改时间
 */
public record EnvVariable(
        String projectId,
        String env,
        String key,
        boolean secret,
        String value,
        String source,
        long updatedAt) {
}
