package top.rongx.agent.domain.audit;

import java.util.List;

/**
 * 工具审计仓储（只追加）。
 */
public interface ToolAuditStore {

    void record(ToolAudit audit);

    /** 最近若干条（排障 / 设置页展示）。 */
    List<ToolAudit> recent(String projectId, int limit);
}
