package top.rongx.agent.domain.env;

import java.util.List;

/**
 * 中间件运行时（docker 容器的启停）。
 *
 * <p>只管理 docker 来源（LOCAL_DOCKER / REMOTE_DOCKER）的资源；手工登记的资源
 * 返回失败，避免平台误操作外部实例。
 */
public interface EnvRuntime {

    /**
     * 一次启停的结果。
     *
     * @param ok      是否全部成功
     * @param changed 是否真的启动了容器（false = 本来就在运行）
     * @param started 启动成功的资源名
     * @param stopped 停止成功的资源名
     * @param failed  失败信息（"名称：原因"）
     * @param message 汇总文案（可直接写进部署日志）
     */
    record Result(boolean ok, boolean changed, List<String> started, List<String> stopped,
                  List<String> failed, String message) {

        public Result {
            started = started == null ? List.of() : List.copyOf(started);
            stopped = stopped == null ? List.of() : List.copyOf(stopped);
            failed = failed == null ? List.of() : List.copyOf(failed);
        }

        public static Result ok(List<String> started) {
            return new Result(true, started != null && !started.isEmpty(), started, List.of(),
                    List.of(), started == null || started.isEmpty()
                    ? "中间件已在运行" : "已启动 " + String.join("、", started));
        }

        public static Result failed(String name, String reason) {
            return new Result(false, false, List.of(), List.of(),
                    List.of(name + "：" + reason), name + " 启动失败：" + reason);
        }
    }

    /** 启动项目实际引用、且当前未运行的中间件（部署前调用）。 */
    Result ensureRunning(String projectId, String env);

    /** 启动单个资源。 */
    Result start(String resourceId);

    /** 停止单个资源。 */
    Result stop(String resourceId);

    /** 空实现（单测/未装配时使用）：不启停，也不阻塞部署。 */
    static EnvRuntime noop() {
        return new EnvRuntime() {
            @Override
            public Result ensureRunning(String projectId, String env) {
                return Result.ok(List.of());
            }

            @Override
            public Result start(String resourceId) {
                return new Result(false, false, List.of(), List.of(), List.of(),
                        "未启用中间件运行时");
            }

            @Override
            public Result stop(String resourceId) {
                return start(resourceId);
            }
        };
    }
}
