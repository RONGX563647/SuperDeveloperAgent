package top.rongx.agent.infra.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import top.rongx.agent.domain.http.HttpClients;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;

/**
 * 模型发现：请求 OpenAI 兼容端点的 {@code GET /models}，解析出可选模型列表。
 *
 * <p>兼容三种常见返回：OpenAI 的 {@code {"data":[{"id":...}]}}、
 * vLLM/Ollama 的 {@code {"data":[...]}} / {@code {"models":[...]}}，
 * 以及直接返回字符串数组的极简实现。本地免鉴权网关允许空 apiKey。
 */
@Component
public class ModelDiscoveryClient {

    private final ObjectMapper objectMapper;
    private final HttpClient http = client();

    public ModelDiscoveryClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @return 去重后的模型 id 列表（保持服务端返回顺序） */
    public List<String> discover(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "baseUrl 不能为空");
        }
        String url = endpoint(baseUrl);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("User-Agent", "agent-platform")
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        try {
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 != 2) {
                throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型列表返回 HTTP " + status + "：" + url);
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型列表请求被中断", e);
        } catch (IOException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型列表不可达：" + e.getMessage(), e);
        }
    }

    List<String> parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body == null ? "{}" : body);
        } catch (IOException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型列表不是合法 JSON：" + e.getMessage(), e);
        }
        List<JsonNode> candidates = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(candidates::add);
        } else {
            for (String field : List.of("data", "models")) {
                JsonNode node = root.get(field);
                if (node != null && node.isArray()) {
                    node.forEach(candidates::add);
                }
            }
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode node : candidates) {
            String id = modelId(node);
            if (id != null && !id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return List.copyOf(ids);
    }

    private static String modelId(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        for (String field : List.of("id", "model", "name")) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    /** baseUrl 末尾补 {@code /models}；已经以 /models 结尾时不重复拼接。 */
    private static String endpoint(String baseUrl) {
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.toLowerCase(Locale.ROOT).endsWith("/models") ? url : url + "/models";
    }

    /** 与 git / 能力市场一致读 HTTPS_PROXY / HTTP_PROXY（统一走 HttpClients）。 */
    private static HttpClient client() {
        return HttpClients.builder(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
