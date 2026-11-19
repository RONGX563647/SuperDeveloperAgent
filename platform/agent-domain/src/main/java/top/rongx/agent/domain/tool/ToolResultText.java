package top.rongx.agent.domain.tool;

/**
 * 工具返回文本还原。
 *
 * <p>AgentScope 的 {@code DefaultToolResultConverter} 会把 {@code @Tool} 方法的返回值
 * 统一再 JSON 序列化一次：方法返回 {@code String} 时，网关拿到的是带外层引号、
 * 换行被转义成字面量的字符串（例如 {@code "status: ..."} 里出现 {@code 反斜杠 n}）。
 *
 * <p>依赖「独立一行」的线格式（{@link top.rongx.agent.domain.change.ChangeWire}、
 * {@link top.rongx.agent.domain.env.EnvBindingWire}）必须先还原，
 * 否则 {@code changeId:} 永远不在行首，对话里的确认卡会静默消失。
 */
public final class ToolResultText {

    private ToolResultText() {
    }

    /** 若是 JSON 字符串字面量，还原成原始多行文本；其它形态原样返回。 */
    public static String unwrap(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() < 2
                || trimmed.charAt(0) != '"'
                || trimmed.charAt(trimmed.length() - 1) != '"') {
            return text;
        }
        return unescape(trimmed.substring(1, trimmed.length() - 1));
    }

    /** 还原 JSON 字符串支持的转义（换行 / 制表 / 引号 / unicode 码点等）。 */
    private static String unescape(String escaped) {
        StringBuilder out = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char current = escaped.charAt(i);
            if (current != '\\' || i == escaped.length() - 1) {
                out.append(current);
                continue;
            }
            char next = escaped.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    int end = Math.min(i + 5, escaped.length());
                    if (end - i == 5) {
                        try {
                            out.append((char) Integer.parseInt(escaped.substring(i + 1, end), 16));
                            i = end - 1;
                        } catch (NumberFormatException invalid) {
                            out.append(next);
                        }
                    } else {
                        out.append(next);
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }
}
