package top.rongx.agent.gateway.web;

import java.util.Map;

/**
 * 未引入 typed DTO 的接口统一取值入口。
 *
 * <p>此前 Settings / Environment / ProjectEnv 三个 Controller 各写了一份
 * {@code str(Map, key)}，口径还不一致（一个有 fallback 重载）。这里收口，
 * 后续新增接口直接复用，不要再手写。
 */
final class JsonBody {

    private JsonBody() {
    }

    /** 取字符串，缺失返回 null。 */
    static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 取字符串，缺失返回 fallback。 */
    static String str(Map<String, Object> body, String key, String fallback) {
        Object value = body == null ? null : body.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** 取必填字符串，缺失或空白直接 400。 */
    static String required(Map<String, Object> body, String key, String label) {
        String value = str(body, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " 必填");
        }
        return value;
    }

    /** 取整数，缺失或格式非法返回 null。 */
    static Integer integer(Map<String, Object> body, String key) {
        String value = str(body, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须是整数：" + value);
        }
    }

    /** 取布尔值，缺失返回 fallback。 */
    static boolean bool(Map<String, Object> body, String key, boolean fallback) {
        Object value = body == null ? null : body.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
