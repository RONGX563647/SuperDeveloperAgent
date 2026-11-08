package top.rongx.agent.gateway.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 模型连通性探测：调用 OpenAI 兼容端点的 {@code /chat/completions}（max_tokens=1）。
 *
 * <p>安全：只回传状态/延迟/首句，不回显 API Key；错误信息做长度截断。
 */
@Component
public class ModelProbe {

    private final ObjectMapper objectMapper;

    public ModelProbe(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 探测结果。 */
    public record Result(boolean ok, long latencyMs, String reply, String message) {
    }

    public Result probe(String baseUrl, String apiKey, String modelName) {
        String url = trimSlash(baseUrl) + "/chat/completions";
        long start = System.nanoTime();
        try {
            Map<String, Object> body = Map.of(
                    "model", modelName,
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 1,
                    "temperature", 0);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
                    .send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - start) / 1_000_000L;
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new Result(true, latency, firstContent(response.body()),
                        "连接正常，模型已响应");
            }
            return new Result(false, latency, "",
                    "HTTP " + response.statusCode() + "：" + shorten(response.body(), 300));
        } catch (Exception e) {
            long latency = (System.nanoTime() - start) / 1_000_000L;
            return new Result(false, latency, "",
                    "连接失败：" + shorten(String.valueOf(e.getMessage()), 300));
        }
    }

    private String firstContent(String body) {
        try {
            Map<?, ?> root = objectMapper.readValue(body, Map.class);
            Object choices = root.get("choices");
            if (choices instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> choice
                    && choice.get("message") instanceof Map<?, ?> message) {
                Object content = message.get("content");
                return content == null ? "" : shorten(String.valueOf(content), 200);
            }
        } catch (Exception ignored) {
            // 响应不是标准结构时只用于展示"已响应"
        }
        return "";
    }

    private String trimSlash(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String clean = text.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._-]{8,}", "Bearer ***");
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }
}
