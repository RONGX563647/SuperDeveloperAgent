package top.rongx.agent.runtime.workspace;

/** 强制中止一次运行：用于看门狗处理卡死任务，与用户主动取消区分（终态为 FAILED）。 */
public interface RunAborter {

    /**
     * 强制把运行标记为失败并释放底层资源（事件流、排队任务、线程）。
     *
     * @return true 表示本次调用真正改变了状态；运行已结束或句柄不存在返回 false
     */
    boolean fail(String runId, String reason);
}
