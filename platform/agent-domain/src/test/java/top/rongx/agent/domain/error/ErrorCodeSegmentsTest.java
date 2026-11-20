package top.rongx.agent.domain.error;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 新增错误码回归：能力缺失与测试/脚本/镜像失败码必须存在且分组正确（BUG-32 / BUG-39）。 */
class ErrorCodeSegmentsTest {

    @Test
    void capabilityUnsupportedIsDeployGroupAndDistinctFromRollbackVersionMissing() {
        // BUG-32：多服务回滚失败不能再用"版本缺失"码误导运维去翻镜像仓库
        assertEquals("E-510", ErrorCode.CAPABILITY_UNSUPPORTED.code());
        assertEquals(ErrorGroup.DEPLOY, ErrorCode.CAPABILITY_UNSUPPORTED.group());
        assertNotEquals(ErrorCode.ROLLBACK_VERSION_MISSING.code(),
                ErrorCode.CAPABILITY_UNSUPPORTED.code());
    }

    @Test
    void exitFailureCodesAreInBuildSegment() {
        // BUG-39：退出码语义细分 —— 测试/镜像/脚本不再全部挤进 E-101 编译失败
        assertEquals("E-101", ErrorCode.COMPILE_FAILED.code());
        assertEquals("E-102", ErrorCode.TEST_FAILED.code());
        assertEquals("E-103", ErrorCode.IMAGE_BUILD_FAILED.code());
        assertEquals("E-104", ErrorCode.SCRIPT_FAILED.code());
        assertEquals(ErrorGroup.BUILD, ErrorCode.TEST_FAILED.group());
        assertEquals(ErrorGroup.BUILD, ErrorCode.IMAGE_BUILD_FAILED.group());
        assertEquals(ErrorGroup.BUILD, ErrorCode.SCRIPT_FAILED.group());
        for (ErrorCode code : new ErrorCode[]{ErrorCode.COMPILE_FAILED,
                ErrorCode.TEST_FAILED, ErrorCode.IMAGE_BUILD_FAILED, ErrorCode.SCRIPT_FAILED}) {
            assertTrue(code.code().startsWith("E-1"), code + " 必须在构建段");
        }
    }
}
