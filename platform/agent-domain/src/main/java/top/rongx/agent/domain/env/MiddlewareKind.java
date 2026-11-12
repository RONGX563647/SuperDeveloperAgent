package top.rongx.agent.domain.env;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 中间件类型的唯一出处：变量键名、端口、卷、镜像、默认账号全部收敛在这里。
 *
 * <p>此前 {@link StackAdapter} 与执行面的 Docker 供给器各自维护了一套
 * "kind → 变量名"的 switch，加一种中间件要改多个文件，且已经出现 minio
 * 端口键名不一致的漂移。新增类型只需在这里加一个枚举常量。
 */
public enum MiddlewareKind {

    MYSQL("mysql", true, "DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD",
            3306, 13306, "/var/lib/mysql", "root", "mysql:8.0", false),
    MARIADB("mariadb", true, "DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD",
            3306, 13306, "/var/lib/mysql", "root", "mysql:8.0", false),
    POSTGRES("postgres", true, "DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD",
            5432, 15432, "/var/lib/postgresql/data", "postgres", "postgres:16-alpine", false),
    REDIS("redis", false, "REDIS_HOST", "REDIS_PORT", null, null, "REDIS_PASSWORD",
            6379, 16379, "/data", null, "redis:7-alpine", false),
    RABBITMQ("rabbitmq", false, "RABBITMQ_HOST", "RABBITMQ_PORT", null,
            "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD",
            5672, 15673, "/var/lib/rabbitmq", "admin", "rabbitmq:3.12-management", false),
    MINIO("minio", false, "MINIO_ENDPOINT", "MINIO_ENDPOINT", null,
            "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY",
            9000, 19000, "/data", "minioadmin", "minio/minio:latest", true),
    MONGO("mongo", false, "MONGO_HOST", "MONGO_PORT", "MONGO_DATABASE",
            "MONGO_USERNAME", "MONGO_PASSWORD",
            27017, 17017, "/data/db", "root", "mongo:7", false);

    /** 资源登记里的 kind 值。 */
    public final String wireName;
    /** 关系型：需要库名派生，且默认生成库名。 */
    public final boolean relational;
    public final String hostKey;
    public final String portKey;
    public final String databaseKey;
    public final String usernameKey;
    public final String passwordKey;
    /** 容器内端口。 */
    public final int containerPort;
    /** 宿主机发布端口的起始值。 */
    public final int hostPortBase;
    public final String volumePath;
    public final String defaultUser;
    public final String image;
    /** true = host 变量本身是 URL（minio），不再单独派生端口键。 */
    public final boolean endpointUrl;

    MiddlewareKind(String wireName, boolean relational, String hostKey, String portKey,
                   String databaseKey, String usernameKey, String passwordKey,
                   int containerPort, int hostPortBase, String volumePath,
                   String defaultUser, String image, boolean endpointUrl) {
        this.wireName = wireName;
        this.relational = relational;
        this.hostKey = hostKey;
        this.portKey = portKey;
        this.databaseKey = databaseKey;
        this.usernameKey = usernameKey;
        this.passwordKey = passwordKey;
        this.containerPort = containerPort;
        this.hostPortBase = hostPortBase;
        this.volumePath = volumePath;
        this.defaultUser = defaultUser;
        this.image = image;
        this.endpointUrl = endpointUrl;
    }

    public static Optional<MiddlewareKind> of(String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return Optional.empty();
        }
        String value = wireName.trim();
        for (MiddlewareKind kind : values()) {
            if (kind.wireName.equalsIgnoreCase(value)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /**
     * 必填变量名 → 中间件类型（没命中返回 empty）。
     *
     * <p>与历史行为保持一致：{@code DB_*} / {@code MYSQL*} / {@code POSTGRES*} 统一视为 mysql。
     */
    public static Optional<MiddlewareKind> fromKey(String key) {
        String k = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
        if (k.startsWith("RABBITMQ")) {
            return Optional.of(RABBITMQ);
        }
        if (k.startsWith("MINIO")) {
            return Optional.of(MINIO);
        }
        if (k.startsWith("REDIS")) {
            return Optional.of(REDIS);
        }
        if (k.startsWith("MONGO")) {
            return Optional.of(MONGO);
        }
        if (k.startsWith("DB_") || k.startsWith("MYSQL") || k.startsWith("POSTGRES")) {
            return Optional.of(MYSQL);
        }
        return Optional.empty();
    }

    /** 按资源派生默认变量（只写非空值）。 */
    public Map<String, String> derive(EnvResource resource, String host, int port) {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, hostKey, endpointUrl ? "http://" + host + ":" + port : host);
        if (!endpointUrl) {
            put(out, portKey, String.valueOf(port));
        }
        if (relational) {
            put(out, databaseKey, resource.databaseName());
        }
        put(out, usernameKey, resource.username());
        put(out, passwordKey, resource.password());
        return out;
    }

    /** {@code docker run} 需要的环境变量参数（仅容器初始化相关）。 */
    public java.util.List<String> dockerEnvArgs(String database, String username, String password) {
        return switch (this) {
            case MYSQL, MARIADB -> java.util.List.of(
                    "-e", "MYSQL_ROOT_PASSWORD=" + nullSafe(password),
                    "-e", "MYSQL_DATABASE=" + nullSafe(database));
            case POSTGRES -> java.util.List.of(
                    "-e", "POSTGRES_USER=" + nullSafe(username),
                    "-e", "POSTGRES_PASSWORD=" + nullSafe(password),
                    "-e", "POSTGRES_DB=" + nullSafe(database));
            case RABBITMQ -> java.util.List.of(
                    "-e", "RABBITMQ_DEFAULT_USER=" + nullSafe(username),
                    "-e", "RABBITMQ_DEFAULT_PASS=" + nullSafe(password));
            case MINIO -> java.util.List.of(
                    "-e", "MINIO_ROOT_USER=" + nullSafe(username),
                    "-e", "MINIO_ROOT_PASSWORD=" + nullSafe(password));
            case MONGO -> java.util.List.of(
                    "-e", "MONGO_INITDB_ROOT_USERNAME=" + nullSafe(username),
                    "-e", "MONGO_INITDB_ROOT_PASSWORD=" + nullSafe(password));
            case REDIS -> java.util.List.of();
        };
    }

    /** 镜像之后的追加命令（redis 需要显式 requirepass）。 */
    public java.util.List<String> containerCommand(String password) {
        if (this == REDIS && password != null && !password.isBlank()) {
            return java.util.List.of("redis-server", "--requirepass", password);
        }
        if (this == MINIO) {
            return java.util.List.of("server", "/data");
        }
        return java.util.List.of();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static void put(Map<String, String> out, String key, String value) {
        if (key != null && value != null && !value.isBlank()) {
            out.put(key, value);
        }
    }
}
