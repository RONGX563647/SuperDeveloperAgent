package top.rongx.agent.domain.error;

/**
 * 错误码的业务分组，与 {@link ErrorCode} 的码段一一对应。
 *
 * <p>每个分组都绑定一个业务异常类（见 {@link PlatformException} 的 permits 列表），
 * 异常类只接受本组错误码，跨组使用会在构造时失败。
 */
public enum ErrorGroup {

    /** 1xx 构建/执行。 */
    BUILD(1),
    /** 2xx 配方与参数。 */
    RECIPE(2),
    /** 3xx 运行时。 */
    RUN(3),
    /** 4xx 产物。 */
    ARTIFACT(4),
    /** 5xx 部署。 */
    DEPLOY(5),
    /** 6xx 晋升与门禁。 */
    PROMOTION(6),
    /** 7xx 环境与策略。 */
    ENVIRONMENT(7),
    /** 8xx 项目接入。 */
    INTAKE(8);

    private final int digit;

    ErrorGroup(int digit) {
        this.digit = digit;
    }

    public int digit() {
        return digit;
    }

    /** 按码段数字解析分组；未知码段直接拒绝，防止错误码越界。 */
    public static ErrorGroup of(int digit) {
        for (ErrorGroup group : values()) {
            if (group.digit == digit) {
                return group;
            }
        }
        throw new IllegalArgumentException("未知错误码段：" + digit); // spec-ignore: R15 契约守卫，非法码段必须在枚举层拒绝
    }
}
