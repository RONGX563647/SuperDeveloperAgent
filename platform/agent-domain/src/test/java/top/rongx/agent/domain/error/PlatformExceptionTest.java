package top.rongx.agent.domain.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.error.PlatformException;

class PlatformExceptionTest {

    @Test
    void carriesCodeOfItsOwnGroup() {
        DeployException error = new DeployException(ErrorCode.DEPLOY_START_FAILED, "启动失败");
        assertEquals("E-502", error.code());
        assertEquals(ErrorGroup.DEPLOY, error.errorCode().group());
    }

    @Test
    void rejectsCodeFromAnotherGroup() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DeployException(ErrorCode.ENV_BINDING_INVALID, "跨组"));
        assertEquals(true, error.getMessage().contains("E-703"));
    }
}
