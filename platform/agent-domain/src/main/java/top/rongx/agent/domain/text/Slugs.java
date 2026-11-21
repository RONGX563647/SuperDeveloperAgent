package top.rongx.agent.domain.text;

import java.util.Locale;

/**
 * 标识符/镜像名片段的安全化：小写、非安全字符转 {@code -}、去掉前导分隔符。
 *
 * <p>executor 与 tools 曾各有一份（其中两份完全相同），这里只保留一份；
 * 需要非空兜底（如 "unknown"）的调用方在本地做一次空判断即可。
 */
public final class Slugs {

    private Slugs() {
    }

    public static String slug(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "-");
        while (value.startsWith("-") || value.startsWith(".")) {
            value = value.substring(1);
        }
        return value;
    }
}
