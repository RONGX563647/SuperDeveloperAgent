package top.rongx.agent.domain.env;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 密钥引用语法。
 *
 * <p>支持两种写法：
 * <ul>
 *   <li>整值：{@code DB_PASSWORD=secret://db-password}</li>
 *   <li>嵌入：{@code jdbc:mysql://host/db?password=${secret:db-password}}</li>
 * </ul>
 *
 * <p>引用名只允许字母、数字、点、下划线与短横线，避免把路径/URL 片段误判为引用。
 */
public final class SecretRefs {

    private static final String NAME = "[A-Za-z0-9._-]{1,128}";
    private static final Pattern WHOLE = Pattern.compile("^secret://(" + NAME + ")$");
    private static final Pattern EMBEDDED = Pattern.compile("\\$\\{secret:(" + NAME + ")}");

    private SecretRefs() {
    }

    /** 整值引用名；不是整值引用时返回 null。 */
    public static String whole(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = WHOLE.matcher(raw.trim());
        return m.matches() ? m.group(1) : null;
    }

    /** 文本中嵌入的所有引用名（去重、保持出现顺序）。 */
    public static List<String> embedded(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = EMBEDDED.matcher(raw);
        while (m.find()) {
            String name = m.group(1);
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** 引用名是否合法。 */
    public static boolean validName(String name) {
        return name != null && name.matches(NAME);
    }
}
