package top.rongx.agent.domain.env;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 中间件镜像清单：环境模板按 <b>镜像</b> 组织，而不是按某台机器上碰巧存在的容器。
 *
 * <p>同一份「dev / test / prod」模板，切到另一台机器后按镜像名重新拉起即可复现；
 * 容器名、端口、运行状态都只是运行态信息，不作为模板的身份。
 *
 * <p>这里只维护镜像名。容器内端口、数据卷、启动参数仍在
 * {@code DockerEnvProvisioner}（它们属于运行方式，不属于模板）。
 */
public final class MiddlewareImages {

    private static final Map<String, String> DEFAULTS = defaults();

    private static Map<String, String> defaults() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("mysql", "mysql:8.0");
        map.put("mariadb", "mariadb:11");
        map.put("postgres", "postgres:16-alpine");
        map.put("redis", "redis:7-alpine");
        map.put("rabbitmq", "rabbitmq:3.12-management");
        map.put("minio", "minio/minio:latest");
        map.put("mongo", "mongo:7");
        map.put("nacos", "nacos/nacos-server:v2.3.2");
        return Map.copyOf(map);
    }

    private MiddlewareImages() {
    }

    /** 按类型取默认镜像；未知类型返回 null（不猜）。 */
    public static String imageOf(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        return DEFAULTS.get(kind.trim().toLowerCase());
    }

    /** 全量默认镜像（设置页展示「模板会拉起哪些镜像」用）。 */
    public static Map<String, String> defaultsByKind() {
        return DEFAULTS;
    }

    /** 是否为平台已知类型。 */
    public static boolean known(String kind) {
        return imageOf(kind) != null;
    }
}
