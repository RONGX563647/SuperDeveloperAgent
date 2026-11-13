package top.rongx.agent.domain.bugfix;

/**
 * 单个阶段的执行记录：每次进入阶段都新增一行，保留尝试次数、耗时与证据。
 *
 * <p>运行快照只保留「当前阶段」，历史阶段（尤其是验证失败回到修复后的上一轮证据）
 * 不能互相覆盖，因此必须逐阶段落库。前端时间线与排障都读这里。
 *
 * @param id        记录 id（自增）
 * @param runId     运行 id
 * @param stage     阶段
 * @param attempt   该阶段的第几次尝试（同一运行同一阶段递增）
 * @param status    RUNNING / CONTINUE / BLOCKED / FAILED / CANCELLED
 * @param message   阶段结论文本
 * @param artifact  结构化产物或证据（分析 JSON、部署日志摘要、验证证据）
 * @param startedAt 开始时间
 * @param endedAt   结束时间（0 = 仍在执行）
 * @param durationMs 耗时毫秒（0 = 仍在执行）
 */
public record BugFixStageRun(
        long id,
        String runId,
        BugFixStage stage,
        int attempt,
        String status,
        String message,
        String artifact,
        long startedAt,
        long endedAt,
        long durationMs) {

    public static final String RUNNING = "RUNNING";
    public static final String CONTINUE = "CONTINUE";
    public static final String BLOCKED = "BLOCKED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
}
