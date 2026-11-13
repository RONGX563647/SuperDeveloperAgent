package top.rongx.agent.domain.env;

import java.util.Locale;
import java.util.Optional;

/**
 * 配置项的三态：没配 / 已配 / 配了但疑似示例值。
 *
 * <p>只区分「有没有」无法解释"部署一直连不上第三方服务"——很多时候键是填了，
 * 但填的是文档里的 {@code sk-xxxxxxxx} 或 {@code ****}。这里把这种占位值单独标出来，
 * Agent 才能给出正确建议。
 */
public enum ValueState {

    MISSING,
    PRESENT,
    PLACEHOLDER;

    public static ValueState of(String key, String value) {
        if (value == null || value.isBlank()) {
            return MISSING;
        }
        if (looksPlaceholder(key, value)) {
            return PLACEHOLDER;
        }
        return PRESENT;
    }

    /** 占位值判定：文档示例、全 x/星号、明显未替换的模板变量。 */
    public static boolean looksPlaceholder(String key, String value) {
        String v = value.trim();
        if (v.isEmpty()) {
            return true;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.contains("your_") || lower.contains("your-") || lower.contains("changeme")
                || lower.contains("replace_me") || lower.contains("replace-me")
                || lower.contains("todo") || lower.contains("示例") || lower.contains("请填写")) {
            return true;
        }
        // ****abcd 是平台的展示掩码（表示"确实配了"），不是占位值；
        // 纯 **** 或 **** 后面没有指纹才是可疑占位。
        if (v.matches("^\\*{4}[A-Za-z0-9]{2,6}$")) {
            return false;
        }
        if (v.startsWith("****") || v.matches("^\\*+$")) {
            return true;
        }
        if (v.matches("(?i)^[xX]{4,}$") || v.matches("(?i)^x{2,}[_-].*x{2,}$")) {
            return true;
        }
        if (v.startsWith("${") && v.endsWith("}")) {
            return true;
        }
        // 常见的文档示例：sk-xxxxxxxx、token-xxxx；只要连续 4 个 x 就按占位处理。
        if (lower.contains("xxxx")) {
            return true;
        }
        Optional<ScenarioCatalog.Key> entry = ScenarioCatalog.key(key);
        if (entry.isPresent()) {
            ScenarioCatalog.Key k = entry.get();
            if (k.example() != null && k.example().equalsIgnoreCase(v)) {
                return true;
            }
            if (k.defaultValue() != null && k.defaultValue().equalsIgnoreCase(v)
                    && k.secret()) {
                return true;
            }
        }
        return false;
    }
}
