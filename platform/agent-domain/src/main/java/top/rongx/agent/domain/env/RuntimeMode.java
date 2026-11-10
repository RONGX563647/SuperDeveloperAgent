package top.rongx.agent.domain.env;

/**
 * 环境运行形态：决定前后端跑在哪、中间件怎么寻址。
 *
 * <ul>
 *   <li>{@link #LOCAL_DEV}：本机 Docker 中间件 + 本机进程前后端（平台托管端口与启停）；</li>
 *   <li>{@link #LOCAL_TEST}：本机 Docker 中间件（与 dev 隔离）+ 前后端各一个容器；</li>
 *   <li>{@link #SERVER}：服务器上的 Docker 中间件与前后端。</li>
 * </ul>
 */
public enum RuntimeMode {

    LOCAL_DEV,
    LOCAL_TEST,
    SERVER;

    /** 解析（兼容空值与大小写）；空值按环境名给默认形态。 */
    public static RuntimeMode of(String raw, String env) {
        if (raw != null && !raw.isBlank()) {
            for (RuntimeMode mode : values()) {
                if (mode.name().equalsIgnoreCase(raw.trim())) {
                    return mode;
                }
            }
        }
        return defaultFor(env);
    }

    public static RuntimeMode defaultFor(String env) {
        return switch (env == null ? "" : env) {
            case "dev" -> LOCAL_DEV;
            case "test" -> LOCAL_TEST;
            default -> SERVER;
        };
    }

    /** 前后端是否跑在宿主机进程里（只有 dev 是）。 */
    public boolean localProcess() {
        return this == LOCAL_DEV;
    }

    /** 中间件是否由本机 Docker 提供。 */
    public boolean localDocker() {
        return this == LOCAL_DEV || this == LOCAL_TEST;
    }
}
