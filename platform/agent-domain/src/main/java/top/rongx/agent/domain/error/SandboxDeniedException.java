package top.rongx.agent.domain.error;

import java.util.List;

/**
 * 沙箱拒绝执行：隔离策略不允许这次动作。
 *
 * <p><b>为什么单独一个类型</b>：沙箱拒绝与「命令失败」是两件事。
 * 把「网络不在白名单」「想挂载宿主目录」「想用特权镜像」这类拒绝压成普通
 * 非零退出码，模型与用户只能看到一句难懂的 stderr，无法判断该改配置还是改代码。
 *
 * <p>结构化正文给出：拒绝原因码、命中的策略、当前生效的隔离参数、可采取的动作，
 * 让流水线页面、自愈 Agent 与设置页共用同一份判定。
 *
 * <p>错误码固定 {@link ErrorCode#ENV_POLICY_DENIED}（E-707，环境与策略段）：
 * 拒绝是「策略拒绝」而不是「命令失败」，HTTP 语义也因此是状态冲突而不是 5xx。
 */
public class SandboxDeniedException extends PlatformException {

    /** 稳定原因码：前端、自愈与日志都按它分类。 */
    public static final String REASON_CODE = "SANDBOX_DENIED";

    /** 拒绝类别：越出隔离边界的动作分别来自不同配置项，需要分别给出建议。 */
    public enum Rule {
        /** 网络档位不允许（例如要求外联但策略是 none）。 */
        NETWORK,
        /** 追加了宿主挂载（只有工作区与缓存卷允许）。 */
        MOUNT,
        /** 要求特权模式 / 额外 capability / 关闭 no-new-privileges。 */
        PRIVILEGE,
        /** 镜像引用非法或来自不可信的运行期输入。 */
        IMAGE,
        /** 运行时不满足（要求的 gVisor/runtime 不可用）。 */
        RUNTIME,
        /** 资源上限缺失或超出配置允许的上限。 */
        RESOURCE,
        /** 其它未归类违规（保留给新增策略，避免调用方因为枚举不匹配而丢信息）。 */
        POLICY
    }

    private final Rule rule;
    private final String detail;
    private final List<String> effective;
    private final String action;

    public SandboxDeniedException(Rule rule, String detail, List<String> effective, String action) {
        super(ErrorGroup.ENVIRONMENT, ErrorCode.ENV_POLICY_DENIED,
                buildMessage(rule, detail), null);
        this.rule = rule;
        this.detail = detail;
        this.effective = List.copyOf(effective);
        this.action = action;
    }

    public Rule rule() {
        return rule;
    }

    public String detail() {
        return detail;
    }

    /** 当前生效的隔离参数（审计与排障用）。 */
    public List<String> effective() {
        return effective;
    }

    /** 建议的下一步（可操作，而不是只报错）。 */
    public String action() {
        return action;
    }

    /** 结构化正文：调用方原样回给模型或写进运行日志。 */
    public String structured() {
        return """
                %s
                rule: %s
                detail: %s
                effective: %s
                action: %s
                """.formatted(REASON_CODE, rule.name(), detail,
                String.join("; ", effective), action).strip();
    }

    private static String buildMessage(Rule rule, String detail) {
        return "沙箱拒绝执行（" + rule.name() + "）：" + detail;
    }
}
