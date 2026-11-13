package top.rongx.agent.domain.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 项目上下文的安全校验：工作树名来自客户端，必须在进入工具层之前拒绝目录逃逸。
 */
class ProjectContextTest {

    @Test
    @DisplayName("工作树名：允许平台生成的 wt-* 名称")
    void acceptsPlatformWorktreeName() {
        ProjectContext ctx = new ProjectContext("RY", "test", "u1", null, "wt-verify");
        assertEquals("wt-verify", ctx.worktree());
        assertFalse(ctx.readOnly());
    }

    @Test
    @DisplayName("工作树名：拒绝 ../ 逃逸、绝对路径、分隔符")
    void rejectsEscapeNames() {
        for (String bad : new String[]{
                "../../etc", "..", "a/b", "a\\b", "/etc/passwd", "C:/windows", "wt-../x", ".hidden"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProjectContext("RY", "test", "u1", null, bad),
                    "应拒绝工作树名：" + bad);
        }
    }

    @Test
    @DisplayName("空白工作树视为主仓库")
    void blankWorktreeIsNull() {
        assertNull(new ProjectContext("RY", "test", "u1", null, "  ").worktree());
    }
}
