package top.rongx.agent.domain.conversation;

import java.util.regex.Pattern;

/**
 * runId 形状契约（BUG-29）：调度器、工具层与端口实现共用同一口径。
 *
 * <p>{@code send} 的契约是「要么返回合法 runId，要么抛异常」
 * （见 {@link WorkspaceSupervisorPort#send} 的注释）。历史上调用方用
 * {@code startsWith("err:")} 猜错误——那是"返回错误文案当 runId"旧契约的残留，
 * 新契约下永远不可达，留着只会掩盖契约回归。现在统一用形状校验代替文本猜测。
 *
 * <p>只校验 {@code run-} 前缀 + 合法字符（字母/数字/下划线/连字符），
 * 而不是复刻 id 生成规则：规则收紧时不应该连带把合法派发判成失败。
 */
public final class RunIds {

    /** runId 形状：{@code run-} 开头，后跟字母/数字/下划线/连字符。 */
    public static final Pattern SHAPE = Pattern.compile("run-[A-Za-z0-9_-]+");

    private RunIds() {
    }

    /** 形状校验：null / 空串 / 非 {@code run-} 形状都返回 false。 */
    public static boolean isRunId(String value) {
        return value != null && SHAPE.matcher(value).matches();
    }
}
