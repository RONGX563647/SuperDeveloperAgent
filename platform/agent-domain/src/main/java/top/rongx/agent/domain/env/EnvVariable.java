package top.rongx.agent.domain.env;

/**
 * 环境变量/项目覆盖的展示视图。
 *
 * <p>{@code value} 对敏感项是掩码（{@code ****abcd}），非敏感项是明文；
 * 真实明文只在 {@link EffectiveEnvironment#values()} 中，仅供部署器使用。
 * {@code secretRef} 非空表示该变量引用密钥库（{@code secret://name}），
 * 此时 {@code value} 只展示引用名，不展示密钥值。
 *
 * @param projectId 项目 id（环境模板变量为 null）
 * @param env       环境
 * @param key       变量名
 * @param secret    是否敏感
 * @param value     展示值
 * @param source    RESOURCE / TEMPLATE / OVERRIDE
 * @param updatedAt 修改时间
 * @param valueKind INLINE（直接值）/ SECRET_REF（密钥引用）
 * @param secretRef 密钥引用名（可空）
 */
public record EnvVariable(
        String projectId,
        String env,
        String key,
        boolean secret,
        String value,
        String source,
        long updatedAt,
        String valueKind,
        String secretRef) {

    public static final String KIND_INLINE = "INLINE";
    public static final String KIND_SECRET_REF = "SECRET_REF";

    public EnvVariable {
        valueKind = valueKind == null || valueKind.isBlank() ? KIND_INLINE : valueKind;
        secretRef = secretRef == null || secretRef.isBlank() ? null : secretRef;
    }

    /** 兼容旧调用：无引用。 */
    public EnvVariable(String projectId, String env, String key, boolean secret,
                       String value, String source, long updatedAt) {
        this(projectId, env, key, secret, value, source, updatedAt, KIND_INLINE, null);
    }
}
