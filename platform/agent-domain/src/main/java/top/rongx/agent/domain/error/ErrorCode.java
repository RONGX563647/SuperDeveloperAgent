package top.rongx.agent.domain.error;

/**
 * 平台对外错误码。
 *
 * <p>所有业务代码必须通过本枚举获取错误码（{@link #code()}），禁止再写字面量。
 * 码段约定：1xx 构建/执行，2xx 配方与参数，3xx 运行时，4xx 产物，
 * 5xx 部署，6xx 晋升与门禁，7xx 环境与策略，8xx 项目接入。
 *
 * <p>抛异常时必须使用 {@link PlatformException} 对应分组的业务异常类，
 * 禁止再把码值拼进通用异常。每个码段额外提供 INVALID_INPUT / INVALID_STATE /
 * INTERNAL 三个兜底码，用于迁移既有的参数校验与状态断言，并保持原 HTTP 语义。
 */
public enum ErrorCode {

    /** 构建/执行失败（进程非零退出，真正的编译动作）。 */
    COMPILE_FAILED("E-101", ErrorKind.INPUT),
    /** 测试未通过（backend_test / frontend_test 断言失败）。 */
    TEST_FAILED("E-102", ErrorKind.INPUT),
    /** 镜像构建失败（docker build / image_package 非零退出）。 */
    IMAGE_BUILD_FAILED("E-103", ErrorKind.INPUT),
    /** 脚本/任意非 mvn、pnpm、docker build 命令非零退出。 */
    SCRIPT_FAILED("E-104", ErrorKind.INPUT),
    /** 构建阶段输入非法。 */
    BUILD_INVALID_INPUT("E-190", ErrorKind.INPUT),
    /** 构建阶段状态冲突。 */
    BUILD_INVALID_STATE("E-191", ErrorKind.STATE),
    /** 构建阶段内部失败。 */
    BUILD_INTERNAL("E-199", ErrorKind.INTERNAL),

    /** argv 为空或首元素不在白名单。 */
    ARGV_INVALID("E-201", ErrorKind.INPUT),
    /** 出现 shell 解释器或元字符。 */
    SHELL_REJECTED("E-202", ErrorKind.INPUT),
    /** 命令不存在或无法启动。 */
    EXECUTABLE_NOT_FOUND("E-209", ErrorKind.INPUT),
    /** 禁止 mvn deploy。 */
    MVN_DEPLOY_FORBIDDEN("E-211", ErrorKind.INPUT),
    /** 平台侧无法准备流水线定义。 */
    PIPELINE_DEFINITION_UNAVAILABLE("E-220", ErrorKind.INPUT),
    /** pipeline.yml 语法或结构非法。 */
    PIPELINE_DEFINITION_INVALID("E-221", ErrorKind.INPUT),
    /** 配方/流水线输入非法。 */
    RECIPE_INVALID_INPUT("E-290", ErrorKind.INPUT),
    /** 配方/流水线状态冲突。 */
    RECIPE_INVALID_STATE("E-291", ErrorKind.STATE),
    /** 配方/流水线内部失败。 */
    RECIPE_INTERNAL("E-299", ErrorKind.INTERNAL),

    /** 执行超时。 */
    TIMEOUT("E-302", ErrorKind.INTERNAL),
    /** 执行被取消或中断。 */
    CANCELLED("E-303", ErrorKind.INTERNAL),
    /** Agent 执行失败（模型超时、调用异常、未按 schema 返回）。 */
    AGENT_EXECUTION_FAILED("E-304", ErrorKind.INTERNAL),
    /** 运行时输入非法。 */
    RUN_INVALID_INPUT("E-390", ErrorKind.INPUT),
    /** 运行时状态冲突。 */
    RUN_INVALID_STATE("E-391", ErrorKind.STATE),
    /** 运行时内部失败。 */
    RUN_INTERNAL("E-399", ErrorKind.INTERNAL),

    /**
     * 声明的产物未生成；历史上本机健康检查未通过也复用该码。
     */
    ARTIFACT_MISSING("E-401", ErrorKind.INTERNAL),
    /** 产物输入非法。 */
    ARTIFACT_INVALID_INPUT("E-490", ErrorKind.INPUT),
    /** 产物状态冲突。 */
    ARTIFACT_INVALID_STATE("E-491", ErrorKind.STATE),
    /** 产物内部失败。 */
    ARTIFACT_INTERNAL("E-499", ErrorKind.INTERNAL),

    /** 部署前置条件缺失（无镜像 / 无状态目录 / 无 jar）。 */
    DEPLOY_PREREQUISITE_MISSING("E-501", ErrorKind.INTERNAL),
    /** 部署启动失败。 */
    DEPLOY_START_FAILED("E-502", ErrorKind.INTERNAL),
    /** 无法确定部署目标（既无镜像也无可运行产物）。 */
    DEPLOY_TARGET_UNDETERMINED("E-504", ErrorKind.INTERNAL),
    /** 部署目标未配置（无可用服务器或认证信息）。 */
    DEPLOY_TARGET_UNCONFIGURED("E-505", ErrorKind.INTERNAL),
    /** pipeline.yml 缺少 deploy 段或目标非法。 */
    DEPLOY_SECTION_MISSING("E-506", ErrorKind.INTERNAL),
    /** 缺少可回滚的历史版本。 */
    ROLLBACK_VERSION_MISSING("E-507", ErrorKind.INTERNAL),
    /** 部署形态声明的能力当前引擎未实现（例如多服务回滚）：不要与"版本缺失"混用。 */
    CAPABILITY_UNSUPPORTED("E-510", ErrorKind.INTERNAL),
    /** 部署定义与默认分支不一致。 */
    PIPELINE_DEFINITION_UNMERGED("E-508", ErrorKind.INTERNAL),
    /** 缺少 deploy.env-required 声明的必填变量。 */
    ENV_REQUIRED_MISSING("E-509", ErrorKind.INTERNAL),
    /** 部署输入非法。 */
    DEPLOY_INVALID_INPUT("E-590", ErrorKind.INPUT),
    /** 部署状态冲突。 */
    DEPLOY_INVALID_STATE("E-591", ErrorKind.STATE),
    /** 部署内部失败。 */
    DEPLOY_INTERNAL("E-599", ErrorKind.INTERNAL),

    /**
     * 生产晋升门禁未通过；历史上本机 dev 启动前置条件失败也复用该码。
     */
    ENV_NOT_PROMOTED("E-601", ErrorKind.STATE),
    /**
     * 缺少人工确认；历史上本机 dev 前端构建失败也复用该码。
     */
    CONFIRM_REQUIRED("E-602", ErrorKind.STATE),
    /** 本机 dev 前端静态服务器启动失败。 */
    FRONTEND_START_FAILED("E-603", ErrorKind.STATE),
    /** 本机 dev 健康检查未通过。 */
    HEALTH_CHECK_FAILED("E-604", ErrorKind.STATE),
    /** 晋升门禁输入非法。 */
    PROMOTION_INVALID_INPUT("E-690", ErrorKind.INPUT),
    /** 晋升门禁状态冲突。 */
    PROMOTION_INVALID_STATE("E-691", ErrorKind.STATE),
    /** 晋升门禁内部失败。 */
    PROMOTION_INTERNAL("E-699", ErrorKind.INTERNAL),

    /** 中间件无法启动或连接信息缺失。 */
    MIDDLEWARE_START_FAILED("E-701", ErrorKind.INPUT),
    /** 环境准备失败。 */
    ENV_PREPARE_FAILED("E-702", ErrorKind.INPUT),
    /** 环境绑定缺失、未确认、已过期或与运行不匹配。 */
    ENV_BINDING_INVALID("E-703", ErrorKind.INPUT),
    /** 密钥引用无法解析；远端部署端口耗尽也复用该码。 */
    SECRET_UNRESOLVED("E-704", ErrorKind.INPUT),
    /** 本机 Docker 不可用或镜像不存在。 */
    LOCAL_IMAGE_UNAVAILABLE("E-705", ErrorKind.INPUT),
    /** dry-run 报告已过期。 */
    DRY_RUN_STALE("E-706", ErrorKind.INPUT),
    /** 当前环境策略拒绝该操作。 */
    ENV_POLICY_DENIED("E-707", ErrorKind.STATE),
    /** dry-run 未通过，禁止 apply。 */
    DRY_RUN_FAILED("E-708", ErrorKind.INPUT),
    /** 破坏性动作触发频次上限。 */
    RATE_LIMITED("E-709", ErrorKind.STATE),
    /** 环境输入非法。 */
    ENVIRONMENT_INVALID_INPUT("E-790", ErrorKind.INPUT),
    /** 环境状态冲突。 */
    ENVIRONMENT_INVALID_STATE("E-791", ErrorKind.STATE),
    /** 环境内部失败。 */
    ENVIRONMENT_INTERNAL("E-799", ErrorKind.INTERNAL),

    /** 项目接入失败（目录、仓库或 git 操作不可用）。 */
    INTAKE_FAILED("E-801", ErrorKind.INTERNAL),
    /** 项目接入输入非法。 */
    INTAKE_INVALID_INPUT("E-890", ErrorKind.INPUT),
    /** 项目接入状态冲突。 */
    INTAKE_INVALID_STATE("E-891", ErrorKind.STATE),
    /** 项目接入内部失败。 */
    INTAKE_INTERNAL("E-899", ErrorKind.INTERNAL);

    private final String code;
    private final ErrorKind kind;

    ErrorCode(String code) {
        this(code, ErrorKind.INTERNAL);
    }

    ErrorCode(String code, ErrorKind kind) {
        this.code = code;
        this.kind = kind;
    }

    /** 对外错误码字符串，例如 {@code E-707}。 */
    public String code() {
        return code;
    }

    /** 错误码所属业务分组，由码段决定。 */
    public ErrorGroup group() {
        return ErrorGroup.of(code.charAt(2) - '0');
    }

    /** 错误码语义类型，决定 HTTP 状态。 */
    public ErrorKind kind() {
        return kind;
    }
}
