package top.rongx.agent.gateway.web;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

/**
 * 出站 HTTP 客户端：遵循 HTTPS_PROXY / HTTP_PROXY 环境变量。
 *
 * <p>Java 的 HttpClient 不会自动读代理环境变量，而 git（clone/fetch）会；
 * 这里保持一致，避免「git 能拉代码、测试连接却超时」。
 */
final class OutboundHttp {

    private OutboundHttp() {
    }

    static HttpClient client(Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        InetSocketAddress proxy = proxyAddress();
        if (proxy != null) {
            builder.proxy(ProxySelector.of(proxy));
        }
        return builder.build();
    }

    private static InetSocketAddress proxyAddress() {
        String raw = env("HTTPS_PROXY");
        if (raw == null || raw.isBlank()) {
            raw = env("HTTP_PROXY");
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String value = raw.trim();
            URI uri = URI.create(value.contains("://") ? value : "http://" + value);
            if (uri.getHost() == null) {
                return null;
            }
            return new InetSocketAddress(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 8080);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value != null ? value : System.getenv(name.toLowerCase(Locale.ROOT));
    }
}
