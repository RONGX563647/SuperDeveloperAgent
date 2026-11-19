package top.rongx.agent.domain.conversation;

import java.util.List;

/**
 * 子会话结论摘要：父（调度）会话汇报时读的就是这个。
 *
 * <p><b>为什么需要</b>：DAG 派发已经能建子会话、发目标、等结果、重试与熔断，
 * 但父会话拿到的是「每个节点的状态字符串」。真正汇报时要说清「哪个子会话做完了什么」，
 * 就需要一份按会话组织的结论摘要，而不是让上层再去翻每个 run。
 *
 * @param conversationId 子会话 id（独立会话）
 * @param title          会话标题
 * @param status         会话状态（RUNNING / IDLE / FAILED / CANCELLED ...）
 * @param runId          最近一次运行 id；没有运行时为空
 * @param runStatus      最近一次运行状态；没有运行时为空
 * @param summary        结论摘要（一行，来自子会话最近一次结果）
 * @param error          失败原因（成功或未运行时为空）
 * @param goal           最近一次运行的目标
 * @param updatedAt      最近更新时间（毫秒）
 */
public record ChildDigest(
        String conversationId,
        String title,
        String status,
        String runId,
        String runStatus,
        String summary,
        String error,
        String goal,
        long updatedAt) {

    public ChildDigest {
        conversationId = conversationId == null ? "" : conversationId;
        title = title == null ? "" : title;
        status = status == null ? "" : status;
        runId = runId == null ? "" : runId;
        runStatus = runStatus == null ? "" : runStatus;
        summary = summary == null ? "" : summary;
        error = error == null ? "" : error;
        goal = goal == null ? "" : goal;
    }

    /** 成功的判定：最近一次运行 SUCCEEDED。 */
    public boolean succeeded() {
        return "SUCCEEDED".equals(runStatus);
    }

    /** 需要人关注：失败或取消。 */
    public boolean needsAttention() {
        return "FAILED".equals(runStatus) || "CANCELLED".equals(runStatus);
    }

    /** 一行摘要：结论优先，其次是错误，最后是「还没有结论」。 */
    public String oneLine() {
        String text = !summary.isBlank() ? summary : !error.isBlank() ? "错误：" + error : "（还没有结论）";
        return text.replaceAll("\\s+", " ").trim();
    }

    /** 一份人类可读的汇总（调度会话直接贴进回复）。 */
    public static String render(List<ChildDigest> digests) {
        if (digests == null || digests.isEmpty()) {
            return "children: （还没有派发过子会话）";
        }
        StringBuilder sb = new StringBuilder("children:\n");
        for (ChildDigest digest : digests) {
            sb.append("- ").append(digest.conversationId())
                    .append("  [").append(digest.runStatus().isBlank() ? digest.status() : digest.runStatus())
                    .append("]  ").append(digest.title().isBlank() ? "（无标题）" : digest.title())
                    .append('\n');
            sb.append("   ").append(digest.oneLine()).append('\n');
        }
        return sb.toString().trim();
    }
}
