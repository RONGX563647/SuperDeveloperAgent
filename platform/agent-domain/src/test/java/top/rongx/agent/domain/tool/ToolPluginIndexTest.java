package top.rongx.agent.domain.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 工具插件索引：核心常驻、插件按需、意图路由、提示词索引稳定。 */
class ToolPluginIndexTest {

    private static final Set<String> TOOLS = Set.of(
            "project_info", "tool_load", "tool_search",
            "git_status", "read_file", "ci_status", "deploy_run",
            "mcp_tool_x", "unknown_tool");

    private static ToolPluginIndex index() {
        return ToolPluginIndex.of(List.of(
                        new ToolPluginIndex.Plugin("repo", "代码", "仓库检索",
                                Set.of("git", "代码"), Set.of("git_status", "read_file")),
                        new ToolPluginIndex.Plugin("ci", "构建", "构建流水线",
                                Set.of("构建"), Set.of("ci_status")),
                        new ToolPluginIndex.Plugin("deploy", "发布", "部署发布",
                                Set.of("部署"), Set.of("deploy_run")),
                        // 定义里有、工具面没有的工具必须被过滤
                        new ToolPluginIndex.Plugin("ghost", "幽灵", "不存在的插件",
                                Set.of("幽灵"), Set.of("ghost_tool"))),
                TOOLS, ToolPlugins.CORE);
    }

    @Test
    @DisplayName("核心默认可见，未归类工具归入 other 插件、默认隐藏")
    void coreVisibleUngroupedInOther() {
        ToolPluginIndex index = index();

        assertTrue(index.visible("project_info", Set.of()));
        assertFalse(index.visible("mcp_tool_x", Set.of()), "未归类工具不再常驻");
        assertFalse(index.visible("unknown_tool", Set.of()));
        assertTrue(index.visible("mcp_tool_x", Set.of("other")));
        assertTrue(index.visible("unknown_tool", Set.of("other")));
        assertFalse(index.visible("git_status", Set.of()));
        assertTrue(index.visible("git_status", Set.of("repo")));
    }

    @Test
    @DisplayName("只保留真实存在的工具，空插件被丢弃")
    void dropsEmptyPlugins() {
        ToolPluginIndex index = index();

        assertEquals(4, index.plugins().size());
        assertTrue(index.plugin("ghost").isEmpty());
        assertEquals(Set.of("git_status", "read_file"), index.plugins().get(0).tools());
        assertEquals("other", index.plugins().get(3).name());
        assertEquals(Set.of("mcp_tool_x", "unknown_tool"), index.plugins().get(3).tools());
    }

    @Test
    @DisplayName("按用户输入关键词路由插件")
    void routesByKeyword() {
        ToolPluginIndex index = index();

        assertEquals(Set.of("repo"), index.route("帮我看看这个 git 分支的代码"));
        assertEquals(Set.of("deploy", "ci"),
                index.route("部署失败，先看构建流水线"));
        assertTrue(index.route("你好").isEmpty());
    }

    @Test
    @DisplayName("工单相关意图（关闭/评论/优先级/合并）能路由到 issue 插件")
    void routesIssueIntent() {
        ToolPluginIndex index = ToolPluginIndex.of(ToolPlugins.standard(),
                Set.of("list_git_issues", "create_git_issue", "close_git_issue",
                        "comment_git_issue", "update_git_issue"),
                ToolPlugins.CORE);

        assertEquals(Set.of("issue"), index.route("把这个工单关闭"));
        assertEquals(Set.of("issue"), index.route("给 issue 加评论并调整优先级"));
        assertEquals(Set.of("issue"), index.route("合并重复的缺陷单"));
    }

    @Test
    @DisplayName("提示词索引只给插件摘要，不含完整 schema，且顺序稳定")
    void promptIndexIsCompactAndStable() {
        String text = index().promptIndex();

        assertTrue(text.contains("repo"));
        assertTrue(text.contains("deploy"));
        assertFalse(text.contains("parameters"));
        assertEquals(text, index().promptIndex());
    }
}
