package top.rongx.agent.domain.conversation;

/**
 * DAG 中一个节点的执行结果。
 *
 * <p>{@code status} 取值：SUCCEEDED / FAILED / CANCELLED / TIMEOUT / SKIPPED / REJECTED。
 * {@code skipped=true} 表示没有真正派发（依赖失败或提前终止）。
 */
public record DispatchNodeResult(
        String nodeId,
        String conversationId,
        String runId,
        String status,
        boolean skipped,
        String summary,
        String error,
        long elapsedMillis,
        String ticketId) {

    public DispatchNodeResult {
        nodeId = nodeId == null ? "" : nodeId;
        conversationId = conversationId == null ? "" : conversationId;
        runId = runId == null ? "" : runId;
        status = status == null || status.isBlank() ? "FAILED" : status;
        summary = summary == null ? "" : summary;
        error = error == null ? "" : error;
        ticketId = ticketId == null ? "" : ticketId;
    }

    /** 兼容旧调用点：没有票据时 ticketId 为空。 */
    public DispatchNodeResult(String nodeId, String conversationId, String runId, String status,
                              boolean skipped, String summary, String error, long elapsedMillis) {
        this(nodeId, conversationId, runId, status, skipped, summary, error, elapsedMillis, "");
    }

    public DispatchNodeResult withTicket(String ticket) {
        return new DispatchNodeResult(nodeId, conversationId, runId, status, skipped, summary,
                error, elapsedMillis, ticket);
    }

    public boolean succeeded() {
        return "SUCCEEDED".equals(status);
    }

    public static DispatchNodeResult skipped(String nodeId, String conversationId, String reason) {
        return new DispatchNodeResult(nodeId, conversationId, "", "SKIPPED", true, "",
                reason, 0);
    }
}
