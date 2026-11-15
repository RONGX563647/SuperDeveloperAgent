package top.rongx.agent.domain.conversation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次 DAG 派发计划。
 *
 * @param nodes          节点（id 必须唯一）
 * @param abortOnFailure 必需节点失败后是否停止调度未开始的节点（默认 true）
 * @param maxParallel    同时在跑的节点上限（1-8，默认 4）
 */
public record DispatchPlan(List<DispatchNode> nodes, boolean abortOnFailure, int maxParallel) {

    public static final int DEFAULT_MAX_PARALLEL = 4;
    public static final int MAX_MAX_PARALLEL = 8;

    public DispatchPlan {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("调度计划至少需要一个节点");
        }
        Set<String> ids = new HashSet<>();
        for (DispatchNode node : nodes) {
            if (!ids.add(node.id())) {
                throw new IllegalArgumentException("节点 id 重复：" + node.id());
            }
        }
        for (DispatchNode node : nodes) {
            for (String dep : node.dependsOn()) {
                if (!ids.contains(dep)) {
                    throw new IllegalArgumentException("节点 " + node.id() + " 依赖不存在的节点：" + dep);
                }
            }
        }
        maxParallel = maxParallel <= 0 ? DEFAULT_MAX_PARALLEL
                : Math.min(maxParallel, MAX_MAX_PARALLEL);
    }
}
