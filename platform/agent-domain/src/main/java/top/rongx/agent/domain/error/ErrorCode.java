package top.rongx.agent.domain.error;

/**
 * 平台对外错误码。
 *
 * <p>所有业务代码必须通过本枚举获取错误码（{@link #code()}），禁止再写字面量。
 * 码段约定：1xx 构建/执行，2xx 配方与参数，3xx 运行时，4xx 产物，
 * 5xx 部署，6xx 晋升与门禁，7xx 环境与策略，8xx 项目接入。
 *
 * <p>抛异常时必须使用 {@link PlatformException} 对应分组的业务异常类，
 * 禁止再把码值拼进通用异常。
 */
public enum ErrorCode {

    /** 构建/执行失败（进程非零退出）。 */
    COMPILE_FAILED("E-101"),

    /** argv 为空或首元素不在白名单。 */
    ARGV_INVALID("E-201"),
    /** 出现 shell 解释器或元字符。 */
    SHELL_REJECTED("E-202"),
    /** 命令不存在或无法启动。 */
    EXECUTABLE_NOT_FOUND("E-209"),
    /** 禁止 mvn deploy。 */
    MVN_DEPLOY_FORBIDDEN("E-211"),
    /** 平台侧无法准备流水线定义。 */
    PIPELINE_DEFINITION_UNAVAILABLE("E-220"),
    /** pipeline.yml 语法或结构非法。 */
    PIPELINE_DEFINITION_INVALID("E-221"),

    /** 执行超时。 */
    TIMEOUT("E-302"),
    /** 执行被取消或中断。 */
    CANCELLED("E-303"),
    /** Agent 执行失败（模型超时、调用异常、未按 schema 返回）。 */
    AGENT_EXECUTION_FAILED("E-304"),

    /**
     * 声明的产物未生成；历史上本机健康检查未通过也复用该码。
     */
    ARTIFACT_MISSING("E-401"),

    /** 部署前置条件缺失（无镜像 / 无状态目录 / 无 jar）。 */
    DEPLOY_PREREQUISITE_MISSING("E-501"),
    /** 部署启动失败。 */
    DEPLOY_START_FAILED("E-502"),
    /** 无法确定部署目标（既无镜像也无可运行产物）。 */
    DEPLOY_TARGET_UNDETERMINED("E-504"),
    /** 部署目标未配置（无可用服务器或认证信息）。 */
    DEPLOY_TARGET_UNCONFIGURED("E-505"),
    /** pipeline.yml 缺少 deploy 段或目标非法。 */
    DEPLOY_SECTION_MISSING("E-506"),
    /** 缺少可回滚的历史版本。 */
    ROLLBACK_VERSION_MISSING("E-507"),
    /** 部署定义与默认分支不一致。 */
    PIPELINE_DEFINITION_UNMERGED("E-508"),
    /** 缺少 deploy.env-required 声明的必填变量。 */
    ENV_REQUIRED_MISSING("E-509"),

    /**
     * 生产晋升门禁未通过；历史上本机 dev 启动前置条件失败也复用该码。
     */
    ENV_NOT_PROMOTED("E-601"),
    /**
     * 缺少人工确认；历史上本机 dev 前端构建失败也复用该码。
     */
    CONFIRM_REQUIRED("E-602"),
    /** 本机 dev 前端静态服务器启动失败。 */
    FRONTEND_START_FAILED("E-603"),
    /** 本机 dev 健康检查未通过。 */
    HEALTH_CHECK_FAILED("E-604"),

    /** 中间件无法启动或连接信息缺失。 */
    MIDDLEWARE_START_FAILED("E-701"),
    /** 环境准备失败。 */
    ENV_PREPARE_FAILED("E-702"),
    /** 环境绑定缺失、未确认、已过期或与运行不匹配。 */
    ENV_BINDING_INVALID("E-703"),
    /** 密钥引用无法解析；远端部署端口耗尽也复用该码。 */
    SECRET_UNRESOLVED("E-704"),
    /** 本机 Docker 不可用或镜像不存在。 */
    LOCAL_IMAGE_UNAVAILABLE("E-705"),
    /** dry-run 报告已过期。 */
    DRY_RUN_STALE("E-706"),
    /** 当前环境策略拒绝该操作。 */
    ENV_POLICY_DENIED("E-707"),
    /** dry-run 未通过，禁止 apply。 */
    DRY_RUN_FAILED("E-708"),
    /** 破坏性动作触发频次上限。 */
    RATE_LIMITED("E-709"),

    /** 项目接入失败（目录、仓库或 git 操作不可用）。 */
    INTAKE_FAILED("E-801");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    /** 对外错误码字符串，例如 {@code E-707}。 */
    public String code() {
        return code;
    }

    /** 错误码所属业务分组，由码段决定。 */
    public ErrorGroup group() {
        return ErrorGroup.of(code.charAt(2) - '0');
    }
}
