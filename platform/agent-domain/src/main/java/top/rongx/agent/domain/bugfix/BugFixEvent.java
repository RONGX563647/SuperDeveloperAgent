package top.rongx.agent.domain.bugfix;

/** 自动修复运行的时间线事件（追加写，前端按时间正序展示）。 */
public record BugFixEvent(
        String id,
        String runId,
        BugFixStage stage,
        String level,
        String message,
        long createdAt) {

    public static BugFixEvent info(String runId, BugFixStage stage, String message) {
        return new BugFixEvent("", runId, stage, "INFO", message, System.currentTimeMillis());
    }

    public static BugFixEvent warn(String runId, BugFixStage stage, String message) {
        return new BugFixEvent("", runId, stage, "WARN", message, System.currentTimeMillis());
    }

    public static BugFixEvent error(String runId, BugFixStage stage, String message) {
        return new BugFixEvent("", runId, stage, "ERROR", message, System.currentTimeMillis());
    }
}
