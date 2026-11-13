package top.rongx.agent.gateway.bugfix;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 阶段提示词模板：提示词放在 {@code classpath:bugfix/prompts/*.md}，不再硬编码在 Java 里。
 *
 * <p>这样可以独立评审/调整提示词，也避免 Java 文本块里的大量转义。模板使用
 * {@code {{key}}} 占位符；缺失值会直接报错（而不是把 {@code {{key}}} 送给模型）。
 */
@Component
public class BugFixPromptTemplates {

    private static final String ROOT = "bugfix/prompts/";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 渲染模板；values 允许 null（按空串处理）。 */
    public String render(String name, Map<String, ?> values) {
        String template = template(name);
        Map<String, String> resolved = new HashMap<>();
        if (values != null) {
            values.forEach((key, value) -> resolved.put(key, value == null ? "" : String.valueOf(value)));
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!resolved.containsKey(key)) {
                throw new IllegalStateException("提示词模板 " + name + " 缺少参数：" + key);
            }
        }
        StringBuilder rendered = new StringBuilder(template);
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            replaceAll(rendered, "{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered.toString();
    }

    private String template(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("提示词模板名不能为空");
        }
        return cache.computeIfAbsent(name, key -> read(ROOT + key + ".md"));
    }

    private static String read(String path) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        InputStream stream = loader == null ? null : loader.getResourceAsStream(path);
        if (stream == null) {
            stream = BugFixPromptTemplates.class.getClassLoader().getResourceAsStream(path);
        }
        if (stream == null) {
            throw new IllegalStateException("提示词模板不存在：" + path);
        }
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词模板失败：" + path + "（" + e.getMessage() + "）",
                    e);
        }
    }

    /** 简单字符串替换（避免正则替换把值里的 $ / \\ 当成组引用）。 */
    private static void replaceAll(StringBuilder text, String target, String value) {
        int from = 0;
        while (true) {
            int index = text.indexOf(target, from);
            if (index < 0) {
                return;
            }
            text.replace(index, index + target.length(), value);
            from = index + value.length();
        }
    }
}
