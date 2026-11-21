package top.rongx.agent.domain.process;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 运行日志脱敏的<b>唯一实现</b>（规约 §1.6 redactor）。
 *
 * <p>为什么必须只有一份：脱敏规则一旦多处复制，就必然出现"某个出口漏了某条规则"。
 * 历史上执行内核（{@code ProcessExecutor.redact}）与网关修复链（{@code BugFixRedactor.redact}）
 * 各有一份形状几乎相同但阈值不同的实现，同一段密钥在不同出口的表现不一致——
 * 那比"都没脱敏"更难发现，因为看起来是脱过的。
 *
 * <p>本类只做<b>形态匹配</b>：不解析内容、不查上下文，命中即替换为 {@code ***}。
 * 值级脱敏（把本次注入的密钥明文整段替换）见 {@link #redact(String, Collection)}。
 */
public final class LogRedactor {

    /** 最短有效凭证长度：低于此值的"密钥"替换会打碎正常日志，且本来也不构成凭证。 */
    private static final int MIN_SECRET_LENGTH = 4;

    /** 已知前缀形凭证（与 SecretRedactor 的 TOKEN_PREFIX 规则对齐）。 */
    private static final Pattern TOKEN_PREFIX = Pattern.compile(
            "(?i)(sk-[A-Za-z0-9_-]{4,}"          // OpenAI / Anthropic（sk-proj- / sk-ant- 由 sk- 覆盖）
                    + "|gh[pousr]_[A-Za-z0-9]{4,}"      // GitHub
                    + "|glpat-[A-Za-z0-9_-]{4,}"        // GitLab
                    + "|xox[baprs]-[A-Za-z0-9_-]{4,}"   // Slack
                    + "|AKIA[0-9A-Z]{8,})");            // AWS

    /** {@code token=...}/{@code password: ...} 这类赋值形状。 */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)((?:token|secret|password|passwd|api[_-]?key|access[_-]?key|authorization)"
                    + "\\s*[=:]\\s*)[^\\s\"']{8,}");

    /** {@code Authorization: Bearer xxx}。阈值 8 与 agent-tools 的 SecretRedactor 一致。 */
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._-]{8,}");

    /**
     * 长十六进制串（jwt 第二段等）。
     *
     * <p>必须放过 {@code sha256:<hex>} / {@code sha512:<hex>}：那是镜像与产物的公开指纹，
     * 属于构建证据，一并抹掉会让页面上的证据只剩 {@code ***}，反而失去可追溯性。
     */
    private static final Pattern LONG_HEX = Pattern.compile(
            "(?<!sha256:)(?<!sha512:)\\b[0-9a-fA-F]{64}\\b");

    /** PEM 私钥块。 */
    private static final Pattern PEM = Pattern.compile(
            "(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----");

    /** Cookie / Set-Cookie 整行（整行都是会话凭证）。 */
    private static final Pattern COOKIE = Pattern.compile("(?im)^\\s*(?:set-)?cookie\\s*:.*$");

    /**
     * JWT（三段点分）：只靠长 hex 规则抓不到——JWT 用 base64url，含 {@code -} 与 {@code _}。
     *
     * <p>这条规则原先只在网关的 {@code BugFixRedactor} 里有，执行内核没有：
     * 同一份构建日志，写进 Issue 评论时脱敏了，写进运行日志时没有。
     */
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b");

    /**
     * URL 内嵌凭据：{@code https://user:pass@host/...}。
     *
     * <p>同样原先只在网关侧有：Git 远程地址带凭据时，运行日志会原样留下账号密码。
     */
    private static final Pattern URL_CREDENTIALS = Pattern.compile(
            "(https?://)[^\\s/@:]+:[^\\s/@]+@");

    private LogRedactor() {
    }

    /** 形态脱敏。 */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = BEARER.matcher(text).replaceAll("$1***");
        out = ASSIGNMENT.matcher(out).replaceAll("$1***");
        out = TOKEN_PREFIX.matcher(out).replaceAll("***");
        out = JWT.matcher(out).replaceAll("***");
        out = LONG_HEX.matcher(out).replaceAll("***");
        out = PEM.matcher(out).replaceAll("***PEM***");
        out = COOKIE.matcher(out).replaceAll("***COOKIE***");
        out = URL_CREDENTIALS.matcher(out).replaceAll("$1***@");
        return out;
    }

    /**
     * 值级脱敏：在形态匹配之外，把本次注入过的密钥明文整段替换掉。
     *
     * <p>形态匹配挡不住 {@code echo $TOKEN} 这类没有关键字前缀的输出；
     * 写盘前用环境里的真实密钥值兜底，避免明文进入证据链。
     *
     * @param sensitiveValues 本次运行注入的敏感值（短于 4 字符的会被忽略）
     */
    public static String redact(String text, Collection<String> sensitiveValues) {
        String out = redact(text);
        if (out == null || out.isEmpty() || sensitiveValues == null || sensitiveValues.isEmpty()) {
            return out;
        }
        // 长的先替换：先换短值会把长值切碎，留下残片
        List<String> values = sensitiveValues.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> v.length() >= MIN_SECRET_LENGTH)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String value : values) {
            out = out.replace(value, "***");
        }
        return out;
    }
}
