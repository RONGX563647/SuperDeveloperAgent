package top.rongx.agent.domain.error;

/**
 * 异常文案的统一处理：取根因、压成单行、限长。
 *
 * <p>此前 {@code rootMessage} 有 4 份完全相同，{@code safeMessage} 有 4 份两套语义
 * （一套折叠空白、一套直接返回 message）。这里保留两种明确语义，避免把含换行的
 * 堆栈片段直接塞进单行提示。
 */
public final class Errors {

    private Errors() {
    }

    /** 异常链最内层的 message；无 message 时回退类名，最长 300 字符。 */
    public static String rootMessage(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    /** 面向用户的单行错误文案：折叠空白并限长 300。 */
    public static String safeMessage(Throwable error) {
        if (error == null) {
            return "";
        }
        String raw = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        raw = top.rongx.agent.domain.process.LogTail.fold(raw);
        return raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
    }

    /** 保留换行的简短文案：只回退类名，不做折叠与截断。 */
    public static String briefMessage(Throwable error) {
        if (error == null) {
            return "";
        }
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }
}
