package top.rongx.agent.executor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 触发调度：同一 (项目, 环境) 的 <b>默认部署槽</b> 互斥（公平 FIFO），构建阶段完全并行。
 *
 * <p>并行化的依据：每次运行在独立 worktree（{@code wt-build-<runId>}）里检出、构建、打镜像，
 * 每次构建打自己的镜像 tag（含提交 sha），不同运行之间没有共享写资源。真正串行的只有
 * 「该环境的默认部署槽」：旧容器移除、端口租约、默认槽容器命名——一个环境同时只能有一个
 * 默认部署在切换。因此：
 * <ul>
 *   <li>同一 (项目, 环境) 的多个提交同时触发 → 构建并行跑（submit 立即执行）；</li>
 *   <li>进入 deploy 阶段时调用 {@link #acquireDeploySlot}：空闲立即持有；
 *       被占用则当前虚拟线程原地阻塞（公平锁保证按触发顺序依次获得）；</li>
 *   <li>部署槽由持有者运行收尾（execute/executeRetry 的 finally）释放；
 *       打 tag 的版本部署完全隔离，不占槽。</li>
 * </ul>
 *
 * <p>实现选型：公平 {@link ReentrantLock}——公平模式按等待先后授予，天然就是
 * 「先触发的部署先切换默认槽」；执行线程是虚拟线程，阻塞等待不占用平台线程。
 * 相比回调接力，阻塞式让执行线程贯穿部署全程：终态落盘、产物保留、异常处理
 * 都还在原线程的调用栈里，没有跨线程的状态交接。
 *
 * <p>全局并发上限仍由 {@link BuiltInPipelineEngine} 的 workers 线程池控制；
 * 等待部署的数量可见（{@link #pendingCount()}）。
 */
public class TriggerScheduler {

    /**
     * 部署槽令牌：持有者收尾时调用 {@link #release()} 释放。
     * release 由持锁线程调用（ReentrantLock 的约束），幂等由调用方负责（先置空再释放）。
     */
    public interface DeploySlotToken {
        void release();

        /** 无环境概念（如临时任务）时的空令牌：release 什么都不做。 */
        DeploySlotToken NOOP = () -> { };
    }

    /** 每个 (项目, 环境) 一把公平锁：先到先得，保证默认部署按触发顺序切换。 */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 提交一个运行任务：立即执行（构建阶段并行，不再排队）。 */
    public void submit(String projectId, String env, Runnable task) {
        task.run();
    }

    /**
     * 获取该环境的默认部署槽（阻塞、FIFO 公平）。
     *
     * @return 非空令牌 = 已持有（部署完成后必须 release）；
     *         projectId 为空 → 返回 NOOP（不互斥）；
     *         等待中被打断 → 返回 null（未获得，调用方按放弃处理）
     */
    public DeploySlotToken acquireDeploySlot(String projectId, String env) {
        if (projectId == null || projectId.isBlank()) {
            return DeploySlotToken.NOOP; // 没有环境概念（如本机 dev 临时任务）：不互斥
        }
        ReentrantLock lock = locks.computeIfAbsent(key(projectId, env), k -> new ReentrantLock(true));
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            // 吞掉中断标志：不把标志泄漏给后续数据库调用；由调用方决定放弃后的状态
            return null;
        }
        return lock::unlock;
    }

    /** 全局等待部署槽的运行总数（可观测性；不含当前持锁者）。 */
    public int pendingCount() {
        int total = 0;
        for (ReentrantLock lock : locks.values()) {
            total += lock.getQueueLength();
        }
        return total;
    }

    /** 某项目某环境的部署槽是否被占用（含有人排队等待）。 */
    public boolean isRunning(String projectId, String env) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        ReentrantLock lock = locks.get(key(projectId, env));
        return lock != null && lock.isLocked();
    }

    /** 队列键：项目 + 环境。 */
    static String key(String projectId, String env) {
        return projectId + "|" + (env == null || env.isBlank() ? "test" : env);
    }
}
