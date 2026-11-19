package top.rongx.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BUG-24「工具调用失败会话中断卡死」的回归护栏。
 *
 * <p>成因不是工具本身失败，而是<b>失败之后无法恢复</b>：一次工具调用发出去了却没拿到结果
 * （进程重启 / SSE 静默看门狗掐流 / 用户点停止），框架默认
 * {@code enablePendingToolRecovery=false}，那条 {@code tool_use} 就永久留在会话状态里，
 * 之后每一轮都直接抛 {@code Pending tool calls exist without results}，
 * 用户重发多少次都是同一句——表现就是会话卡死。
 *
 * <p>所以契约有两条，缺一条就会复发：
 * <ol>
 *   <li>平台基线 builder 必须显式打开恢复；</li>
 *   <li>框架默认值是关的——这条钉住「为什么要显式写」，防止有人"简化"掉它。</li>
 * </ol>
 */
class AgentFactoryPendingRecoveryTest {

    @Test
    @DisplayName("平台基线 builder 打开悬挂工具调用恢复")
    void baseBuilderEnablesPendingToolRecovery() {
        ReActAgent agent = AgentFactory.baseBuilder("pending-recovery-probe").build();

        assertTrue(agent.isPendingToolRecoveryEnabled(),
                "工具调用悬挂后必须能自愈，否则该会话之后每轮都报 Pending tool calls exist");
    }

    @Test
    @DisplayName("框架默认为关闭：不显式开启就会复现会话卡死")
    void frameworkDefaultIsOff() {
        ReActAgent bare = ReActAgent.builder().name("framework-default-probe").build();

        assertFalse(bare.isPendingToolRecoveryEnabled(),
                "框架默认值若变成开启，本条测试应删除并在 baseBuilder 的注释里更新原因");
    }
}
