package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 对话入口的工作树兜底：会话一进入就有写落点，打破「没工作树→open 也被拦→更没工作树」的死锁。
 */
class ChatControllerWorktreeTest {

    @Test
    @DisplayName("客户端显式选择时原样保留（含前后空格去掉）")
    void respectsExplicitChoice() {
        assertEquals("wt-verify", ChatController.defaultWorktree("wt-verify", "s-abc12345", "alice"));
        assertEquals("wt-verify", ChatController.defaultWorktree("  wt-verify  ", "s-abc12345", "alice"));
    }

    @Test
    @DisplayName("未选择时按会话 id 派生，按会话隔离")
    void derivesFromSessionId() {
        String a = ChatController.defaultWorktree(null, "s-aaa11111", "alice");
        String b = ChatController.defaultWorktree("", "s-bbb22222", "alice");
        assertEquals("wt-s-aaa11111", a);
        assertEquals("wt-s-bbb22222", b);
        assertTrue(!a.equals(b), "不同会话必须派生不同工作树");
    }

    @Test
    @DisplayName("sessionId 缺失时用 userId 兜底，绝不返回空")
    void fallsBackToUserId() {
        String out = ChatController.defaultWorktree(null, null, "alice");
        assertTrue(out.startsWith("wt-"), out);
        assertTrue(out.length() <= 64, out);
        assertTrue(out.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"), out);
    }

    @Test
    @DisplayName("派生值满足 ProjectContext 的工作树名校验")
    void derivedNameIsLegal() {
        String out = ChatController.defaultWorktree(null, "s-xyz_9.9", "alice");
        assertTrue(out.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"), out);
    }
}
