package top.rongx.agent.domain.conversation;

import java.util.List;
import java.util.Optional;

/**
 * 一次 DAG 派发的聚合结果（按计划顺序返回，便于调度 Agent 直接汇报）。
 *
 * @param earlyStopped 是否发生了提前终止（失败熔断或必需节点已全部成功）
 * @param reason       提前终止原因；未提前终止时为空
 */
public record DispatchResult(
        boolean earlyStopped,
        String reason,
        long elapsedMillis,
        List<DispatchNodeResult> nodes) {

    public DispatchResult {
        reason = reason == null ? "" : reason;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public Optional<DispatchNodeResult> find(String nodeId) {
        return nodes.stream().filter(n -> n.nodeId().equals(nodeId)).findFirst();
    }

    public long count(String status) {
        return nodes.stream().filter(n -> n.status().equals(status)).count();
    }

    public long succeeded() {
        return count("SUCCEEDED");
    }

    public long failed() {
        return nodes.stream()
                .filter(n -> !n.succeeded() && !n.skipped())
                .count();
    }

    public long skipped() {
        return nodes.stream().filter(DispatchNodeResult::skipped).count();
    }
}
