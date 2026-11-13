package top.rongx.agent.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.domain.settings.McpServer;
import top.rongx.agent.domain.settings.McpServerRepository;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * MCP 接入（设置页）。
 *
 * <p>配置形状对齐 Claude Desktop / Claude Code 的 {@code mcpServers}：
 * <ul>
 *   <li>{@code stdio}：{@code command}（如 npx）+ {@code args}（如 {@code -y @playwright/mcp@latest}）；</li>
 *   <li>{@code http}：{@code url} + 自定义请求头（如 {@code Authorization: Bearer ...}）。</li>
 * </ul>
 *
 * <p>「测试连接」对 HTTP 服务发送 MCP {@code initialize} 握手并解析 serverInfo；
 * stdio 服务检查命令是否在本机 PATH 上（真正拉起由对话运行时的 MCP 客户端负责）。
 */
@RestController
@RequestMapping("/api/v1/settings/mcp-servers")
public class McpSettingsController {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = OutboundHttp.client(Duration.ofSeconds(8));
    private final McpServerRepository servers;
    private final CurrentUser currentUser;

    public McpSettingsController(McpServerRepository servers, CurrentUser currentUser) {
        this.servers = servers;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<McpServer> list() {
        requireRead();
        return servers.findAll();
    }

    @PostMapping
    public McpServer create(@RequestBody Map<String, Object> body) {
        String userId = requireWriteUser();
        return servers.save(view(null, body), headers(body), userId);
    }

    @PutMapping("/{id}")
    public McpServer update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String userId = requireWriteUser();
        return servers.save(view(id, body), headers(body), userId);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        requireWrite();
        servers.delete(id);
        return Map.of("ok", true);
    }

    /** 已保存配置的连通性测试。 */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String id) {
        requireWrite();
        McpServer server = servers.find(id).orElseThrow(
                () -> new IllegalArgumentException("MCP 服务不存在：" + id));
        return probe(server.transport(), server.command(), server.url(), servers.headersOf(id));
    }

    /** 保存前测试当前表单（headers 为空时沿用已保存值）。 */
    @PostMapping("/test")
    public Map<String, Object> testDraft(@RequestBody Map<String, Object> body) {
        requireWrite();
        String id = str(body, "id");
        Map<String, String> headers = headers(body);
        if (headers.isEmpty() && id != null && !id.isBlank() && servers.find(id).isPresent()) {
            headers = servers.headersOf(id);
        }
        McpServerRepository.McpServerView draft = view(id, body);
        return probe(draft.transport(), draft.command(), draft.url(), headers);
    }

    // ---------------------------------------------------------------- 探测

    private Map<String, Object> probe(String transport, String commandValue, String urlValue,
                                      Map<String, String> headers) {
        long start = System.nanoTime();
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if ("stdio".equals(transport)) {
                String command = commandValue == null ? "" : commandValue.trim();
                boolean found = commandOnPath(command);
                out.put("ok", found);
                out.put("latencyMs", 0L);
                out.put("message", found
                        ? "命令可用：" + command + "（由对话运行时按需拉起）"
                        : "本机 PATH 中找不到命令：" + command);
                return out;
            }
            if (urlValue == null || urlValue.isBlank()) {
                throw new IllegalArgumentException("http 传输必须填写服务 URL");
            }
            JsonNode result = initialize(urlValue, headers);
            JsonNode info = result.path("serverInfo");
            out.put("ok", true);
            out.put("latencyMs", (System.nanoTime() - start) / 1_000_000L);
            out.put("serverName", info.path("name").asText(""));
            out.put("serverVersion", info.path("version").asText(""));
            out.put("protocolVersion", result.path("protocolVersion").asText(""));
            out.put("message", "握手成功："
                    + info.path("name").asText("MCP 服务") + " " + info.path("version").asText(""));
            return out;
        } catch (IllegalArgumentException | ResponseStatusException e) {
            out.put("ok", false);
            out.put("latencyMs", (System.nanoTime() - start) / 1_000_000L);
            out.put("message", e instanceof ResponseStatusException rse ? rse.getReason() : e.getMessage());
            return out;
        }
    }

    /** MCP initialize 握手；返回 result 节点（兼容 SSE 与纯 JSON 两种响应）。 */
    private JsonNode initialize(String url, Map<String, String> headers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", 1);
        payload.put("method", "initialize");
        payload.put("params", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agent-platform", "version", "1.0")));
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream");
            headers.forEach(builder::header);
            HttpRequest req = builder.POST(HttpRequest.BodyPublishers.ofString(
                    mapper.writeValueAsString(payload))).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "MCP 服务返回 HTTP " + resp.statusCode() + "（检查 URL 与请求头）");
            }
            JsonNode node = parseRpc(resp.body());
            if (node.has("error")) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "MCP 握手失败：" + node.path("error").path("message").asText("未知错误"));
            }
            return node.has("result") ? node.path("result") : node;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "无法连接 MCP 服务：" + (e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** 响应可能是 SSE（data: {...}）或纯 JSON。 */
    private JsonNode parseRpc(String body) throws Exception {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("MCP 服务返回空响应");
        }
        if (text.startsWith("{")) {
            return mapper.readTree(text);
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                String data = trimmed.substring("data:".length()).trim();
                if (data.startsWith("{")) {
                    return mapper.readTree(data);
                }
            }
        }
        throw new IllegalArgumentException("无法解析 MCP 响应（不是 JSON 或 SSE）");
    }

    /** 命令是否在 PATH 上（只看文件名，不执行任何命令）。 */
    private static boolean commandOnPath(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String name = Path.of(command).getFileName().toString();
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            if (Files.isExecutable(Path.of(dir, name))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers

    private McpServerRepository.McpServerView view(String id, Map<String, Object> body) {
        return new McpServerRepository.McpServerView(
                id == null ? str(body, "id") : id,
                str(body, "name"),
                str(body, "transport"),
                str(body, "command"),
                args(body),
                str(body, "url"),
                !(body.get("enabled") instanceof Boolean b) || b);
    }

    /** args 支持数组或「一行一个参数」的字符串。 */
    private static List<String> args(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("args");
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        } else if (raw instanceof String text) {
            Arrays.stream(text.split("\\R")).filter(s -> !s.isBlank())
                    .map(String::trim).forEach(out::add);
        }
        return out;
    }

    /** headers 支持对象或「Key: Value」多行文本。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> headers(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("headers");
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && !String.valueOf(k).isBlank()) {
                    out.put(String.valueOf(k).trim(), v == null ? "" : String.valueOf(v).trim());
                }
            });
        } else if (raw instanceof String text) {
            for (String line : text.split("\\R")) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    out.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
            }
        }
        return out;
    }

    private void requireRead() {
        currentUser.requireUserId();
        currentUser.checkPermission("settings:read");
    }

    private void requireWrite() {
        currentUser.requireUserId();
        currentUser.checkPermission("settings:write");
    }

    private String requireWriteUser() {
        String userId = currentUser.requireUserId();
        currentUser.checkPermission("settings:write");
        return userId;
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
