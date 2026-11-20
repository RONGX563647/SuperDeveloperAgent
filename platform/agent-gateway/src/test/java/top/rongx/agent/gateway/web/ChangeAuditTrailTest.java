package top.rongx.agent.gateway.web;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.port.AuditTrail;
import top.rongx.agent.domain.port.ChangeStore;
import top.rongx.agent.domain.port.InMemoryAuditTrail;
import top.rongx.agent.governance.ChangeRegistry;
import top.rongx.agent.governance.ConfirmTokenService;
import top.rongx.agent.governance.GovernanceDb;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.gateway.security.PasswordVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BUG-37：确认变更后审计落库——audit_event 必须有一条 change:confirm 记录。
 *
 * <p>真实 ChangeRegistry + 内存令牌（不碰磁盘上的令牌库），AuditTrail 用内存实现断言；
 * 只覆盖 /confirm 写入点，apply 走 ActionService 全链路（由 ActionUsabilityTest 覆盖）。
 */
class ChangeAuditTrailTest {

    @TempDir
    Path tempDir;

    private final InMemoryAuditTrail audit = new InMemoryAuditTrail();

    private final CurrentUser user = new CurrentUser() {
        @Override
        public String requireUserId() {
            return "devops";
        }
    };

    private final PasswordVerifier alwaysOk = (userId, rawPassword) -> true;

    /** 关闭缓存的独立库，否则 Windows 上 @TempDir 删除会因 SQLite 文件占用失败。 */
    @AfterEach
    void releaseDb() {
        GovernanceDb.closeAll();
    }

    private ChangeController controller(ChangeStore registry, ConfirmTokenService tokens) {
        return new ChangeController(registry, tokens, alwaysOk,
                null, null, user, null, null, audit);
    }

    @Test
    @DisplayName("确认变更 → audit_event 记录一条 change:confirm，actor/目标可追溯")
    void confirmWritesAuditEvent() {
        ChangeRegistry registry = new ChangeRegistry(tempDir.toString());
        ChangeController controller = controller(registry, new ConfirmTokenService(java.time.Clock.systemUTC()));

        ChangePlan plan = registry.request("RY", "test", "stack_deploy", "部署到测试环境",
                null, null, false);
        controller.confirm(plan.changeId(), new ChangeController.ConfirmRequest(null));

        assertEquals(1, audit.events().size(), "确认后应有且只有一条审计记录");
        InMemoryAuditTrail.Event event = audit.events().get(0);
        assertEquals("change:confirm", event.action());
        assertEquals("change_plan", event.targetType());
        assertEquals(plan.changeId(), event.targetId(), "target_id = changeId，变更单清理后仍可追溯");
        assertEquals("RY", event.projectId());
        assertEquals("test", event.env());
        assertEquals("OK", event.result());
        assertEquals("devops", event.actor());
    }

    @Test
    @DisplayName("高危确认密码失败 → result=DENIED，同样留痕")
    void deniedConfirmAlsoRecorded() {
        ChangeRegistry registry = new ChangeRegistry(tempDir.toString());
        // 密码校验失败的验证器
        PasswordVerifier alwaysFail = (userId, rawPassword) -> false;
        ChangeController controller = new ChangeController(registry,
                new ConfirmTokenService(java.time.Clock.systemUTC()), alwaysFail,
                null, null, user, null, null, audit);

        ChangePlan plan = registry.request("RY", "prod", "stack_deploy", "生产部署",
                null, null, true);
        assertThrows(Exception.class,
                () -> controller.confirm(plan.changeId(), new ChangeController.ConfirmRequest("wrong")));

        assertEquals(1, audit.events().size(), "被拒的确认也要留痕");
        InMemoryAuditTrail.Event event = audit.events().get(0);
        assertEquals("change:confirm", event.action());
        assertEquals("DENIED", event.result());
        assertEquals(plan.changeId(), event.targetId());
    }

    @Test
    @DisplayName("AuditTrail 端口语义：record 只追加，count 按 action+targetId 统计")
    void inMemoryTrailAppendsOnly() {
        AuditTrail trail = audit;
        trail.record("a", null, "change:confirm", "change_plan", "chg-1", null, null, "OK", null);
        trail.record("b", null, "change:apply", "change_plan", "chg-1", null, null, "OK", null);
        trail.record("c", null, "change:confirm", "change_plan", "chg-2", null, null, "OK", null);

        assertEquals(3, audit.events().size(), "append-only：只增不减");
        assertEquals(1, audit.count("change:confirm", "chg-1"));
        assertEquals(1, audit.count("change:apply", "chg-1"));
        assertEquals(0, audit.count("change:apply", "chg-2"));
    }

    /** 占位引用：保留 Map 导入语义（确认响应 meta 结构相关），避免误删。 */
    @SuppressWarnings("unused")
    private static Map<String, String> noop() {
        return Map.of();
    }
}
