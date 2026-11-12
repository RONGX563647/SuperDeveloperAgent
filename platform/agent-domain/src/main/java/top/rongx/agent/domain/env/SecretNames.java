package top.rongx.agent.domain.env;

import java.util.Locale;

/**
 * 「哪些环境变量值算敏感」的唯一判定。
 *
 * <p>此前 infra / gateway / executor 各写了一份，关键词集合还不一致
 * （有的漏了 PRIVATE、有的漏了 ACCESS_KEY），导致同一变量在不同链路上
 * 一会儿掩码一会儿明文。泄露判定取并集，宁可多掩码。
 */
public final class SecretNames {

    private static final String[] NEEDLES = {
            "PASSWORD", "SECRET", "TOKEN", "ACCESS_KEY", "PRIVATE"
    };

    private SecretNames() {
    }

    public static boolean isSecret(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String k = key.toUpperCase(Locale.ROOT);
        if (k.endsWith("_KEY")) {
            return true;
        }
        for (String needle : NEEDLES) {
            if (k.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
