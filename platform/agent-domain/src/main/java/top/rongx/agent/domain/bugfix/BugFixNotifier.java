package top.rongx.agent.domain.bugfix;

/**
 * 自动修复的关键节点通知端口。
 *
 * <p>典型实现是把「分析结论」「PR 链接」「失败原因」回写到 Git Issue 评论，
 * 让没有打开平台页面的人也能追踪进度。
 *
 * <p>投递失败应抛出异常（而不是静默吞掉）：编排器把通知写入发件箱，
 * 由调度器按指数退避重试，避免评论接口抖动导致通知永久丢失。
 */
public interface BugFixNotifier {

    void comment(BugFixRun run, String message);
}
