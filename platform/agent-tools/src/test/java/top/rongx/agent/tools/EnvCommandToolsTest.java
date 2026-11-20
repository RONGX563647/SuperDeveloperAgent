package top.rongx.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.env.EnvBinding;
import top.rongx.agent.domain.env.EnvBindingPort;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.governance.EnvPolicyService;

/**
 * 环境绑定草案：变量齐全时平台自动确认（不再要求人工点一张无事可做的卡），
 * 有缺失项或实现不支持时仍退回人工确认卡。
 */
class EnvCommandToolsTest {

    private static final ProjectContext CTX =
            new ProjectContext("P", "test", "u", "repo", null, false);

    private static final String READY_MAPPING = "{\"keys\":[],\"missing\":[],\"warnings\":[]}";
    private static final String MISSING_MAPPING =
            "{\"keys\":[{\"key\":\"DB_URL\"}],\"missing\":[\"DB_URL\"],\"warnings\":[]}";

    private static final class FakeBindings implements EnvBindingPort {
        final String mappingJson;
        final boolean confirmSupported;
        int confirmCalls;

        FakeBindings(String mappingJson, boolean confirmSupported) {
            this.mappingJson = mappingJson;
            this.confirmSupported = confirmSupported;
        }

        @Override
        public EnvBinding draft(String projectId, String env, String actor) {
            return new EnvBinding("bind-cc0913c1", projectId, env, mappingJson,
                    "hash", actor, null, 0, 0, "DRAFT");
        }

        @Override
        public EnvBinding requireForRun(String bindingId, String projectId, String env) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EnvBinding confirm(String bindingId, String confirmedBy) {
            if (!confirmSupported) {
                throw new UnsupportedOperationException("不支持自动确认");
            }
            confirmCalls++;
            return new EnvBinding(bindingId, "P", "test", mappingJson,
                    "hash", "agent", confirmedBy, 1L, 9L, "CONFIRMED");
        }
    }

    private static EnvCommandTools tools(EnvBindingPort bindings) {
        return new EnvCommandTools(bindings, null, null, new EnvPolicyService());
    }

    @Test
    @DisplayName("BUG-52：env_var_set 走统一环境策略门（prod DENY / test ALLOW）")
    void setVarGoesThroughEnvPolicyGate() {
        FakeBindings bindings = new FakeBindings(READY_MAPPING, true);
        EnvCommandTools commandTools = new EnvCommandTools(bindings, null, null,
                new EnvPolicyService());

        // test 环境 L2=ALLOW：正常放行（envStore=null 会在写入处 NPE，但策略门必须先过——
        // 用 prod 的拒绝来验证门存在）
        top.rongx.agent.domain.project.ProjectContext prod =
                new top.rongx.agent.domain.project.ProjectContext("P", "prod", "u", "repo", null, false);
        org.junit.jupiter.api.Assertions.assertThrows(
                top.rongx.agent.domain.policy.PolicyDeniedException.class,
                () -> commandTools.setVar("prod", "SERVER_PORT", "8080", null, null, prod));
    }

    @Test
    @DisplayName("变量齐全：平台自动确认，可直接 deploy_run")
    void autoConfirmsWhenNothingToFill() {
        FakeBindings bindings = new FakeBindings(READY_MAPPING, true);

        String out = tools(bindings).mappingDraft("test", CTX);

        assertEquals(1, bindings.confirmCalls);
        assertTrue(out.contains("status: CONFIRMED"), out);
        assertTrue(out.contains("平台自动确认"), out);
        assertTrue(out.contains("已自动确认"), out);
        assertTrue(out.contains("bindingId: bind-cc0913c1"), out);
    }

    @Test
    @DisplayName("存在缺失项：保持 DRAFT，等人工补值并确认")
    void keepsDraftWhenValuesMissing() {
        FakeBindings bindings = new FakeBindings(MISSING_MAPPING, true);

        String out = tools(bindings).mappingDraft("test", CTX);

        assertEquals(0, bindings.confirmCalls, "缺值时不能替用户决定");
        assertTrue(out.contains("status: DRAFT"), out);
        assertTrue(out.contains("missing: DB_URL"), out);
        assertTrue(out.contains("环境绑定确认卡已在对话中展开"), out);
    }

    @Test
    @DisplayName("实现不支持自动确认：退回人工确认卡")
    void fallsBackWhenConfirmUnsupported() {
        FakeBindings bindings = new FakeBindings(READY_MAPPING, false);

        String out = tools(bindings).mappingDraft("test", CTX);

        assertTrue(out.contains("status: DRAFT"), out);
        assertTrue(out.contains("需人工确认"), out);
        assertTrue(out.contains("环境绑定确认卡已在对话中展开"), out);
    }
}
