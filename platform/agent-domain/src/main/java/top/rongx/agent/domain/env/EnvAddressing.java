package top.rongx.agent.domain.env;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中间件寻址规则：同一份资源登记，在不同运行形态下映射成不同的连接地址。
 *
 * <ul>
 *   <li>{@link RuntimeMode#LOCAL_DEV}：应用是宿主机进程 → {@code 127.0.0.1:发布端口}；</li>
 *   <li>{@link RuntimeMode#LOCAL_TEST}：应用是容器 → {@code 容器名:容器内端口}（docker 网络 DNS）；</li>
 *   <li>{@link RuntimeMode#SERVER}：应用在服务器上 → 资源登记的服务器地址 + 发布端口。</li>
 * </ul>
 *
 * <p>登记值保持不变：{@code port} 是发布到宿主机的端口，{@code containerPort} 是容器内端口，
 * {@code internalHost} 是容器名/服务名。解析后的副本只用于注入与展示。
 */
public final class EnvAddressing {

    private EnvAddressing() {
    }

    /** 按模板形态解析全部资源。 */
    public static List<EnvResource> resolve(List<EnvResource> resources, EnvTemplate template) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        RuntimeMode mode = template == null
                ? RuntimeMode.LOCAL_DEV : template.mode();
        List<EnvResource> out = new ArrayList<>(resources.size());
        for (EnvResource r : resources) {
            if (r != null) {
                out.add(resolve(r, mode));
            }
        }
        return List.copyOf(out);
    }

    /** 单个资源解析（返回副本，不改登记值）。 */
    public static EnvResource resolve(EnvResource r, RuntimeMode mode) {
        if (r == null) {
            return null;
        }
        RuntimeMode effective = mode == null ? RuntimeMode.LOCAL_DEV : mode;
        String host = r.host();
        int port = r.port();
        switch (effective) {
            case LOCAL_DEV -> {
                host = "127.0.0.1";
                port = publishedPort(r);
            }
            case LOCAL_TEST -> {
                host = internalHost(r);
                port = r.containerPort() > 0 ? r.containerPort() : publishedPort(r);
            }
            case SERVER -> port = publishedPort(r);
        }
        if (host == null || host.isBlank()) {
            host = internalHost(r);
        }
        return new EnvResource(r.id(), r.env(), r.kind(), r.name(), host, port,
                r.containerPort(), r.internalHost(), r.databaseName(), r.username(), r.password(),
                r.network(), r.serverId(), r.source(), r.running(), r.lastCheckAt(), r.lastError(),
                r.image());
    }

    /** 容器内 / 共享网络内的地址。 */
    public static String internalHost(EnvResource r) {
        if (r.internalHost() != null && !r.internalHost().isBlank()) {
            return r.internalHost();
        }
        return r.name() == null || r.name().isBlank() ? r.host() : r.name();
    }

    private static int publishedPort(EnvResource r) {
        if (r.port() > 0) {
            return r.port();
        }
        return r.containerPort();
    }

    /**
     * 把变量里以「容器名/内网名」登记的地址改写为宿主机可达地址（本机模式部署用）。
     *
     * <p>这是地址改写的唯一实现：DB / REDIS / RABBITMQ / MINIO / MONGO 全部覆盖，
     * 不再由执行器各自维护一套。找不到同名资源时原值保持不变。
     */
    public static Map<String, String> rewriteToHost(Map<String, String> values,
                                                    List<EnvResource> resources) {
        Map<String, String> out = new LinkedHashMap<>(values == null ? Map.of() : values);
        if (resources == null || resources.isEmpty()) {
            return out;
        }
        rewriteHost(out, resources, "DB", "mysql", "mariadb", "postgres");
        rewriteHost(out, resources, "REDIS", "redis");
        rewriteHost(out, resources, "RABBITMQ", "rabbitmq");
        rewriteHost(out, resources, "MONGO", "mongo");
        rewriteMinio(out, resources);
        return out;
    }

    private static void rewriteHost(Map<String, String> vars, List<EnvResource> resources,
                                    String prefix, String... kinds) {
        String host = vars.get(prefix + "_HOST");
        if (host == null || host.isBlank()) {
            return;
        }
        EnvResource matched = match(host, resources, kinds);
        if (matched == null) {
            return;
        }
        if (matched.host() != null && !matched.host().isBlank()) {
            vars.put(prefix + "_HOST", matched.host());
        }
        if (matched.port() > 0) {
            vars.put(prefix + "_PORT", String.valueOf(matched.port()));
        }
        if ("DB".equals(prefix) && matched.databaseName() != null
                && !matched.databaseName().isBlank()) {
            vars.put(prefix + "_NAME", matched.databaseName());
        }
        if (!"REDIS".equals(prefix) && matched.username() != null
                && !matched.username().isBlank()) {
            vars.put(prefix + "_USERNAME", matched.username());
        }
        if (matched.password() != null && !matched.password().isEmpty()) {
            vars.put(prefix + "_PASSWORD", matched.password());
        }
    }

    private static void rewriteMinio(Map<String, String> vars, List<EnvResource> resources) {
        String endpoint = vars.get("MINIO_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            return;
        }
        String host = endpoint;
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int colon = host.lastIndexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        EnvResource matched = match(host, resources, "minio");
        if (matched == null) {
            return;
        }
        if (matched.host() != null && !matched.host().isBlank() && matched.port() > 0) {
            vars.put("MINIO_ENDPOINT", "http://" + matched.host() + ":" + matched.port());
        }
        if (matched.username() != null && !matched.username().isBlank()) {
            vars.put("MINIO_ACCESS_KEY", matched.username());
        }
        if (matched.password() != null && !matched.password().isEmpty()) {
            vars.put("MINIO_SECRET_KEY", matched.password());
        }
    }

    /** 按 internalHost/name 精确匹配；同名资源优先取正在运行的那个。 */
    private static EnvResource match(String host, List<EnvResource> resources, String... kinds) {
        String name = host.trim();
        EnvResource matched = null;
        for (EnvResource r : resources) {
            if (r == null) {
                continue;
            }
            boolean kindOk = false;
            for (String kind : kinds) {
                if (kind.equals(r.kind())) {
                    kindOk = true;
                    break;
                }
            }
            if (!kindOk) {
                continue;
            }
            if (!name.equalsIgnoreCase(internalHost(r)) && !name.equalsIgnoreCase(r.name())) {
                continue;
            }
            if (matched == null || (!matched.running() && r.running())) {
                matched = r;
            }
            if (r.running()) {
                break;
            }
        }
        return matched;
    }
}
