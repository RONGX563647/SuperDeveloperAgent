package top.rongx.agent.domain.change;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具输出与网关之间的变更单「线格式」。
 *
 * <p>AgentScope 的工具事件只有文本增量，没有结构化 payload，因此 changeId 只能从
 * 工具返回文本里传递。为了避免生产者（工具）与消费者（ChatController）各写一份
 * 正则、改格式后卡片静默消失，这里把格式收敛到唯一实现：
 *
 * <ul>
 *   <li>生产者只用 {@link #line(String)} 输出；</li>
 *   <li>消费者只用 {@link #extract(String)} 解析；</li>
 *   <li>行必须是独立的 {@code changeId: chg-...}（整行匹配），正文里顺带提到的
 *       单号不会被误认成可确认的变更。</li>
 * </ul>
 */
public final class ChangeWire {

    /** 字段名（同时是工具输出里的行首标记）。 */
    public static final String FIELD = "changeId";

    /** 变更单号前缀。 */
    public static final String PREFIX = "chg-";

    private static final Pattern LINE = Pattern.compile(
            "(?m)^\\s*" + FIELD + ":\\s*(chg-[A-Za-z0-9-]{8,64})\\s*$");

    private ChangeWire() {
    }

    /** 生产端：输出一行可被 {@link #extract(String)} 识别的变更单号。 */
    public static String line(String changeId) {
        if (changeId == null || changeId.isBlank()) {
            throw new IllegalArgumentException("changeId 不能为空");
        }
        return FIELD + ": " + changeId.trim();
    }

    /**
     * 消费端：从工具返回文本里取变更单号。
     *
     * @return 单号；未按约定输出（或格式被改动）时返回 {@code null}
     */
    public static String extract(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = LINE.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
