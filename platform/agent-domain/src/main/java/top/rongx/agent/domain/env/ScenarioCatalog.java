package top.rongx.agent.domain.env;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 场景配置词条库：平台内置的一组「应用对接第三方服务」的标准变量名。
 *
 * <p>用途有两个：
 * <ul>
 *   <li>设置页可以按场景一键把词条加入环境模板，用户只需填值，不用记变量名；</li>
 *   <li>Agent 通过 {@code config_catalog} 只读工具拿到同一份词条，
 *       知道某个项目缺什么配置、每个键是什么含义，而不是自己猜变量名。</li>
 * </ul>
 *
 * <p>这里只有「键 + 说明 + 默认值」，不含任何真实密钥；值仍然只存在环境模板/项目覆盖里，
 * 读取值时依旧走 {@code config_resolve} 的掩码规则。
 */
public final class ScenarioCatalog {

    /**
     * 单条词条。
     *
     * @param key          变量名
     * @param label        中文短名
     * @param description  含义与去哪申请 / 填什么格式
     * @param secret       是否敏感（落库加密、展示掩码）
     * @param required     场景内是否必需
     * @param defaultValue 非敏感项的默认值（如接口地址、端口）；空 = 无默认
     * @param example      示例值（敏感项只给形态示例，不是真密钥）
     */
    public record Key(String key, String label, String description, boolean secret,
                      boolean required, String defaultValue, String example) {
    }

    /** 一个场景（某个第三方服务需要的全部配置）。 */
    public record Scenario(String id, String name, String summary, List<Key> keys) {
    }

    private static final List<Scenario> ALL = List.of(
            new Scenario("deepseek", "DeepSeek 大模型",
                    "OpenAI 兼容接口，用于对话 / 推理 / 摘要等 AI 能力。",
                    List.of(
                            key("DEEPSEEK_API_KEY", "API Key",
                                    "DeepSeek 开放平台创建的密钥，形如 sk- 开头", true, true,
                                    null, "sk-xxxxxxxx"),
                            key("DEEPSEEK_BASE_URL", "接口地址",
                                    "OpenAI 兼容 base url；自建/代理时改成自己的地址", false, false,
                                    "https://api.deepseek.com", null),
                            key("DEEPSEEK_MODEL", "模型名",
                                    "对话模型标识，常用 deepseek-chat / deepseek-reasoner", false, false,
                                    "deepseek-chat", null))),

            new Scenario("aliyun-oss", "阿里云 OSS",
                    "对象存储：上传图片、附件、备份包等。",
                    List.of(
                            key("OSS_ENDPOINT", "Endpoint",
                                    "地域节点地址，如 oss-cn-hangzhou.aliyuncs.com", false, true,
                                    "https://oss-cn-hangzhou.aliyuncs.com", null),
                            key("OSS_ACCESS_KEY_ID", "AccessKey ID",
                                    "RAM 用户 AccessKey（建议只授权目标 Bucket）", true, true,
                                    null, "LTAI5t****"),
                            key("OSS_ACCESS_KEY_SECRET", "AccessKey Secret",
                                    "RAM 用户 AccessKey Secret", true, true,
                                    null, "****"),
                            key("OSS_BUCKET", "Bucket",
                                    "目标 Bucket 名称（全局唯一，小写）", false, true,
                                    null, "my-app-assets"),
                            key("OSS_REGION", "地域",
                                    "OSS 地域标识，如 oss-cn-hangzhou", false, false,
                                    "oss-cn-hangzhou", null))),

            new Scenario("smtp", "SMTP 邮箱",
                    "邮件通知 / 验证码 / 告警；支持 163、QQ、企业邮箱等。",
                    List.of(
                            key("SMTP_HOST", "SMTP 服务器",
                                    "邮件服务商 SMTP 地址，如 smtp.163.com", false, true,
                                    "smtp.163.com", null),
                            key("SMTP_PORT", "端口",
                                    "SSL 一般 465，STARTTLS 一般 587", false, true,
                                    "465", null),
                            key("SMTP_USERNAME", "账号",
                                    "完整邮箱地址，如 ops@example.com", false, true,
                                    null, "ops@example.com"),
                            key("SMTP_PASSWORD", "授权码 / 密码",
                                    "邮箱设置里生成的 SMTP 授权码，不是登录密码", true, true,
                                    null, "****"),
                            key("SMTP_FROM", "发件人",
                                    "收件人看到的发件地址，一般与账号相同", false, false,
                                    null, "ops@example.com"),
                            key("SMTP_SSL_ENABLED", "启用 SSL",
                                    "true = 465 直连 SSL；false = 587 STARTTLS", false, false,
                                    "true", null))),

            new Scenario("dashscope", "通义千问 / 百炼",
                    "阿里云百炼（DashScope）OpenAI 兼容接口。",
                    List.of(
                            key("DASHSCOPE_API_KEY", "API Key",
                                    "百炼控制台创建的 sk- 开头密钥", true, true,
                                    null, "sk-xxxxxxxx"),
                            key("DASHSCOPE_BASE_URL", "接口地址",
                                    "OpenAI 兼容 base url", false, false,
                                    "https://dashscope.aliyuncs.com/compatible-mode/v1", null))));

    private ScenarioCatalog() {
    }

    public static List<Scenario> all() {
        return ALL;
    }

    public static Optional<Scenario> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String target = id.trim().toLowerCase(Locale.ROOT);
        return ALL.stream().filter(s -> s.id().equals(target)).findFirst();
    }

    /** 按变量名反查词条（Agent 判断"这个键是不是平台认识的"）。 */
    public static Optional<Key> key(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String target = key.trim().toUpperCase(Locale.ROOT);
        return ALL.stream()
                .flatMap(s -> s.keys().stream())
                .filter(k -> k.key().equals(target))
                .findFirst();
    }

    /** 一条词条的展示文本（Agent 输出用，不含任何值）。 */
    public static String describe(Scenario s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.name()).append("（").append(s.id()).append("）：").append(s.summary()).append('\n');
        for (Key k : s.keys()) {
            sb.append("  - ").append(k.key())
                    .append(k.required() ? " [必填]" : " [可选]")
                    .append(k.secret() ? " [敏感]" : "")
                    .append("：").append(k.label()).append(" — ").append(k.description());
            if (k.defaultValue() != null) {
                sb.append("（默认 ").append(k.defaultValue()).append("）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static Key key(String key, String label, String description, boolean secret,
                           boolean required, String defaultValue, String example) {
        return new Key(key, label, description, secret, required, defaultValue, example);
    }
}
