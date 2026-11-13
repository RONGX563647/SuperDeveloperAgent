package top.rongx.agent.executor;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 触发调度：同一 (项目, 环境) 串行，不同环境/项目可并行。
 *
 * <p>为什么按环境而不是项目：dev / test / prod 使用的是各自的工作区、端口与镜像，
 * 互相并不争抢；按项目串行会让"dev 正在跑"阻塞 test 发布，白白排队。
 * 同一环境内仍按 FIFO，避免同一套端口/工作区被两个构建同时改写。
 *
 * <p>全局并发上限由 {@link BuiltInPipelineEngine} 的 4 线程池控制；
 * 这里只保证「环境级 FIFO」，且队列可见（{@link #pendingCount()}）。
 */
public class TriggerScheduler {

    private final Map<String, Queue<Runnable>> queued = new ConcurrentHashMap<>();
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    /** 队列键：项目 + 环境。 */
    static String key(String projectId, String env) {
        return projectId + "|" + (env == null || env.isBlank() ? "test" : env);
    }

    /** 提交一个运行任务；若同项目同环境有正在运行的任务则排队。 */
    public synchronized void submit(String projectId, String env, Runnable task) {
        String key = key(projectId, env);
        if (running.getOrDefault(key, false)) {
            queued.computeIfAbsent(key, k -> new ArrayDeque<>()).add(task);
            return;
        }
        running.put(key, true);
        task.run();
    }

    /** 运行结束，取下一条（在同一线程内串行触发，实际执行在线程池）。 */
    public synchronized void complete(String projectId, String env) {
        String key = key(projectId, env);
        Queue<Runnable> q = queued.get(key);
        if (q == null || q.isEmpty()) {
            running.put(key, false);
            return;
        }
        q.poll().run();
    }

    /** 全局排队数量（用于可观测性与限流判断）。 */
    public synchronized int pendingCount() {
        return queued.values().stream().mapToInt(Queue::size).sum();
    }

    /** 某项目某环境当前是否有运行中的任务。 */
    public boolean isRunning(String projectId, String env) {
        return running.getOrDefault(key(projectId, env), false);
    }
}
