package top.rongx.agent.domain.settings;

import java.util.List;
import java.util.Locale;

/**
 * 一项能力的可用状态：名称之外还必须回答「能不能用、为什么不能用」。
 *
 * <p><b>问题</b>：能力接口原来只给名字，用户看到列表里有一项，
 * 装完之后会话里却用不上，只能猜是没装好、缺环境变量、还是平台没挂上它。
 *
 * <p><b>口径</b>：四件事分开说，不要压成一个布尔：
 * <ul>
 *   <li>{@code enabled}：用户是否开启（配置文件里的开关）；</li>
 *   <li>{@code installed}：能力本体是否已落地（MCP 已登记 / Skill 目录存在）；</li>
 *   <li>{@code available}：现在这一轮会话是否真的能用（= 已开启 + 已落地 + 依赖就绪）；</li>
 *   <li>{@code reason}：不可用时的一句话原因 + 稳定原因码。</li>
 * </ul>
 *
 * @param name           能力名（会话里引用它的名字）
 * @param kind           MCP / SKILL
 * @param enabled        用户是否开启
 * @param installed      是否已安装
 * @param available      当前是否可用
 * @param reasonCode     稳定原因码（可用时为空）
 * @param reason         可读原因（可用时为空）
 * @param missingEnv     缺少的环境变量名（只有缺变量时非空）
 */
public record CapabilityState(
        String name,
        String kind,
        boolean enabled,
        boolean installed,
        boolean available,
        String reasonCode,
        String reason,
        List<String> missingEnv) {

    /** 不可用原因码：前端与自愈都按它分类。 */
    public enum Reason {
        /** 可用。 */
        NONE(""),
        /** 用户没开启。 */
        DISABLED("CAPABILITY_DISABLED"),
        /** 没安装（或安装失败）。 */
        NOT_INSTALLED("CAPABILITY_NOT_INSTALLED"),
        /** 缺必需的环境变量。 */
        MISSING_ENV("CAPABILITY_MISSING_ENV"),
        /** 传输/命令不可用（命令不存在、URL 不可达）。 */
        TRANSPORT_UNAVAILABLE("CAPABILITY_TRANSPORT_UNAVAILABLE"),
        /** 其它未归类问题。 */
        UNKNOWN("CAPABILITY_UNAVAILABLE");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public CapabilityState {
        name = name == null ? "" : name.trim();
        kind = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        reason = reason == null ? "" : reason.trim();
        missingEnv = missingEnv == null ? List.of() : List.copyOf(missingEnv);
    }

    /** 可用：没有原因码也没有原因。 */
    public static CapabilityState usable(String name, String kind) {
        return new CapabilityState(name, kind, true, true, true, "", "", List.of());
    }

    /** 未开启。 */
    public static CapabilityState disabled(String name, String kind, boolean installed) {
        return new CapabilityState(name, kind, false, installed, false, Reason.DISABLED.code(),
                "该能力未开启：在「设置 → 能力」里打开后才会进入会话工具面", List.of());
    }

    /** 未安装。 */
    public static CapabilityState notInstalled(String name, String kind, boolean enabled) {
        return new CapabilityState(name, kind, enabled, false, false,
                Reason.NOT_INSTALLED.code(),
                "该能力未安装：在市场里点「接入」安装后再使用", List.of());
    }

    /** 缺环境变量：把缺哪些直接列出来，用户不用去猜。 */
    public static CapabilityState missingEnv(String name, String kind, List<String> missing) {
        List<String> keys = missing == null ? List.of() : List.copyOf(missing);
        return new CapabilityState(name, kind, true, true, false, Reason.MISSING_ENV.code(),
                "缺少必需的环境变量：" + String.join("、", keys), keys);
    }

    /** 传输不可用（命令/URL）。 */
    public static CapabilityState transportUnavailable(String name, String kind, String detail) {
        return new CapabilityState(name, kind, true, true, false,
                Reason.TRANSPORT_UNAVAILABLE.code(),
                "连接方式不可用：" + (detail == null || detail.isBlank() ? "未配置命令或地址" : detail),
                List.of());
    }

    /** 可用与否的唯一判定：三件事都满足才算可用。 */
    public boolean consistent() {
        return available == (enabled && installed && reasonCode.isEmpty());
    }
}
