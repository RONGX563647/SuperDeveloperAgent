package top.rongx.agent.gateway.web;

import java.util.Map;

/**
 * 控制台 JSON 请求体的统一读取。
 *
 * <p>此前 12 个控制器各写一份 {@code str(Map,key)}（共 101 次调用），且语义分三派：
 * 缺失返回 {@code null}、缺失返回空串并 trim、带 fallback。这里保留这三种明确语义，
 * 避免统一成一种后把 {@code null} 传进 {@code isBlank()} 之类的 NPE。
 */
public final class RequestBodies {

    private RequestBodies() {
    }

    /** 缺失返回 {@code null}，保留调用方区分"未传"与"空串"的能力。 */
    public static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static String str(Map<String, Object> body, String key, String fallback) {
        Object value = body == null ? null : body.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** 缺失返回空串并去掉首尾空白（用于"可选文本"字段）。 */
    public static String trimmed(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
