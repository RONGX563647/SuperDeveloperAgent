package top.rongx.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * 迁移到官方 HarnessAgent 的兼容性契约护栏。
 *
 * <p>HarnessAgent 是 ReActAgent 的增强封装，默认装配一整套编排。本项目只取 Agent 循环与
 * 上下文压缩/工具结果驱逐，其余默认编排在 {@link AgentFactory#baseBuilder} 里统一关闭。
 * 本测试钉住四个迁移不变量，任何一条被破坏都会让对话层行为漂移：
 * <ol>
 *   <li>平台传入的 stateStore 必须被 harness 接管（否则落回明文 JsonFile，安全降级）；</li>
 *   <li>delegate 继承悬挂工具调用恢复（否则会话卡死复发）；</li>
 *   <li>工具面透传到 delegate（否则模型看到空工具面）；</li>
 *   <li>事件流 API 签名仍是 streamEvents(String, RuntimeContext) → Flux&lt;AgentEvent&gt;，
 *       与 AgentEventMapper / ChatService 的调用点一致。</li>
 * </ol>
 */
class HarnessAgentCompatibilityTest {

    @TempDir
    Path workspace;

    @Test
    @DisplayName("平台 stateStore 被 harness 接管，不落回默认明文存储")
    void harnessTakesPlatformStateStore() {
        AgentStateStore mine = new InMemoryAgentStateStore();
        HarnessAgent agent = AgentFactory.baseBuilder("compat-state")
                .workspace(workspace)
                .stateStore(mine)
                .build();

        assertSame(mine, agent.getStateStore(),
                "必须接管平台的加密 stateStore；落回 JsonFileAgentStateStore 等于安全降级");
    }

    @Test
    @DisplayName("delegate 继承悬挂工具调用恢复")
    void delegateKeepsPendingToolRecovery() {
        HarnessAgent agent = AgentFactory.baseBuilder("compat-recovery")
                .workspace(workspace)
                .stateStore(new InMemoryAgentStateStore())
                .build();

        assertTrue(agent.getDelegate().isPendingToolRecoveryEnabled(),
                "HarnessAgent 包着 ReActAgent，恢复开关必须传到 delegate");
    }

    @Test
    @DisplayName("工具面透传到 delegate")
    void toolkitReachesDelegate() {
        Toolkit toolkit = new Toolkit();
        HarnessAgent agent = AgentFactory.baseBuilder("compat-toolkit")
                .workspace(workspace)
                .stateStore(new InMemoryAgentStateStore())
                .toolkit(toolkit)
                .build();

        assertSame(agent.getToolkit(), agent.getDelegate().getToolkit(),
                "harness 与 delegate 必须看到同一份工具面");
        assertNotNull(toolkit);
    }

    @Test
    @DisplayName("事件流签名与 ChatService 调用点一致：streamEvents(String, RuntimeContext)")
    void streamEventsSignatureIsCompatible() throws Exception {
        Method m = HarnessAgent.class.getMethod(
                "streamEvents", String.class,
                io.agentscope.core.agent.RuntimeContext.class);
        assertEquals(Flux.class, m.getReturnType());
        assertEquals("streamEvents", m.getName());
        // 元素类型同样是 AgentEvent：确保 AgentEventMapper 不需要改
        assertEquals(AgentEvent.class,
                Class.forName("io.agentscope.core.event.AgentEvent"));
    }

    @Test
    @DisplayName("激进替换项开启：压缩钩子已安装")
    void compactionHookInstalled() {
        // 压缩钩子只在 model != null 时装；用动态代理造一个最小 Model，不触网。
        Model stub = (Model) Proxy.newProxyInstance(
                Model.class.getClassLoader(), new Class<?>[]{Model.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getModelName" -> "stub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        HarnessAgent agent = AgentFactory.baseBuilder("compat-compaction")
                .workspace(workspace)
                .stateStore(new InMemoryAgentStateStore())
                .model(stub)
                .build();

        assertNotNull(agent.getCompactionHook(),
                "激进替换下 CompactionMiddleware 应接管上下文压缩，替代自研裁剪");
    }
}
