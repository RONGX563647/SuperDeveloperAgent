package top.rongx.agent.domain.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 子会话结论摘要的契约。
 *
 * <p>调度 Agent 的汇报能力此前只到「节点状态字符串」：能说「n2 失败」，
 * 说不出「哪个子会话做完了什么」。这份摘要就是补上那一层。
 */
class ChildDigestTest {

    private static ChildDigest digest(String conversationId, String runStatus, String summary,
                                      String error) {
        return new ChildDigest(conversationId, "子任务 " + conversationId, "IDLE", "run-1",
                runStatus, summary, error, "改一个接口", 1L);
    }

    @Test
    @DisplayName("成功判定只看最近一次运行状态")
    void successFollowsRunStatus() {
        assertTrue(digest("c1", "SUCCEEDED", "已完成", "").succeeded());
        assertFalse(digest("c1", "FAILED", "", "炸了").succeeded());
        assertTrue(digest("c1", "FAILED", "", "炸了").needsAttention());
        assertTrue(digest("c1", "CANCELLED", "", "").needsAttention());
        assertFalse(digest("c1", "RUNNING", "", "").needsAttention());
    }

    @Test
    @DisplayName("一行摘要优先级：结论 > 错误 > 还没有结论")
    void oneLinePrefersConclusion() {
        assertEquals("改好了", digest("c1", "SUCCEEDED", "改好了", "旧的错误").oneLine());
        assertEquals("错误：炸了", digest("c1", "FAILED", "", "炸了").oneLine());
        assertEquals("（还没有结论）", digest("c1", "", "", "").oneLine());
        // 多行结论压成一行，避免把父会话回复撑爆
        assertEquals("第一行 第二行", digest("c1", "SUCCEEDED", "第一行\n第二行", "").oneLine());
    }

    @Test
    @DisplayName("空列表给出可读占位，而不是空字符串")
    void renderHandlesEmpty() {
        assertTrue(ChildDigest.render(List.of()).contains("还没有派发"));
        assertTrue(ChildDigest.render(null).contains("还没有派发"));
    }

    @Test
    @DisplayName("汇总渲染带会话 id、状态与结论，便于父会话直接贴出")
    void renderListsChildren() {
        String text = ChildDigest.render(List.of(
                digest("c1", "SUCCEEDED", "接口改完", ""),
                digest("c2", "FAILED", "", "编译失败")));

        assertTrue(text.startsWith("children:"), text);
        assertTrue(text.contains("c1") && text.contains("[SUCCEEDED]"), text);
        assertTrue(text.contains("接口改完"), text);
        assertTrue(text.contains("c2") && text.contains("[FAILED]"), text);
        assertTrue(text.contains("编译失败"), text);
    }

    @Test
    @DisplayName("没有运行时状态回落到会话状态，不为空")
    void renderFallsBackToConversationStatus() {
        ChildDigest noRun = new ChildDigest("c3", "空会话", "IDLE", "", "", "", "", "", 2L);
        String text = ChildDigest.render(List.of(noRun));

        assertTrue(text.contains("[IDLE]"), text);
        assertTrue(text.contains("还没有结论"), text);
    }
}
