package top.rongx.agent.gateway.bugfix;

import java.util.regex.Pattern;

/**
 * Agent 输出脱敏：自动修复会把模型摘要写进 Issue 评论与 PR 描述，
 * 模型可能复述日志里的 token / 密码 / 连接串，必须在离开平台前统一打码。
 */
public final class BugFixRedactor {

    private static final Pattern[] PATTERNS = {
            Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"),
            Pattern.compile("(?i)((?:password|passwd|pwd|token|secret|api[_-]?key|access[_-]?key|"
                    + "private[_-]?key)\\s*[:=]\\s*[\"']?)[^\\s\"',;]{6,}"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
            Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}\\b"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
            Pattern.compile("(https?://)[^\\s/@:]+:[^\\s/@]+@"),
    };

    private static final String[] REPLACEMENTS = {
            "$1[REDACTED]",
            "$1[REDACTED]",
            "[REDACTED]",
            "[REDACTED]",
            "[REDACTED]",
            "[REDACTED]",
            "$1[REDACTED]@",
    };

    private BugFixRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (int i = 0; i < PATTERNS.length; i++) {
            result = PATTERNS[i].matcher(result).replaceAll(REPLACEMENTS[i]);
        }
        return result;
    }
}
