package top.rongx.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import top.rongx.agent.domain.tool.ToolPluginIndex;
import top.rongx.agent.domain.tool.ToolPlugins;

/** 渐进式工具披露：默认只发核心工具，按需/按意图激活插件后追加。 */
class ProgressiveToolMiddlewareTest {

    private static final ToolPluginIndex INDEX = ToolPluginIndex.of(List.of(
                    new ToolPluginIndex.Plugin("repo", "代码", "仓库检索",
                            Set.of("git", "代码"), Set.of("git_status", "read_file")),
                    new ToolPluginIndex.Plugin("ci", "构建", "构建流水线",
                            Set.of("构建"), Set.of("ci_status"))),
            Set.of("project_info", "tool_load", "git_status", "read_file", "ci_status"),
            ToolPlugins.CORE);

    @Test
    @DisplayName("默认只暴露核心与未归类工具，插件工具被裁掉")
    void hidesPluginToolsByDefault() {
        AgentState state = AgentState.builder().sessionId("s").userId("u").build();
        RuntimeContext rc = RuntimeContext.builder().sessionId("s").userId("u").build();
        rc.setAgentState(state);

        AtomicReference<List<ToolSchema>> sent = new AtomicReference<>();
        new ProgressiveToolMiddleware(INDEX, true)
                .onReasoning(null, rc, new ReasoningInput(List.of(user("你好")), schemas(), null),
                        input -> {
                            sent.set(input.tools());
                            return Flux.empty();
                        })
                .blockLast();

        assertEquals(List.of("project_info"), names(sent.get()));
    }

    @Test
    @DisplayName("用户提到 git 时自动加载 repo 插件，并写入会话状态")
    void activatesByIntentAndPersists() {
        AgentState state = AgentState.builder().sessionId("s").userId("u").build();
        RuntimeContext rc = RuntimeContext.builder().sessionId("s").userId("u").build();
        rc.setAgentState(state);

        AtomicReference<List<ToolSchema>> sent = new AtomicReference<>();
        ProgressiveToolMiddleware middleware = new ProgressiveToolMiddleware(INDEX, true);
        middleware.onReasoning(null, rc,
                new ReasoningInput(List.of(user("看下这个 git 分支的代码")), schemas(), null),
                input -> {
                    sent.set(input.tools());
                    return Flux.empty();
                }).blockLast();

        assertEquals(List.of("project_info", "git_status", "read_file"), names(sent.get()));
        assertTrue(state.getToolContext().getActivatedGroups().contains("repo"));

        // 第二轮用户没提关键词：激活状态从会话状态恢复，工具仍在
        AtomicReference<List<ToolSchema>> second = new AtomicReference<>();
        middleware.onReasoning(null, rc, new ReasoningInput(List.of(user("继续")), schemas(), null),
                input -> {
                    second.set(input.tools());
                    return Flux.empty();
                }).blockLast();

        assertEquals(List.of("project_info", "git_status", "read_file"), names(second.get()));
    }

    @Test
    @DisplayName("首轮路由只做一次：后续消息提到其它关键词不再扩工具面")
    void routesOnlyOnFirstTurn() {
        AgentState state = AgentState.builder().sessionId("s").userId("u").build();
        RuntimeContext rc = RuntimeContext.builder().sessionId("s").userId("u").build();
        rc.setAgentState(state);

        ProgressiveToolMiddleware middleware = new ProgressiveToolMiddleware(INDEX, "session");
        middleware.onReasoning(null, rc,
                new ReasoningInput(List.of(user("看下这个 git 分支的代码")), schemas(), null),
                input -> Flux.empty()).blockLast();

        middleware.onReasoning(null, rc,
                new ReasoningInput(List.of(user("再看构建流水线为什么失败")), schemas(), null),
                input -> {
                    assertEquals(List.of("project_info", "git_status", "read_file"),
                            names(input.tools()), "第二轮不应因关键词再加载 ci 插件");
                    return Flux.empty();
                }).blockLast();
    }

    @Test
    @DisplayName("auto-activate=off 时不按意图加载，只保留核心工具")
    void autoActivateOffKeepsCoreOnly() {
        AgentState state = AgentState.builder().sessionId("s").userId("u").build();
        RuntimeContext rc = RuntimeContext.builder().sessionId("s").userId("u").build();
        rc.setAgentState(state);

        new ProgressiveToolMiddleware(INDEX, "off")
                .onReasoning(null, rc,
                        new ReasoningInput(List.of(user("看下这个 git 分支的代码")), schemas(), null),
                        input -> {
                            assertEquals(List.of("project_info"), names(input.tools()));
                            return Flux.empty();
                        }).blockLast();

        assertTrue(state.getToolContext().getActivatedGroups().isEmpty());
    }

    @Test
    @DisplayName("首轮最多自动加载 2 个插件，其余交给 tool_load")
    void routesAtMostTwoPlugins() {
        ToolPluginIndex index = ToolPluginIndex.of(List.of(
                        new ToolPluginIndex.Plugin("a", "A", "a", Set.of("git"), Set.of("git_status")),
                        new ToolPluginIndex.Plugin("b", "B", "b", Set.of("git"), Set.of("ci_status")),
                        new ToolPluginIndex.Plugin("c", "C", "c", Set.of("git"), Set.of("read_file"))),
                Set.of("project_info", "git_status", "ci_status", "read_file"), Set.of("project_info"));
        AgentState state = AgentState.builder().sessionId("s").userId("u").build();
        RuntimeContext rc = RuntimeContext.builder().sessionId("s").userId("u").build();
        rc.setAgentState(state);

        new ProgressiveToolMiddleware(index, "session")
                .onReasoning(null, rc, new ReasoningInput(List.of(user("git")), schemas(), null),
                        input -> Flux.empty()).blockLast();

        assertEquals(List.of("a", "b"), new ArrayList<>(state.getToolContext().getActivatedGroups())
                .stream().filter(name -> Set.of("a", "b", "c").contains(name)).toList());
    }

    private static List<ToolSchema> schemas() {
        List<ToolSchema> tools = new ArrayList<>();
        for (String name : List.of("project_info", "git_status", "read_file", "ci_status")) {
            tools.add(ToolSchema.builder().name(name).description(name).parameters(null).build());
        }
        return tools;
    }

    private static List<String> names(List<ToolSchema> tools) {
        return tools.stream().map(ToolSchema::getName).toList();
    }

    private static Msg user(String text) {
        return Msg.builderForRole(MsgRole.USER).textContent(text).build();
    }
}
