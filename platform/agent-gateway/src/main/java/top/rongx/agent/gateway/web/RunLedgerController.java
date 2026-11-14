package top.rongx.agent.gateway.web;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.run.RunLedgerRepository;
import top.rongx.agent.domain.run.RunRecord;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * 统一运行账本查询（对话/工作区、修复、流水线、发布共用一张表）。
 *
 * <p>只读：列表用于「最近运行」一屏查看跨引擎失败，重试仍走各自入口。
 * 与 {@code /api/v1/runs/{runId}}（会话运行详情）路径分开，避免路由歧义。
 */
@RestController
@RequestMapping("/api/v1/run-ledger")
public class RunLedgerController {

    private final RunLedgerRepository ledger;
    private final CurrentUser currentUser;

    public RunLedgerController(RunLedgerRepository ledger, CurrentUser currentUser) {
        this.ledger = ledger;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<RunLedgerView> list(@RequestParam String projectId,
                                    @RequestParam(defaultValue = "50") int limit) {
        String userId = currentUser.requireUserId();
        return ledger.byProject(projectId, Math.min(Math.max(limit, 1), 200)).stream()
                .filter(record -> record.ownerUserId().isBlank()
                        || "system".equals(record.ownerUserId())
                        || userId.equals(record.ownerUserId()))
                .map(RunLedgerView::of)
                .toList();
    }

    /** 账本视图：跨引擎可比的扁平字段，前端按 kind 决定跳转目标。 */
    public record RunLedgerView(String id, String kind, String projectId, String subjectId,
                                String state, String summary, String error,
                                long createdAt, long updatedAt) {

        static RunLedgerView of(RunRecord record) {
            return new RunLedgerView(record.id(), record.kind().name(), record.projectId(),
                    record.subjectId(), record.state().name(),
                    record.summary() == null ? "" : record.summary(),
                    record.error() == null ? "" : record.error(),
                    record.createdAt(), record.updatedAt());
        }
    }
}
