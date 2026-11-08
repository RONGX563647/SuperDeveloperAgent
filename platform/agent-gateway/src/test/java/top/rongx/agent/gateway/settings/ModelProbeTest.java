package top.rongx.agent.gateway.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模型连通性探测：真实 HTTP（本地假 OpenAI 端点）。 */
class ModelProbeTest {

    private HttpServer server;

    private String start(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("2xx → ok + 延迟 + 回复首句")
    void success() throws Exception {
        String baseUrl = start(200,
                "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}");
        ModelProbe probe = new ModelProbe(new ObjectMapper());

        ModelProbe.Result result = probe.probe(baseUrl, "sk-test", "qwen-plus");

        assertTrue(result.ok(), result.message());
        assertEquals("pong", result.reply());
        assertTrue(result.latencyMs() >= 0);
    }

    @Test
    @DisplayName("4xx → 失败并给出可读原因（不回显 key）")
    void failure() throws Exception {
        String baseUrl = start(401, "{\"error\":{\"message\":\"invalid api key\"}}");
        ModelProbe probe = new ModelProbe(new ObjectMapper());

        ModelProbe.Result result = probe.probe(baseUrl, "sk-secret-value", "qwen-plus");

        assertFalse(result.ok());
        assertTrue(result.message().contains("HTTP 401"), result.message());
        assertFalse(result.message().contains("sk-secret-value"), "不能回显密钥");
    }

    @Test
    @DisplayName("不可达端点 → 连接失败（不抛异常）")
    void unreachable() {
        ModelProbe probe = new ModelProbe(new ObjectMapper());
        ModelProbe.Result result = probe.probe("http://127.0.0.1:1/v1", "k", "m");
        assertFalse(result.ok());
        assertTrue(result.message().contains("连接失败"), result.message());
    }
}
