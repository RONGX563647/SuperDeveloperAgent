package top.rongx.agent.domain.env;

import java.util.List;

/**
 * 本地前后端服务运行时（dev：宿主机进程；平台负责启动/停止/探活）。
 *
 * <p>端口由调用方分配（见服务端口段规则），实现只负责按端口把进程拉起来。
 */
public interface ServiceRuntime {

    /**
     * 一次操作结果。
     *
     * @param ok      是否成功
     * @param port    实际使用的端口（失败时为 0）
     * @param pid     进程 pid（失败/停止时为 0）
     * @param status  RUNNING / STOPPED / UNKNOWN（端口被外部进程占用由调用方判定为 EXTERNAL）
     * @param message 可直接展示的文案
     */
    record Result(boolean ok, int port, long pid, String status, String message) {

        public static Result failed(String message) {
            return new Result(false, 0, 0, "UNKNOWN", message);
        }
    }

    /**
     * 服务可用性：仓库里有没有可运行产物、依赖工具是否就绪。
     *
     * @param supported 是否具备启动条件
     * @param hint      给用户看的说明（不具备条件时是可操作的失败原因）
     */
    record Availability(boolean supported, String hint) {
    }

    /** 启动某个服务（会先停掉同项目同服务的旧进程）。 */
    Result start(String projectId, String env, String service, int port);

    /** 停止某个服务。 */
    Result stop(String projectId, String env, String service);

    /** 实时探活（不看缓存）。 */
    String status(String projectId, String env, String service);

    /** 启动条件探测：前端据此提前禁用按钮并给出原因，避免失败后才报错。 */
    Availability availability(String projectId, String service);

    /** 最近的运行日志（启动失败时用于定位原因）。 */
    List<String> logTail(String projectId, String service, int lines);
}
