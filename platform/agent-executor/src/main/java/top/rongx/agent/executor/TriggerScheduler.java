package top.rongx.agent.executor;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 触发调度：同一项目串行（避免争抢工作区/缓存），不同项目可并行。
 *
 * <p>全局并发上限由 {@link BuiltInPipelineEngine} 的 4 线程池控制；
 * 这里只保证「项目级 FIFO」，且队列可见（{@link #pendingCount()}）。
 */
public class TriggerScheduler {

    private final Map<String, Queue<Runnable>> queued = new ConcurrentHashMap<>();
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    /** 提交一个运行任务；若同项目有正在运行的任务则排队。 */
    public synchronized void submit(String projectId, Runnable task) {
        if (running.getOrDefault(projectId, false)) {
            queued.computeIfAbsent(projectId, k -> new ArrayDeque<>()).add(task);
            return;
        }
        running.put(projectId, true);
        task.run();
    }

    /** 运行结束，取下一条（在同一线程内串行触发，实际执行在线程池）。 */
    public synchronized void complete(String projectId) {
        Queue<Runnable> q = queued.get(projectId);
        if (q == null || q.isEmpty()) {
            running.put(projectId, false);
            return;
        }
        q.poll().run();
    }

    /** 全局排队数量（用于可观测性与限流判断）。 */
    public synchronized int pendingCount() {
        return queued.values().stream().mapToInt(Queue::size).sum();
    }

    /** 某项目当前是否有运行中的任务。 */
    public boolean isRunning(String projectId) {
        return running.getOrDefault(projectId, false);
    }
}
