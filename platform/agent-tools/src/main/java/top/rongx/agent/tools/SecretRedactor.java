package top.rongx.agent.tools;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvVariable;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 统一脱敏器：read_file / grep / app_logs 等"把外部文本带进模型上下文"的工具共用。
 *
 * <p>三层防护：
 * <ol>
 *   <li><b>文件级</b>：{@link #isSensitiveFile} 判定 .env、私钥、凭据文件，读取入口直接拒绝；</li>
 *   <li><b>值级</b>：把当前环境里"敏感变量"的真实值从文本中替换成 {@code [REDACTED]}；</li>
 *   <li><b>形态级</b>：PEM 私钥块、{@code password=...}/{@code token: ...}、URL 里的 userinfo。</li>
 * </ol>
 *
 * <p>说明：值级替换依赖 {@link EnvironmentStore} 能解析出明文；若密钥引用无法解析（缺密钥），
 * 该值本就不会出现在平台注入的环境中，跳过即可。脱敏是"尽量不泄漏"的最后一道保险，
 * 不是唯一防线——生产工具面本身就没有写能力。
 */
@Component
public class SecretRedactor {

    /** 敏感变量名（用于值级收集与形态级替换）。 */
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|api[_-]?key|private[_-]?key|credential|authorization).*");

    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----");

    /** KEY=value / "key": "value" 形态，键名命中敏感词时只保留键名。 */
    private static final Pattern SECRET_ASSIGN = Pattern.compile(
            "(?i)([\\w.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|private[_-]?key|credential|authorization)[\\w.-]*)"
                    + "(\"?\\s*[:=]\\s*\"?)([^\\s\"',;]+)");

    private static final Pattern URL_USERINFO = Pattern.compile(
            "([a-z][a-z0-9+.-]*://)[^/\\s:@]+:[^/\\s@]+@", Pattern.CASE_INSENSITIVE);

    private static final String REDACTED = "[REDACTED]";

    private final EnvironmentStore envStore;

    public SecretRedactor(EnvironmentStore envStore) {
        this.envStore = envStore;
    }

    /**
     * 可能含凭据的文件：读取入口直接拒绝，grep 直接跳过。
     *
     * <p>{@code .env.example / .env.sample / .env.template} 是模板（约定不含真实值），允许读取。
     */
    public boolean isSensitiveFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = (slash >= 0 ? normalized.substring(slash + 1) : normalized).toLowerCase(Locale.ROOT);
        if (name.equals(".env") || name.equals("env")) {
            return true;
        }
        if (name.startsWith(".env.")) {
            return !(name.endsWith(".example") || name.endsWith(".sample")
                    || name.endsWith(".template") || name.endsWith(".dist"));
        }
        if (name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12")
                || name.endsWith(".pfx") || name.endsWith(".jks") || name.endsWith(".keystore")) {
            return true;
        }
        return name.equals("id_rsa") || name.equals("id_dsa") || name.equals("id_ecdsa")
                || name.equals("id_ed25519")
                || name.equals("credentials") || name.equals("credentials.json")
                || name.equals("secret.yml") || name.equals("secret.yaml")
                || name.equals("secrets.yml") || name.equals("secrets.yaml");
    }

    /** 对一段外部文本做脱敏（值级 + 形态级）。 */
    public String redact(String text, ProjectContext ctx) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (String value : secretValues(ctx)) {
            if (value != null && value.length() >= 4) {
                out = out.replace(value, REDACTED);
            }
        }
        out = PRIVATE_KEY_BLOCK.matcher(out).replaceAll(REDACTED + " PRIVATE KEY");
        out = SECRET_ASSIGN.matcher(out).replaceAll("$1$2" + REDACTED);
        out = URL_USERINFO.matcher(out).replaceAll("$1***:***@");
        return out;
    }

    /** 当前环境里值得从文本中抹掉的真实值（只做内存内比较，绝不外泄）。 */
    private Set<String> secretValues(ProjectContext ctx) {
        Set<String> values = new LinkedHashSet<>();
        try {
            EffectiveEnvironment effective = envStore.effective(ctx.projectId(), ctx.env());
            Map<String, String> actual = effective.values();
            for (EnvVariable var : effective.vars()) {
                if (!var.secret() && !SENSITIVE_KEY.matcher(var.key()).matches()) {
                    continue;
                }
                String value = actual.get(var.key());
                if (value != null && !value.isBlank() && !value.startsWith("secret://")) {
                    values.add(value);
                }
            }
        } catch (RuntimeException ignored) {
            // 环境未初始化/项目不存在：退化为纯形态级脱敏
        }
        return values;
    }

    /** 单行脱敏（grep 结果逐行处理时复用，避免整段拼接后再替换的开销）。 */
    public String redactLine(String line, ProjectContext ctx) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        String out = SECRET_ASSIGN.matcher(line).replaceAll("$1$2" + REDACTED);
        out = URL_USERINFO.matcher(out).replaceAll("$1***:***@");
        for (String value : secretValues(ctx)) {
            if (value.length() >= 4 && out.contains(value)) {
                out = out.replace(value, REDACTED);
            }
        }
        return out;
    }

    /** 供测试/诊断：返回命中敏感文件规则的路径列表（不涉及内容）。 */
    public List<String> sensitiveFiles(List<String> paths) {
        return paths.stream().filter(this::isSensitiveFile).toList();
    }
}
