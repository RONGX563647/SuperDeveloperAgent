package top.rongx.agent.domain.bugfix;

import java.util.List;

/** 通知发件箱端口：入队、取待投递、标记结果。 */
public interface BugFixNotificationStore {

    void enqueue(String runId, String message);

    /** 取到期可投递的通知（按 id 升序）。 */
    List<BugFixNotification> due(int limit);

    void markSent(long id);

    /** 投递失败：attempts+1，指数退避后再试；超过上限标记 FAILED。 */
    void markFailed(long id, String error, int maxAttempts);
}
