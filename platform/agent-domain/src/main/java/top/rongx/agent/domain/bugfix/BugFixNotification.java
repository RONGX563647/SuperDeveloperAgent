package top.rongx.agent.domain.bugfix;

/**
 * 通知发件箱条目：Issue 评论先落库，再由后台按退避重试投递。
 *
 * <p>阶段推进不能因为远端评论接口超时/限流而失败，也不能丢通知；因此写库与投递解耦。
 */
public record BugFixNotification(
        long id,
        String runId,
        String message,
        String status,
        int attempts,
        long nextAttemptAt,
        String lastError,
        long createdAt) {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
}
