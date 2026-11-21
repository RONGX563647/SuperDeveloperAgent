package top.rongx.agent.domain.docker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 容器运行状态判定：State 优先，Status 回退。 */
class ContainerStateTest {

    @Test
    @DisplayName("State=running 优先于 Status 文本")
    void stateWins() {
        assertTrue(ContainerState.running("running", "Exited (0)"));
        assertFalse(ContainerState.running("exited", "Up 3 minutes"));
    }

    @Test
    @DisplayName("缺 State 时按 Status 判断，暂停不算运行")
    void statusFallback() {
        assertTrue(ContainerState.running(null, "Up 3 minutes"));
        assertFalse(ContainerState.running(null, "Up 3 minutes (Paused)"));
        assertFalse(ContainerState.running(null, "Exited (0) 2 minutes ago"));
        assertFalse(ContainerState.running(null, null));
    }
}
