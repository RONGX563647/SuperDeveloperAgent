package top.rongx.agent.domain.run;

import java.util.List;

/**
 * 运行失败后的自动诊断端口。
 *
 * <p>流水线是确定性执行器：失败后由本端口决定"要不要让部署 Agent 介入"。
 * 实现放在 gateway（可访问模型与工具），执行器只按端口调用并把返回的每一步
 * 写进运行日志（前缀 {@code [agent]}），前端即可看到 Agent 的工具调用步骤。
 *
 * <p>返回空列表 = 不介入（如生产环境、非 UI 触发、已是 Agent 自己触发的运行）。
 */
public interface RunRecoveryAdvisor {

    /**
     * 诊断并修复一次失败的运行。
     *
     * @param context 失败运行的可读上下文（项目 / 环境 / 流水线 / 提交 / 触发人 / 日志尾部）
     * @return 需要追加到运行日志的行（不含 {@code [agent]} 前缀）
     */
    List<String> advise(Context context);

    /** 是否装配了可用的诊断实现（false 时执行器直接跳过，不写误导性的 "[agent] 开始" 日志）。 */
    default boolean available() {
        return true;
    }

    /** 关闭自动诊断（单测与未装配模型时使用）。 */
    static RunRecoveryAdvisor noop() {
        return context -> List.of();
    }

    /**
     * 失败运行上下文。
     *
     * @param actor 触发者（{@code ui:xxx} / {@code agent}）；只有 UI 触发才自动诊断，避免 Agent 触发→失败→再触发递归
     * @param log   运行日志尾部（已截断，供模型定位根因）
     */
    record Context(String runId, String projectId, String env, String pipelineId,
                   String gitSha, String actor, String log) {
    }
}
