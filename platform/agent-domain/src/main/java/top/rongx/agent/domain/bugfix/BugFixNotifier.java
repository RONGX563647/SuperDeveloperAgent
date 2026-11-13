package top.rongx.agent.domain.bugfix;

/**
 * 自动修复的关键节点通知端口。
 *
 * <p>实现应在内部吞掉异常：评论同步失败绝不能影响修复运行本身。
 * 典型实现是把「分析结论」「PR 链接」「失败原因」回写到 Git Issue 评论，
 * 让没有打开平台页面的人也能追踪进度。
 */
public interface BugFixNotifier {

    void comment(BugFixRun run, String message);
}
