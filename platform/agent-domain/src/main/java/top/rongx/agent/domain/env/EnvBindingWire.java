package top.rongx.agent.domain.env;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.domain.tool.ToolResultText;

/**
 * 工具输出与网关之间的环境绑定「线格式」。
 *
 * <p>与 {@link top.rongx.agent.domain.change.ChangeWire} 同一思路：AgentScope 的工具事件只有
 * 文本增量，绑定草案 id 只能从工具返回文本里传递。把格式收敛到唯一实现，避免生产者与
 * 消费者各写一份正则、改格式后对话内的确认卡静默消失。
 *
 * <ul>
 *   <li>生产者只用 {@link #line(String)} 输出独立的一行 {@code bindingId: bind-...}；</li>
 *   <li>消费者只用 {@link #extract(String)} 解析（正文里顺带提到的 id 不会被误认）。</li>
 * </ul>
 */
public final class EnvBindingWire {

    /** 字段名（同时是工具输出里的行首标记）。 */
    public static final String FIELD = "bindingId";

    /** 绑定 id 前缀。 */
    public static final String PREFIX = "bind-";

    private static final Pattern LINE = Pattern.compile(
            "(?m)^\\s*" + FIELD + ":\\s*(bind-[A-Za-z0-9-]{6,})\\s*$");

    private EnvBindingWire() {
    }

    /** 生产端：输出一行可被 {@link #extract(String)} 识别的绑定 id。 */
    public static String line(String bindingId) {
        if (bindingId == null || bindingId.isBlank()) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "bindingId 不能为空");
        }
        return FIELD + ": " + bindingId.trim();
    }

    /**
     * 消费端：从工具返回文本里取绑定 id。
     *
     * <p>工具结果到网关时会被再 JSON 序列化一次（换行变成字面量 {@code \n}），
     * 必须先还原，否则绑定确认卡不会出现。
     *
     * @return 绑定 id；未按约定输出时返回 {@code null}
     */
    public static String extract(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = LINE.matcher(ToolResultText.unwrap(text));
        return matcher.find() ? matcher.group(1) : null;
    }
}
