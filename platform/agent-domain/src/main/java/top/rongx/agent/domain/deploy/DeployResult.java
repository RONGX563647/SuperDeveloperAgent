package top.rongx.agent.domain.deploy;

/**
 * 部署结果（进入 stage 结果与证据链）。
 *
 * @param success    是否成功
 * @param target     实际使用的部署目标
 * @param endpoint   访问地址（如 {@code http://127.0.0.1:8080}）
 * @param resource   平台托管的资源标识（容器名 / pid 文件路径 / 远端标识）
 * @param errorCode  失败时的错误码（E-5xx 段：部署失败）
 * @param summary    面向人的一句话
 * @param log        部署过程输出（已脱敏）
 * @param reasonCode 机器可读原因码（Agent 据此选下一步策略）
 * @param suggestion 建议动作（与 reasonCode 配套）
 * @param rolledBack 失败后是否已自动回滚到上一版
 */
public record DeployResult(
        boolean success,
        DeployTarget target,
        String endpoint,
        String resource,
        String errorCode,
        String summary,
        String log,
        ReasonCode reasonCode,
        String suggestion,
        boolean rolledBack) {

    public DeployResult {
        reasonCode = reasonCode == null ? ReasonCode.OK : reasonCode;
        suggestion = suggestion == null ? "" : suggestion;
    }

    /** 兼容旧调用（无结构化诊断）。 */
    public DeployResult(boolean success, DeployTarget target, String endpoint, String resource,
                        String errorCode, String summary, String log) {
        this(success, target, endpoint, resource, errorCode, summary, log,
                DeployDiagnosis.classify(success, errorCode, summary, log), "", false);
    }

    public static DeployResult ok(DeployTarget target, String endpoint, String resource, String log) {
        return new DeployResult(true, target, endpoint, resource, null, "部署成功", log,
                ReasonCode.OK, "", false);
    }

    public static DeployResult failed(DeployTarget target, String errorCode, String summary,
                                      String log) {
        ReasonCode code = DeployDiagnosis.classify(false, errorCode, summary, log);
        return new DeployResult(false, target, "", "", errorCode, summary, log,
                code, code.suggestion(), false);
    }

    /** 失败且已回滚：success=false（本次发布没成功），但运行态已恢复到上一版。 */
    public static DeployResult rolledBack(DeployTarget target, String errorCode, String summary,
                                          String log) {
        ReasonCode code = DeployDiagnosis.classify(false, errorCode, summary, log);
        return new DeployResult(false, target, "", "", errorCode,
                summary + "（已自动回滚到上一版）", log, ReasonCode.ROLLED_BACK,
                ReasonCode.ROLLED_BACK.suggestion(), true);
    }
}
