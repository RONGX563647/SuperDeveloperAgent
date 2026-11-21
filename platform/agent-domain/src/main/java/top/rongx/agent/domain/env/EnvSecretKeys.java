package top.rongx.agent.domain.env;

import java.util.Locale;

/**
 * 环境变量名是否"看起来是密钥"的唯一判定。
 *
 * <p>此前 infra / gateway / tools 各有一份规则，且三份不一致（有的认 {@code PRIVATE}、
 * 有的认 {@code CREDENTIAL}），同一个 {@code DB_PRIVATE_KEY} 在配置解析里被掩码、
 * 在环境绑定页却可能按明文展示。这里取三者并集：宁可多掩码，不可漏掩码。
 */
public final class EnvSecretKeys {

    private EnvSecretKeys() {
    }

    public static boolean looksSecret(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toUpperCase(Locale.ROOT);
        return k.contains("PASSWORD") || k.contains("SECRET") || k.contains("TOKEN")
                || k.contains("CREDENTIAL") || k.endsWith("_KEY") || k.contains("PRIVATE");
    }
}
