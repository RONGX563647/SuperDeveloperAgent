package top.rongx.agent.domain.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 日志截断/取尾部（唯一实现）。
 *
 * <p>「只保留最后 N 行 / 最后 N 个字符」曾散落在部署器、沙箱、流水线引擎、本机服务
 * 运行时与网关控制面里各写一遍：行数不同、连接符不同、异常时返回 {@code ""} 还是
 * {@code "（无日志）"}也各不相同，读日志的人经常分不清「真的没输出」和「被截没了」。
 *
 * <p>本类只做纯文本处理，不读业务日志、不脱敏；调用方如需脱敏仍走
 * {@link LogRedactor}。仅依赖 JDK，允许 domain / infra / executor / runtime / gateway 共用。
 */
public final class LogTail {

    private LogTail() {
    }

    /** 取最后若干行（{@code \n} 连接）；{@code null} 视为空串。 */
    public static String lastLines(String text, int maxLines) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = text.split("\\R");
        int from = Math.max(0, lines.length - Math.max(0, maxLines));
        return String.join("\n", Arrays.copyOfRange(lines, from, lines.length));
    }

    /** 读文件并取最后若干行；文件不存在或不可读时返回空串。 */
    public static String lastLines(Path file, int maxLines) {
        return lastLines(file, maxLines, "\n", "");
    }

    /**
     * 读文件并取最后若干行。
     *
     * @param joiner   行连接符（如 {@code " | "}）
     * @param fallback 读取失败时的返回值
     */
    public static String lastLines(Path file, int maxLines, String joiner, String fallback) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String[] lines = text.split("\\R");
            int from = Math.max(0, lines.length - Math.max(0, maxLines));
            return String.join(joiner, Arrays.copyOfRange(lines, from, lines.length));
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    /** 读文件并取最后若干行（列表形式）；读取失败时返回空列表。 */
    public static List<String> lastLineList(Path file, int maxLines) {
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - Math.max(0, maxLines));
            return new ArrayList<>(all.subList(from, all.size()));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** 取末尾最多 {@code maxChars} 个字符（不追加省略号）；{@code null} 视为空串。 */
    public static String lastChars(String text, int maxChars) {
        return lastChars(text, maxChars, "");
    }

    /**
     * 取末尾最多 {@code maxChars} 个字符，发生截断时在头部加 {@code prefix}。
     *
     * <p>用于动作日志这类「只关心结尾、但要让人看到被截断」的场景。
     */
    public static String lastChars(String text, int maxChars, String prefix) {
        if (text == null) {
            return "";
        }
        int max = Math.max(0, maxChars);
        return text.length() <= max ? text : prefix + text.substring(text.length() - max);
    }

    /** 取开头最多 {@code maxChars} 个字符，发生截断时追加 {@code ellipsis}。 */
    public static String prefix(String value, int maxChars, String ellipsis) {
        if (value == null) {
            return "";
        }
        return value.length() > maxChars ? value.substring(0, maxChars) + ellipsis : value;
    }

    /**
     * 折叠为单行后再按开头截断：空白（含换行）压成一个空格，超长追加 {@code …}。
     *
     * <p>用于错误摘要、诊断这类会拼进一行提示的文本。
     */
    public static String oneLine(String value, int maxChars) {
        String one = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return one.length() > maxChars ? one.substring(0, maxChars) + "…" : one;
    }
}
