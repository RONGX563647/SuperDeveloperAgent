package top.rongx.agent.domain.deploy;

/**
 * 部署结果（进入 stage 结果与证据链）。
 *
 * @param success   是否成功
 * @param target    实际使用的部署目标
 * @param endpoint  访问地址（如 {@code http://127.0.0.1:8080}）
 * @param resource  平台托管的资源标识（容器名 / pid 文件路径 / 远端标识）
 * @param errorCode 失败时的错误码（E-5xx 段：部署失败）
 * @param summary   面向人的一句话
 * @param log       部署过程输出（已脱敏）
 */
public record DeployResult(
        boolean success,
        DeployTarget target,
        String endpoint,
        String resource,
        String errorCode,
        String summary,
        String log) {

    public static DeployResult ok(DeployTarget target, String endpoint, String resource, String log) {
        return new DeployResult(true, target, endpoint, resource, null, "部署成功", log);
    }

    public static DeployResult failed(DeployTarget target, String errorCode, String summary, String log) {
        return new DeployResult(false, target, "", "", errorCode, summary, log);
    }
}
