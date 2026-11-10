package top.rongx.agent.domain.env;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中间件资源 → 环境变量的默认映射。
 *
 * <p>第一版采用 RuoYi / Spring 常见的通用键名（DB_* / REDIS_* / RABBITMQ_* / MINIO_*），
 * 后续可按项目类型扩展适配器；项目覆盖永远优先于这里派生的值。
 */
public final class StackAdapter {

    private StackAdapter() {
    }

    /** 根据资源派生默认变量（只派生找到了的资源；运行状态不影响变量内容）。 */
    public static Map<String, String> derive(List<EnvResource> resources) {
        Map<String, String> out = new LinkedHashMap<>();
        if (resources == null) {
            return out;
        }
        for (EnvResource r : resources) {
            if (r == null) {
                continue;
            }
            switch (r.kind()) {
                case "mysql", "mariadb" -> {
                    put(out, "DB_HOST", r.host());
                    put(out, "DB_PORT", String.valueOf(r.port()));
                    put(out, "DB_NAME", r.databaseName());
                    put(out, "DB_USERNAME", r.username());
                    put(out, "DB_PASSWORD", r.password());
                }
                case "redis" -> {
                    put(out, "REDIS_HOST", r.host());
                    put(out, "REDIS_PORT", String.valueOf(r.port()));
                    put(out, "REDIS_PASSWORD", r.password());
                }
                case "rabbitmq" -> {
                    put(out, "RABBITMQ_HOST", r.host());
                    put(out, "RABBITMQ_PORT", String.valueOf(r.port()));
                    put(out, "RABBITMQ_USERNAME", r.username());
                    put(out, "RABBITMQ_PASSWORD", r.password());
                }
                case "minio" -> {
                    put(out, "MINIO_ENDPOINT", "http://" + r.host() + ":" + r.port());
                    put(out, "MINIO_ACCESS_KEY", r.username());
                    put(out, "MINIO_SECRET_KEY", r.password());
                }
                case "postgres" -> {
                    put(out, "DB_HOST", r.host());
                    put(out, "DB_PORT", String.valueOf(r.port()));
                    put(out, "DB_NAME", r.databaseName());
                    put(out, "DB_USERNAME", r.username());
                    put(out, "DB_PASSWORD", r.password());
                }
                default -> {
                    // 未知类型不猜变量
                }
            }
        }
        return out;
    }

    private static void put(Map<String, String> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value);
        }
    }

    /**
     * 该资源是否被项目实际引用（用于"只启动项目用到的中间件"）。
     *
     * <p>判断依据是最终注入值里的连接地址是否指向该资源：若项目用覆盖把 DB_HOST
     * 指向了别的数据库，本地这个容器就不应该被启动。
     *
     * <p>地址有三种合法写法，必须都认：
     * <ul>
     *   <li>{@code host}：宿主机可达地址（dev 本机模式的 127.0.0.1）；</li>
     *   <li>{@code internalHost}：同网络容器内网名（test/prod 的容器名）；</li>
     *   <li>{@code name}：资源展示名与容器名一致时的兜底。</li>
     * </ul>
     * 只比 {@code host} 会把"用容器名连接"的项目误判为未引用，进而导致
     * 中间件登记 STOPPED 假死、env_summary 统计为 0。
     */
    public static boolean isReferenced(EnvResource resource, Map<String, String> values) {
        if (resource == null || values == null || values.isEmpty()) {
            return false;
        }
        if ("minio".equals(resource.kind())) {
            String endpoint = values.get("MINIO_ENDPOINT");
            if (endpoint == null || endpoint.isBlank()) {
                return false;
            }
            // 只比 host 段：http://minio:9000 不应因为包含 "minio" 就命中 test-minio
            String endpointHost = endpointHost(endpoint);
            boolean byInternal = sameAddress(endpointHost, resource.internalHost())
                    || sameAddress(endpointHost, resource.name());
            if (!(byInternal || sameAddress(endpointHost, resource.host()))) {
                return false;
            }
            // 宿主机地址会被多个资源共用，端口必须一致才算同一个实例；
            // 内网名端口是容器端口（9000），宿主机地址端口是发布端口（19000）
            int endpointPort = endpointPort(endpoint);
            int expected = intValue(values.get("MINIO_PORT"));
            int wanted = endpointPort > 0 ? endpointPort : expected;
            int resourcePort = byInternal && resource.containerPort() > 0
                    ? resource.containerPort() : resource.port();
            return wanted <= 0 || resourcePort <= 0 || wanted == resourcePort;
        }
        String value = values.get(hostKey(resource.kind()));
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim();
        // 容器名/内网名是唯一的：精确命中即引用
        if (sameAddress(v, resource.internalHost()) || sameAddress(v, resource.name())) {
            return true;
        }
        // 宿主机地址（127.0.0.1）会被同环境多个实例共用，必须再比端口
        if (!sameAddress(v, resource.host())) {
            return false;
        }
        int expected = intValue(values.get(portKey(resource.kind())));
        return expected > 0 && resource.port() > 0 && expected == resource.port();
    }

    private static int intValue(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean sameAddress(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.trim();
        String y = b.trim();
        return !x.isEmpty() && !y.isEmpty() && x.equalsIgnoreCase(y);
    }

    /** 从 {@code scheme://host:port/path} 里取出 host 段（去协议、端口、路径）。 */
    private static String endpointHost(String endpoint) {
        String value = endpoint.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        return value;
    }

    private static String hostKey(String kind) {
        String k = kind == null ? "" : kind;
        return switch (k) {
            case "redis" -> "REDIS_HOST";
            case "rabbitmq" -> "RABBITMQ_HOST";
            default -> "DB_HOST";
        };
    }

    private static String portKey(String kind) {
        String k = kind == null ? "" : kind;
        return switch (k) {
            case "redis" -> "REDIS_PORT";
            case "rabbitmq" -> "RABBITMQ_PORT";
            default -> "DB_PORT";
        };
    }

    /** 从 {@code scheme://host:port/path} 里取出端口（无端口返回 0）。 */
    private static int endpointPort(String endpoint) {
        String value = endpoint.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.lastIndexOf(':');
        return colon >= 0 ? intValue(value.substring(colon + 1)) : 0;
    }
}
