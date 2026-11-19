package top.rongx.agent.domain.pipeline;

import java.util.Locale;

/**
 * git 标签在本次发布中的真实状态。
 *
 * <p><b>为什么需要枚举而不是字符串</b>：「打 tag 未生效」的根因不是没打，而是
 * <b>推送失败被当成了成功</b>——历史里显示版本号，远端却查不到对应标签。
 * 状态必须显式区分「远端可见」与「只在本机」，并且能给出一句原因码。
 *
 * <p>线值（wireName）与前端 {@code RunView.tagStatus} 对齐，保持向后兼容。
 */
public enum TagState {

    /** 远端可见：已推送到 origin。 */
    PUSHED("PUSHED", "", "标签已在远端可见"),
    /** 只在本机：本地建好了，推送失败（通常是凭据不足）。 */
    LOCAL_ONLY("LOCAL", "TAG_PUSH_CREDENTIAL_MISSING",
            "标签只在本机创建：远端推送失败（检查「设置 → 高级设置 → Git 应用授权」的账号凭据），"
                    + "补齐凭据后可在构建历史里点「重推标签」"),
    /** 创建失败：连本地标签都没有。 */
    FAILED("FAILED", "TAG_CREATE_FAILED", "标签创建失败，见构建日志中的 [tag] 行"),
    /** 未打标签：本次是快照构建。 */
    SNAPSHOT("SNAPSHOT", "", "本次为快照构建，不打标签"),
    /** 版本号与分支不匹配等前置拒绝：标签不会被创建。 */
    REJECTED("REJECTED", "TAG_PRECONDITION_REJECTED", "版本线与目标分支不一致，已拒绝打标签");

    private final String wireName;
    private final String reasonCode;
    private final String hint;

    TagState(String wireName, String reasonCode, String hint) {
        this.wireName = wireName;
        this.reasonCode = reasonCode;
        this.hint = hint;
    }

    /** 与前端/历史字段一致的线值。 */
    public String wireName() {
        return wireName;
    }

    /** 稳定原因码；空串表示无需解释（成功或本就不打标签）。 */
    public String reasonCode() {
        return reasonCode;
    }

    /** 面向用户的处理建议。 */
    public String hint() {
        return hint;
    }

    /** 远端是否可见：只有 PUSHED 才算「生效」。 */
    public boolean remoteVisible() {
        return this == PUSHED;
    }

    /** 是否需要用户介入（凭据缺失 / 创建失败 / 前置拒绝）。 */
    public boolean needsAction() {
        return this == LOCAL_ONLY || this == FAILED || this == REJECTED;
    }

    /**
     * 从线值解析；未知值按 {@link #SNAPSHOT} 处理（历史数据兼容，不抛异常）。
     *
     * @param wire 线值，如 PUSHED / LOCAL / FAILED
     */
    public static TagState fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return SNAPSHOT;
        }
        String value = wire.trim().toUpperCase(Locale.ROOT);
        for (TagState state : values()) {
            if (state.wireName.equals(value) || state.name().equals(value)) {
                return state;
            }
        }
        // 兼容旧值 LOCAL_ONLY 的写法
        return "LOCAL_ONLY".equals(value) ? LOCAL_ONLY : SNAPSHOT;
    }
}
