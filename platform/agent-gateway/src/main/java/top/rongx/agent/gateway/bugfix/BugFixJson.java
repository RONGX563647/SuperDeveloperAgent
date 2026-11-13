package top.rongx.agent.gateway.bugfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.patch.CodePatch;

/**
 * 从 Agent 正文里提取结构化结果。
 *
 * <p>模型输出常见三种形态：纯 JSON、```json 代码块、JSON 前后带解释文字。这里统一做
 * 「先找代码块，再退化到首个 { 到最后一个 }」，解析失败返回 empty，由阶段决定 BLOCKED，
 * 绝不猜一个默认值继续往下跑。
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
        String candidate = codeBlock(text);
        Optional<JsonNode> direct = parse(candidate);
        if (direct.isPresent()) {
            return direct;
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

    /** 解析修复补丁的 files 数组；字段缺失/为空视为没有可用补丁。 */
    public List<CodePatch.FileEdit> files(JsonNode node) {
        List<CodePatch.FileEdit> edits = new ArrayList<>();
        if (node == null || !node.has("files") || !node.get("files").isArray()) {
            return edits;
        }
        for (JsonNode file : node.get("files")) {
            String path = text(file, "path");
            String content = text(file, "content");
            if (!path.isBlank()) {
                edits.add(new CodePatch.FileEdit(path, content));
            }
        }
        return edits;
    }

    private Optional<JsonNode> parse(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return node == null || node.isMissingNode() ? Optional.empty() : Optional.of(node);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String codeBlock(String text) {
        int fence = text.indexOf("```");
        if (fence < 0) {
            return text;
        }
        int start = text.indexOf('\n', fence);
        if (start < 0) {
            return text;
        }
        int end = text.indexOf("```", start);
        return end < 0 ? text.substring(start + 1) : text.substring(start + 1, end);
    }
}
