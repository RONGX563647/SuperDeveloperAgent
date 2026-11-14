package top.rongx.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.project.ProjectContext;

/** 会话必须绑定到唯一的用户/项目/环境，切项目复用同一个 sessionId 会被拒绝。 */
class ChatSessionRegistryTest {

    private final ChatSessionRegistry registry = new ChatSessionRegistry();

    @Test
    void bindsSessionToProject() {
        ProjectContext ryl = new ProjectContext("RY", "test", "u1", "git@x/ry.git", null, false);
        registry.bindOrVerify("s1", ryl);

        assertTrue(registry.contains("s1"));
        assertDoesNotThrow(() -> registry.bindOrVerify("s1", ryl));
    }

    @Test
    void rejectsSameSessionInAnotherProject() {
        registry.bindOrVerify("s1",
                new ProjectContext("RY", "test", "u1", "git@x/ry.git", null, false));

        assertThrows(IllegalArgumentException.class, () -> registry.bindOrVerify("s1",
                new ProjectContext("NEW", "test", "u1", "git@x/new.git", null, false)));
    }

    @Test
    void rejectsSameSessionInAnotherEnvOrUser() {
        registry.bindOrVerify("s1",
                new ProjectContext("RY", "test", "u1", "git@x/ry.git", null, false));

        assertThrows(IllegalArgumentException.class, () -> registry.bindOrVerify("s1",
                new ProjectContext("RY", "dev", "u1", "git@x/ry.git", null, false)));
        assertThrows(IllegalArgumentException.class, () -> registry.bindOrVerify("s1",
                new ProjectContext("RY", "test", "u2", "git@x/ry.git", null, false)));
    }

    @Test
    void unbindAllowsRebinding() {
        registry.bindOrVerify("s1",
                new ProjectContext("RY", "test", "u1", "git@x/ry.git", null, false));
        registry.unbind("s1");

        assertDoesNotThrow(() -> registry.bindOrVerify("s1",
                new ProjectContext("NEW", "dev", "u1", "git@x/new.git", null, false)));
    }
}
