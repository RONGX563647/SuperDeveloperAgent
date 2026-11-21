package top.rongx.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.rongx.agent.domain.json.JsonMappers;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 平台 API 文档工具（只读）。
 *
 * <p>数据源是运行时生成的 OpenAPI 快照（{@code agent.api-docs.snapshot}），
 * 找不到时退回 classpath {@code api/openapi.json}。工具只读规范、不调用任何业务接口，
 * 因此不会因为"查文档"而触发副作用。
 *
 * <p>性能：解析结果按文件 mtime + 大小缓存；快照未变化时不会重复解析 JSON。
 */
@Component
public class ApiDocTools {

    private static final List<String> METHODS =
            List.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
    private static final int MAX_SEARCH = 20;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Path snapshotPath;
    private final ObjectMapper mapper = JsonMappers.readOnly();

    private volatile Snapshot snapshot;

    public ApiDocTools(@Value("${agent.api-docs.snapshot:./data/api/openapi.json}") String snapshot) {
        this.snapshotPath = Path.of(snapshot).toAbsolutePath().normalize();
    }

    @Tool(name = "api_search", description = "Search platform HTTP API docs.",
            readOnly = true, concurrencySafe = true)
    public String search(@ToolParam(name = "keyword",
            description = "关键词，如 deploy、workspace、settings；空字符串返回前若干条") String keyword,
                         @ToolParam(name = "tag", required = false,
                                 description = "可选：按 OpenAPI tag 过滤，如 工作区") String tag,
                         @ToolParam(name = "limit", required = false,
                                 description = "返回条数上限，默认 10，最大 20") Integer limit) {
        Snapshot snap = load();
        if (snap.error != null) {
            return snap.error;
        }
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String tagFilter = tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
        int max = limit == null ? 10 : Math.max(1, Math.min(MAX_SEARCH, limit));

        List<Operation> hits = new ArrayList<>();
        for (Operation op : snap.operations.values()) {
            if (!tagFilter.isEmpty()
                    && op.tags().stream().noneMatch(t -> t.toLowerCase(Locale.ROOT).contains(tagFilter))) {
                continue;
            }
            if (!kw.isEmpty() && !op.haystack().contains(kw)) {
                continue;
            }
            hits.add(op);
        }
        if (hits.isEmpty()) {
            return "未匹配到接口：keyword=" + keyword + (tag == null ? "" : "，tag=" + tag)
                    + "\n" + provenance(snap);
        }
        String body = hits.stream().limit(max)
                .map(op -> "%s %s%n    %s%s".formatted(op.method(), op.path(),
                        op.summary().isBlank() ? "(无摘要)" : op.summary(),
                        op.tags().isEmpty() ? "" : "  [" + String.join(", ", op.tags()) + "]"))
                .collect(Collectors.joining("\n"));
        return body + "\n" + provenance(snap)
                + "\nhint: 用 api_get(method, path) 查看参数/响应；用 api_schema(name) 查看模型。";
    }

    @Tool(name = "api_get", description = "Read one API endpoint signature.",
            readOnly = true, concurrencySafe = true)
    public String get(@ToolParam(name = "method", description = "HTTP 方法，如 GET/POST") String method,
                      @ToolParam(name = "path", description = "接口路径模板") String path) {
        Snapshot snap = load();
        if (snap.error != null) {
            return snap.error;
        }
        Operation op = snap.operations.get(operationKey(method, path));
        if (op == null) {
            return "未找到接口 " + method + " " + path + "\n" + provenance(snap)
                    + "\nhint: 先用 api_search 搜关键词确认路径。";
        }
        return render(op) + "\n" + provenance(snap);
    }

    @Tool(name = "api_schema", description = "Read a request/response model schema.",
            readOnly = true, concurrencySafe = true)
    public String schema(@ToolParam(name = "name", description = "模型名，如 ChatRequest") String name) {
        Snapshot snap = load();
        if (snap.error != null) {
            return snap.error;
        }
        String wanted = name == null ? "" : name.trim().replace("#/components/schemas/", "");
        JsonNode schema = snap.schemas.get(wanted);
        if (schema == null) {
            for (Map.Entry<String, JsonNode> e : snap.schemas.entrySet()) {
                if (e.getKey().equalsIgnoreCase(wanted)) {
                    schema = e.getValue();
                    wanted = e.getKey();
                    break;
                }
            }
        }
        if (schema == null) {
            return "未找到模型 " + name + "。可用模型：" + String.join(", ", snap.schemas.keySet())
                    + "\n" + provenance(snap);
        }
        return "schema: " + wanted + "\n" + pretty(schema) + "\n" + provenance(snap);
    }

    // ------------------------------------------------------------------ 渲染

    private String render(Operation op) {
        StringBuilder out = new StringBuilder();
        out.append(op.method()).append(' ').append(op.path()).append('\n');
        if (!op.summary().isBlank()) {
            out.append("summary: ").append(op.summary()).append('\n');
        }
        if (!op.tags().isEmpty()) {
            out.append("tags: ").append(String.join(", ", op.tags())).append('\n');
        }
        JsonNode node = op.node();
        if (node.hasNonNull("operationId")) {
            out.append("operationId: ").append(node.get("operationId").asText()).append('\n');
        }
        if (node.hasNonNull("description")) {
            out.append("description: ").append(node.get("description").asText()).append('\n');
        }

        JsonNode parameters = node.path("parameters");
        if (parameters.isArray() && !parameters.isEmpty()) {
            out.append("parameters:\n");
            for (JsonNode p : parameters) {
                out.append("  - ").append(p.path("name").asText())
                        .append(" (").append(p.path("in").asText())
                        .append(p.path("required").asBoolean(false) ? ", required" : "")
                        .append(") ");
                String type = typeOf(p.path("schema"));
                if (!type.isBlank()) {
                    out.append(type).append(' ');
                }
                String desc = p.path("description").asText("");
                if (!desc.isBlank()) {
                    out.append(desc);
                }
                out.append('\n');
            }
        }

        JsonNode requestBody = node.path("requestBody");
        if (requestBody.isObject()) {
            out.append("requestBody");
            if (requestBody.path("required").asBoolean(false)) {
                out.append(" (required)");
            }
            out.append(":\n");
            appendContent(out, requestBody.path("content"), "  ");
        }

        JsonNode responses = node.path("responses");
        if (responses.isObject()) {
            out.append("responses:\n");
            Iterator<Map.Entry<String, JsonNode>> it = responses.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                out.append("  - ").append(e.getKey());
                JsonNode resp = e.getValue();
                if (resp.hasNonNull("description")) {
                    out.append(": ").append(resp.get("description").asText());
                }
                out.append('\n');
                appendContent(out, resp.path("content"), "    ");
            }
        }
        return out.toString().stripTrailing();
    }

    private void appendContent(StringBuilder out, JsonNode content, String indent) {
        if (!content.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = content.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode schema = e.getValue().path("schema");
            out.append(indent).append(e.getKey()).append(" -> ").append(typeOf(schema));
            if (schema.hasNonNull("$ref")) {
                out.append("（用 api_schema 查看字段）");
            }
            out.append('\n');
        }
    }

    private static String typeOf(JsonNode schema) {
        if (schema == null || schema.isMissingNode()) {
            return "";
        }
        if (schema.hasNonNull("$ref")) {
            return schema.get("$ref").asText().replace("#/components/schemas/", "");
        }
        if (schema.hasNonNull("type")) {
            return schema.get("type").asText();
        }
        if (schema.has("items")) {
            return "array<" + typeOf(schema.get("items")) + ">";
        }
        return "";
    }

    private String pretty(JsonNode node) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        }
        catch (IOException e) {
            return node.toString();
        }
    }

    private String provenance(Snapshot snap) {
        return "source: " + snap.source + "\n"
                + "generatedAt: " + TIME.format(Instant.ofEpochMilli(snap.generatedAt)) + "\n"
                + "spec: " + snap.title + " " + snap.version + "；operations=" + snap.operations.size();
    }

    // ------------------------------------------------------------------ 加载

    private Snapshot load() {
        try {
            if (Files.isRegularFile(snapshotPath)) {
                long modified = Files.getLastModifiedTime(snapshotPath).toMillis();
                long size = Files.size(snapshotPath);
                Snapshot current = snapshot;
                if (current != null && current.matches(snapshotPath.toString(), modified, size)) {
                    return current;
                }
                return parse(Files.readString(snapshotPath, StandardCharsets.UTF_8),
                        snapshotPath.toString(), modified, size);
            }
            Snapshot current = snapshot;
            if (current != null && current.fromClasspath) {
                return current;
            }
            try (InputStream in = getClass().getResourceAsStream("/api/openapi.json")) {
                if (in != null) {
                    String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    return parse(json, "classpath:api/openapi.json", 0L,
                            json.getBytes(StandardCharsets.UTF_8).length);
                }
            }
            return Snapshot.error("没有可用的 OpenAPI 快照。请启动一次平台（会写入 "
                    + snapshotPath + "），或在构建时生成 classpath:api/openapi.json。");
        }
        catch (IOException | RuntimeException e) {
            return Snapshot.error("OpenAPI 快照读取失败：" + e.getMessage());
        }
    }

    private Snapshot parse(String json, String source, long generatedAt, long size) throws IOException {
        JsonNode root = mapper.readTree(json);
        Map<String, Operation> operations = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> pathIt = root.path("paths").fields();
        while (pathIt.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIt.next();
            String path = pathEntry.getKey();
            JsonNode item = pathEntry.getValue();
            for (String method : METHODS) {
                JsonNode node = item.get(method);
                if (node == null || !node.isObject()) {
                    continue;
                }
                List<String> tags = new ArrayList<>();
                node.path("tags").forEach(t -> tags.add(t.asText()));
                operations.put(operationKey(method, path), new Operation(
                        method.toUpperCase(Locale.ROOT), path,
                        node.path("summary").asText(""), node.path("description").asText(""),
                        List.copyOf(tags), node));
            }
        }
        Map<String, JsonNode> schemas = new LinkedHashMap<>();
        root.path("components").path("schemas").fields()
                .forEachRemaining(e -> schemas.put(e.getKey(), e.getValue()));
        Snapshot snap = new Snapshot(source, generatedAt,
                root.path("info").path("title").asText(""),
                root.path("info").path("version").asText(""),
                operations, schemas, size, null);
        snapshot = snap;
        return snap;
    }

    private static String operationKey(String method, String path) {
        return (method == null ? "" : method.trim().toUpperCase(Locale.ROOT)) + " "
                + Optional.ofNullable(path).orElse("").trim();
    }

    /** 单个接口；检索串在构造时算一次，避免每次搜索重复拼接。 */
    private record Operation(String method, String path, String summary, String description,
                             List<String> tags, JsonNode node, String haystack) {
        Operation(String method, String path, String summary, String description,
                  List<String> tags, JsonNode node) {
            this(method, path, summary, description, tags, node,
                    (path + " " + summary + " " + description + " " + String.join(" ", tags) + " "
                            + node.path("operationId").asText("")).toLowerCase(Locale.ROOT));
        }
    }

    private static final class Snapshot {
        final String source;
        final long generatedAt;
        final String title;
        final String version;
        final Map<String, Operation> operations;
        final Map<String, JsonNode> schemas;
        final long size;
        final String error;
        final boolean fromClasspath;

        Snapshot(String source, long generatedAt, String title, String version,
                 Map<String, Operation> operations, Map<String, JsonNode> schemas,
                 long size, String error) {
            this.source = source;
            this.generatedAt = generatedAt;
            this.title = title;
            this.version = version;
            this.operations = operations;
            this.schemas = schemas;
            this.size = size;
            this.error = error;
            this.fromClasspath = source.startsWith("classpath:");
        }

        static Snapshot error(String message) {
            return new Snapshot("none", 0L, "", "", Map.of(), Map.of(), -1L, message);
        }

        boolean matches(String path, long modified, long size) {
            return !fromClasspath && source.equals(path)
                    && generatedAt == modified && this.size == size;
        }
    }
}
