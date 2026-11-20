package top.rongx.agent.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import top.rongx.agent.domain.error.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退出码语义与脱敏回归（BUG-39 / BUG-46）：
 * 过去所有非 0 退出码都标 E-101"编译失败"、sk-/ghp_/AKIA 类凭证在构建日志里不被识别。
 */
class ProcessExecutorFailureKindTest {

    @TempDir
    Path temp;

    private static final String TOKEN = "sk-proj-abcdef1234567890";
    private static final String GITHUB = "ghp_AbCdEfGh1234567890aBcDeFgHiJkLm1234";
    private static final String AWS = "AKIAIOSFODNN7EXAMPLEKEY";

    // ------------------------------------------------------------- BUG-39 映射

    @Test
    @DisplayName("按动作 wireName 映射：test→E-102，image_package→E-103，build→E-101")
    void mapsByAction() {
        assertEquals(ProcessExecutor.FailureKind.TEST,
                ProcessExecutor.FailureKind.ofAction("backend_test"));
        assertEquals(ProcessExecutor.FailureKind.TEST,
                ProcessExecutor.FailureKind.ofAction("frontend_test"));
        assertEquals(ProcessExecutor.FailureKind.IMAGE_BUILD,
                ProcessExecutor.FailureKind.ofAction("image_package"));
        assertEquals(ProcessExecutor.FailureKind.COMPILE,
                ProcessExecutor.FailureKind.ofAction("backend_build"));
    }

    @Test
    @DisplayName("未知动作按命令特征推断：mvn test→TEST，pnpm test→TEST，mvn package→COMPILE，docker build→IMAGE_BUILD，其余→SCRIPT")
    void infersFromArgv() {
        assertEquals(ProcessExecutor.FailureKind.TEST,
                ProcessExecutor.FailureKind.fromArgv(List.of("mvn", "-q", "-B", "test")));
        assertEquals(ProcessExecutor.FailureKind.TEST,
                ProcessExecutor.FailureKind.fromArgv(List.of("pnpm", "test")));
        assertEquals(ProcessExecutor.FailureKind.COMPILE,
                ProcessExecutor.FailureKind.fromArgv(List.of("mvn", "-q", "-B", "package")));
        assertEquals(ProcessExecutor.FailureKind.IMAGE_BUILD,
                ProcessExecutor.FailureKind.fromArgv(List.of("docker", "build", "-t", "x", ".")));
        assertEquals(ProcessExecutor.FailureKind.SCRIPT,
                ProcessExecutor.FailureKind.fromArgv(List.of("sh", "deploy.sh")));
    }

    @Test
    @DisplayName("错误码与摘要按类型区分，exit 数值原样透出")
    void realProcessMapsExitCodeByKind() {
        ProcessExecutor executor = new ProcessExecutor();
        // Windows 上 mvn 未必可用：用 java - 退出码稳定验证映射管道
        ProcessExecutor.StepOutcome script = executor.exec(temp,
                List.of("java", "-version", "-badflag"), 30_000, new StringBuilder(), () -> false);
        if (script.exitCode() != 0) {
            assertEquals(ErrorCode.SCRIPT_FAILED.code(), script.errorCode(),
                    "非 mvn/pnpm/docker 命令失败必须是 E-104（实际：" + script.summary() + "）");
            assertTrue(script.summary().contains("exit " + script.exitCode()));
        }

        // 直接断言 Kind 的码与摘要模板（不依赖外部命令）
        for (ProcessExecutor.FailureKind kind : ProcessExecutor.FailureKind.values()) {
            String code = kind.errorCode();
            String summary = kind.summary(1, List.of("cmd"));
            assertTrue(code.startsWith("E-1"), code);
            switch (kind) {
                case TEST -> assertEquals(ErrorCode.TEST_FAILED.code(), code);
                case IMAGE_BUILD -> assertEquals(ErrorCode.IMAGE_BUILD_FAILED.code(), code);
                case COMPILE -> assertEquals(ErrorCode.COMPILE_FAILED.code(), code);
                case SCRIPT -> assertEquals(ErrorCode.SCRIPT_FAILED.code(), code);
            }
            assertFalse(summary.isBlank());
        }
        // 137 被点出"被信号终止"
        String sig = ProcessExecutor.FailureKind.SCRIPT.summary(137, List.of("sh"));
        assertTrue(sig.contains("被信号终止"), sig);
    }

    // ------------------------------------------------------------- BUG-46 脱敏

    @Test
    @DisplayName("TOKEN_PREFIX：sk-/ghp_/AKIA 出现在构建输出中被抹掉")
    void redactsTokenPrefixes() {
        String out = ProcessExecutor.redact(
                "OPENAI_API_KEY=" + TOKEN + "\n"
                        + "git push with " + GITHUB + "\n"
                        + "aws key " + AWS + "\n");
        assertFalse(out.contains(TOKEN), out);
        assertFalse(out.contains(GITHUB), out);
        assertFalse(out.contains(AWS), out);
        assertTrue(out.contains("***"), out);
    }

    @Test
    @DisplayName("其它前缀族：gho_/ghu_/ghs_/ghr_/glpat-/xoxb- 全部脱敏")
    void redactsAllPrefixFamilies() {
        String text = "gho_abcdefghijklmnop1234 ghu_abcdefghijklmnop1234 "
                + "ghs_abcdefghijklmnop1234 ghr_abcdefghijklmnop1234 "
                + "glpat-abcdefghijklmnop xoxb-1234567890abcdef";
        String out = ProcessExecutor.redact(text);
        for (String secret : text.split(" ")) {
            assertFalse(out.contains(secret), out);
        }
    }

    @Test
    @DisplayName("Bearer 阈值统一为 8：8 位以上 Bearer 被抹，短值不误伤")
    void bearerThresholdIsEight() {
        String eight = ProcessExecutor.redact("Authorization: Bearer abcd1234");
        assertFalse(eight.contains("abcd1234"), eight);

        String shortValue = ProcessExecutor.redact("Authorization: Bearer abcd123");
        assertTrue(shortValue.contains("abcd123"), shortValue);
    }

    @Test
    @DisplayName("sha256:<64hex> 指纹保留；Cookie 行整行脱敏")
    void keepsDigestAndRedactsCookie() {
        String digest = "9c2a6d0f5b8e4a1c7d3f6b9e2a5c8d1f4e7b0a3c6d9f2e5b8c1a4d7f0e3b6a9c";
        String kept = ProcessExecutor.redact("digest sha256:" + digest);
        assertTrue(kept.contains(digest), kept);

        String cookie = ProcessExecutor.redact("Set-Cookie: session=abcdef0123456789; Path=/");
        assertFalse(cookie.contains("abcdef0123456789"), cookie);
    }
}
