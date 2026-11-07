package top.rongx.agent.domain.run;

import java.util.List;
import top.rongx.agent.domain.artifact.ArtifactRef;

/**
 * 一个 stage 的执行结果（契约见《规约：流水线执行器 v1.0》§1.3）。
 *
 * @param stageId       stage 标识
 * @param status        终态
 * @param exitCode      进程退出码
 * @param errorCode     错误码（E-xxx），成功时为 null
 * @param errorSummary  面向人的一句话（≤200 字）
 * @param artifacts     产出物
 * @param durationMs    耗时
 * @param retryCount    重试次数
 */
public record StageResult(
        String stageId,
        StageStatus status,
        int exitCode,
        String errorCode,
        String errorSummary,
        List<ArtifactRef> artifacts,
        long durationMs,
        int retryCount) {

    public StageResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public static StageResult success(String stageId, int exitCode, List<ArtifactRef> artifacts, long durationMs) {
        return new StageResult(stageId, StageStatus.SUCCEEDED, exitCode, null, null, artifacts, durationMs, 0);
    }

    public static StageResult failed(String stageId, int exitCode, String errorCode,
                                     String summary, long durationMs) {
        return new StageResult(stageId, StageStatus.FAILED, exitCode, errorCode, summary,
                List.of(), durationMs, 0);
    }

    /** 是否属于"用户代码失败"（E-1xx）：禁止重试（契约 §1.4）。 */
    public boolean isUserFailure() {
        return errorCode != null && errorCode.startsWith("E-1");
    }

    /** 是否属于"平台/环境失败"（E-2xx）：可重试。 */
    public boolean isPlatformFailure() {
        return errorCode != null && errorCode.startsWith("E-2");
    }
}
