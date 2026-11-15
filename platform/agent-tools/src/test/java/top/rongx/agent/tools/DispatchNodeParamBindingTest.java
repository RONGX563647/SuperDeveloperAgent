package top.rongx.agent.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.conversation.DispatchNode;

/**
 * 验证 DAG 工具的嵌套参数能被 AgentScope 正确反序列化成 record 列表。
 *
 * <p>这是 workspace_dispatch 的契约：模型给的是 JSON 数组，工具层必须拿到
 * {@code List<DispatchNode>}，否则整套并行调度在真实调用时会静默失败。
 */
class DispatchNodeParamBindingTest {

    @Test
    @DisplayName("JSON 数组参数可以绑定为 List<DispatchNode>")
    void bindsNestedNodeList() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ProbeTools());

        Map<String, Object> input = Map.of("nodes", List.of(
                Map.of("id", "build", "message", "构建", "required", true),
                Map.of("id", "test", "message", "测试", "depends_on", List.of("build"))));
        ToolCallParam param = ToolCallParam.builder()
                .input(input)
                .toolUseBlock(ToolUseBlock.builder().id("call-1").name("dispatch_probe")
                        .input(input).build())
                .runtimeContext(RuntimeContext.builder().sessionId("s1").userId("u1").build())
                .build();

        ToolResultBlock result = toolkit.getTool("dispatch_probe").callAsync(param).block();

        assertTrue(result.getOutput().toString().contains("build"),
                "嵌套参数未正确绑定：" + result.getOutput());
        assertTrue(result.getOutput().toString().contains("test"));
    }

    /** 探针工具：与 workspace_dispatch 使用同样的参数形状。 */
    static final class ProbeTools {
        @Tool(name = "dispatch_probe", description = "探针", readOnly = false,
                concurrencySafe = false)
        public String probe(@ToolParam(name = "nodes", description = "节点") List<DispatchNode> nodes) {
            return nodes.stream().map(DispatchNode::id).reduce("", (a, b) -> a + " " + b);
        }
    }
}
