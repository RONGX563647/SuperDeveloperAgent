package top.rongx.agent.domain.env;

import java.util.List;

/** 环境名口径：全平台只认 dev/test/prod；缺省按 test，绝不默认落到 prod。 */
public final class EnvNames {

    public static final List<String> ALL = List.of("dev", "test", "prod");

    /** 用户输入/接口参数：去空格、转小写，缺省 test；未知值直接拒绝。 */
    public static String normalize(String env) {
        String value = env == null || env.isBlank() ? "test" : env.trim().toLowerCase();
        if (!ALL.contains(value)) {
            throw new IllegalArgumentException("环境只能是 dev / test / prod：" + env);
        }
        return value;
    }

    /** 落库/内部边界：必须是规范值，不做默认与大小写兜底。 */
    public static void requireExact(String env) {
        if (!ALL.contains(env)) {
            throw new IllegalArgumentException("环境只能是 dev / test / prod：" + env);
        }
    }

    private EnvNames() {
    }
}
