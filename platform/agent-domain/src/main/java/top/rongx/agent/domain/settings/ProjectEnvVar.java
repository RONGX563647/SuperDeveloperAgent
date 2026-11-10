package top.rongx.agent.domain.settings;

/**
 * 项目环境变量（部署时注入容器的 KEY=VALUE）。
 *
 * <p>值是**只写不读**的：列表返回时 {@code value} 对敏感项是掩码（如 {@code ****3456}），
 * 非敏感项返回明文（方便核对 DB_HOST 这类配置）。明文只允许部署器通过
 * {@link ProjectEnvVarRepository#resolve(String, String)} 取用，接口层永不返回敏感明文。
 *
 * @param projectId 项目
 * @param env       环境（dev / test / prod）
 * @param key       变量名（必须符合环境变量命名规则）
 * @param secret    是否敏感；敏感项列表里不显示明文
 * @param value     展示值（敏感项为掩码，非敏感项为明文）
 * @param updatedBy 最后修改人
 * @param updatedAt 最后修改时间（epoch ms）
 */
public record ProjectEnvVar(
        String projectId,
        String env,
        String key,
        boolean secret,
        String value,
        String updatedBy,
        long updatedAt) {
}
