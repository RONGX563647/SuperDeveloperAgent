package top.rongx.agent.domain.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 出站 HTTP 客户端构造器（唯一实现）。
 *
 * <p>Java 的 {@link HttpClient} <b>不会</b>自动读 {@code HTTPS_PROXY / HTTP_PROXY}，
 * 而 git（clone/fetch）会。平台里每个出站调用点都自己解析一遍代理环境变量：
 * 有的读了大小写两种写法、有的只读大写；有的设了超时、有的没有。于是同一条网络下
 * 「git 能拉代码、测试连接却超时」，或者反过来。
 *
 * <p>本类提供 {@link #builder} / {@link #withProxy}，统一：连接超时、代理环境变量、
 * 默认不自动重定向（由调用方按语义决定）。纯 JDK 实现。
 */
public final class HttpClients {

    /** 未显式指定时的连接超时。 */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private HttpClients() {
    }

    /** 连接超时 + 代理环境变量的 {@link HttpClient.Builder}，供需要追加选项的调用方使用。 */
    public static HttpClient.Builder builder(Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout);
        InetSocketAddress proxy = proxyAddress();
        if (proxy != null) {
            // 本机模型（Ollama / LM Studio）与回环地址不应走代理：走代理会被企业网关拒绝
            builder.proxy(new LoopbackBypass(ProxySelector.of(proxy)));
        }
        return builder;
    }

    /** 使用默认连接超时。 */
    public static HttpClient.Builder builder() {
        return builder(DEFAULT_CONNECT_TIMEOUT);
    }

    /** 连接超时 + 代理环境变量，直接构建客户端。 */
    public static HttpClient withProxy(Duration connectTimeout) {
        return builder(connectTimeout).build();
    }

    /** 默认连接超时的客户端。 */
    public static HttpClient withProxy() {
        return builder().build();
    }

    /** 解析 {@code HTTPS_PROXY}，其次 {@code HTTP_PROXY}（大小写都认）；无代理返回 null。 */
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

    /** 回环地址直连，其余交给上游代理选择器。 */
    private static final class LoopbackBypass extends ProxySelector {

        private final ProxySelector delegate;

        private LoopbackBypass(ProxySelector delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri == null ? null : uri.getHost();
            if (host == null || host.equalsIgnoreCase("localhost") || host.startsWith("127.")
                    || host.equals("::1") || host.equals("[::1]")) {
                return List.of(Proxy.NO_PROXY);
            }
            return delegate.select(uri);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            delegate.connectFailed(uri, address, failure);
        }
    }
}
