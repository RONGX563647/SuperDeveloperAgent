package top.rongx.agent.domain.run;

import java.util.List;
import java.util.Optional;

/**
 * 统一运行账本端口。
 *
 * <p>只做"跨引擎可比的运行视图"与"重启恢复"，不替代各编排自己的状态机；
 * 写入失败不应影响主流程（调用方负责兜底记录日志）。
 */
public interface RunLedgerRepository {

    void save(RunRecord record);

    Optional<RunRecord> find(String id);

    List<RunRecord> byProject(String projectId, int limit);

    List<RunRecord> active();

    /**
     * 把长时间未更新的 RUNNING 记录标记为失败（进程崩溃/重启后的孤儿）。
     *
     * @param staleBefore 早于该时间戳的才算孤儿，避免误杀其他实例正在跑的运行
     * @return 受影响行数
     */
    int markStaleRunning(long staleBefore, String reason);

    /**
     * 清理已结束且超过保留期的运行记录，避免账本无限增长。
     *
     * @param updatedBefore 早于该时间戳的终态记录才会被删除
     * @return 删除行数
     */
    int purgeTerminalBefore(long updatedBefore);

    /** 测试/降级用：不落盘。 */
    static RunLedgerRepository noop() {
        return new RunLedgerRepository() {
            @Override
            public void save(RunRecord record) {
                // 无账本时不做任何事
            }

            @Override
            public Optional<RunRecord> find(String id) {
                return Optional.empty();
            }

            @Override
            public List<RunRecord> byProject(String projectId, int limit) {
                return List.of();
            }

            @Override
            public List<RunRecord> active() {
                return List.of();
            }

            @Override
            public int markStaleRunning(long staleBefore, String reason) {
                return 0;
            }

            @Override
            public int purgeTerminalBefore(long updatedBefore) {
                return 0;
            }
        };
    }
}
