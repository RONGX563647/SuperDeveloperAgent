package top.rongx.agent.arch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.error.ErrorCode;

/** 错误码枚举自身的完整性：格式统一、码值不重复。 */
class ErrorCodeUniquenessTest {

    @Test
    void codesAreUniqueAndWellFormed() {
        Set<String> seen = new HashSet<>();
        for (ErrorCode errorCode : ErrorCode.values()) {
            String code = errorCode.code();
            assertTrue(code.matches("E-\\d{3}"), "错误码格式不合法：" + errorCode + " -> " + code);
            assertTrue(seen.add(code), "错误码重复：" + code + "（" + errorCode + "）");
        }
    }
}
