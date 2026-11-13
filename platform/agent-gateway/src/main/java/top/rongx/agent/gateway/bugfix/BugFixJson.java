package top.rongx.agent.gateway.bugfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixEdit;

/**
 * 从 Agent 正文里提取结构化结果。
 *
 * <p>模型输出常见三种形态：纯 JSON、```json 代码块、JSON 前后带解释文字。这里统一做
 * 「逐个代码块尝试 → 整段尝试 → 首个 { 到最后一个 }」，解析失败返回 empty，由阶段决定
 * BLOCKED，绝不猜一个默认值继续往下跑。
 */
@Component
public class BugFixJson {

    private final ObjectMapper mapper;

    public BugFixJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<JsonNode> extract(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (String block : fencedBlocks(text)) {
            Optional<JsonNode> parsed = parseCandidate(block);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        Optional<JsonNode> whole = parseCandidate(text);
        if (whole.isPresent()) {
            return whole;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        return parse(text.substring(start, end + 1));
    }

    public boolean bool(JsonNode node, String field, boolean fallback) {
        if (node == null || !node.has(field)) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText("");
        return "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
    }

    public String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("");
    }

    /**
     * 读取置信度：支持 0.8 / "0.8" / "80%" 三种写法；缺失或无法解析按 0 处理，
     * 由阶段用阈值判断是否继续（0 表示模型自己都没把握）。
     */
    public double confidence(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return 0;
        }
        JsonNode value = node.get(field);
        if (value.isNumber()) {
            return clamp(value.asDouble());
        }
        String raw = value.asText("").trim();
        if (raw.endsWith("%")) {
            try {
                return clamp(Double.parseDouble(raw.substring(0, raw.length() - 1)) / 100d);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        try {
            return clamp(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || value < 0) {
            return 0;
        }
        return Math.min(value, 1d);
    }

    /**
     * 解析修复改动：优先 {@code edits}（精确片段替换），其次 {@code files}（完整内容）。
     *
     * <p>字段名做了兼容：old / oldText / search，new / newText / replace。
     * 结构不合法时抛 {@link IllegalArgumentException}，由阶段转成「补丁未通过校验」，
     * 而不是静默丢掉某一条改动导致修了一半。
     */
    public List<BugFixEdit> edits(JsonNode node) {
        List<BugFixEdit> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        JsonNode edits = node.get("edits");
        if (edits != null && edits.isArray()) {
            int index = 0;
            for (JsonNode edit : edits) {
                index++;
                String path = text(edit, "path").trim();
                if (path.isBlank()) {
                    throw new IllegalArgumentException("第 " + index + " 条改动缺少 path");
                }
                String oldText = firstText(edit, "old", "oldText", "search");
                String newText = firstText(edit, "new", "newText", "replace");
                if (oldText == null && newText == null) {
                    throw new IllegalArgumentException("改动缺少 old/new 字段：" + path);
                }
                result.add(BugFixEdit.replace(path, oldText == null ? "" : oldText,
                        newText == null ? "" : newText));
            }
            return result;
        }
        JsonNode files = node.get("files");
        if (files != null && files.isArray()) {
            int index = 0;
            for (JsonNode file : files) {
                index++;
                String path = text(file, "path").trim();
                if (path.isBlank()) {
                    throw new IllegalArgumentException("第 " + index + " 个文件缺少 path");
                }
                result.add(BugFixEdit.full(path, text(file, "content")));
            }
        }
        return result;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node != null && node.has(field) && !node.get(field).isNull()) {
                return node.get(field).asText("");
            }
        }
        return null;
    }

    private Optional<JsonNode> parseCandidate(String text) {
        String candidate = text == null ? "" : text.trim();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        Optional<JsonNode> direct = parse(candidate);
        if (direct.isPresent()) {
            return direct;
        }
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        return parse(candidate.substring(start, end + 1));
    }

    private Optional<JsonNode> parse(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return node == null || node.isMissingNode() ? Optional.empty() : Optional.of(node);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 拆出所有围栏代码块（跳过语言标识行）。 */
    private static List<String> fencedBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        int from = 0;
        while (true) {
            int fence = text.indexOf("```", from);
            if (fence < 0) {
                break;
            }
            int start = text.indexOf('\n', fence);
            if (start < 0) {
                break;
            }
            int end = text.indexOf("```", start);
            if (end < 0) {
                blocks.add(text.substring(start + 1));
                break;
            }
            blocks.add(text.substring(start + 1, end));
            from = end + 3;
        }
        return blocks;
    }
}
