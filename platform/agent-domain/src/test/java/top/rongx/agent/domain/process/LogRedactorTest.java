package top.rongx.agent.domain.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 运行日志脱敏的唯一实现。
 *
 * <p>守住的回归：脱敏规则曾有两份（执行内核与网关修复链），阈值不同、各缺对方两条规则，
 * 于是"同一段密钥写进运行日志脱了、写进 Issue 评论没脱"。合并时必须把两边规则都保留：
 * JWT 与 URL 内嵌凭据原先只有网关侧有。
 */
class LogRedactorTest {

    @Test
    @DisplayName("赋值形密钥：token/password/secret 一律打码，键名保留")
    void assignmentShapedSecrets() {
        String text = "token=abcdefgh12345 password: hunter2hunter2 secret = s3cr3tvalue";

        String out = LogRedactor.redact(text);

        assertFalse(out.contains("abcdefgh12345"), out);
        assertFalse(out.contains("hunter2hunter2"), out);
        assertFalse(out.contains("s3cr3tvalue"), out);
        assertTrue(out.contains("token=***"), out);
    }

    @Test
    @DisplayName("前缀形凭证：GitHub / GitLab / Slack / AWS / OpenAI 都命中")
    void prefixedTokens() {
        String text = "ghp_abcdefgh12345678 glpat-abcdefgh12345678 xoxb-abcdefgh1234 "
                + "AKIAIOSFODNN7EXAMPLE sk-abcdefgh1234";

        String out = LogRedactor.redact(text);

        for (String secret : List.of("ghp_abcdefgh12345678", "glpat-abcdefgh12345678",
                "xoxb-abcdefgh1234", "AKIAIOSFODNN7EXAMPLE", "sk-abcdefgh1234")) {
            assertFalse(out.contains(secret), "未脱敏：" + secret + " → " + out);
        }
    }

    @Test
    @DisplayName("JWT 三段必须命中（原执行内核缺这条规则）")
    void jwtIsRedacted() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";

        String out = LogRedactor.redact("Authorization 里带了 " + jwt);

        assertFalse(out.contains(jwt), out);
    }

    @Test
    @DisplayName("URL 内嵌凭据必须命中（原执行内核缺这条规则）")
    void urlCredentialsAreRedacted() {
        String out = LogRedactor.redact("cloning https://user:sup3rs3cret@git.example.com/repo.git");

        assertFalse(out.contains("sup3rs3cret"), out);
        assertTrue(out.contains("https://***@git.example.com/repo.git"), out);
    }

    @Test
    @DisplayName("sha256:<hex> 是构建证据，必须保留；裸 64 位 hex 才打码")
    void digestIsEvidenceNotSecret() {
        String hex = "a".repeat(64);

        String digest = LogRedactor.redact("镜像 sha256:" + hex);
        String bare = LogRedactor.redact("jwt 第二段 " + hex);

        assertTrue(digest.contains(hex), "sha256 指纹必须保留：" + digest);
        assertFalse(bare.contains(hex), bare);
    }

    @Test
    @DisplayName("PEM 私钥块与 Cookie 整行")
    void pemAndCookie() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----";

        assertEquals("***PEM***", LogRedactor.redact(pem));
        assertEquals("***COOKIE***", LogRedactor.redact("Cookie: SESSION=abc123def456"));
    }

    @Test
    @DisplayName("值级脱敏：长值先替换，短值（<4 字符）忽略，避免打碎正常日志")
    void valueLevelRedaction() {
        String text = "echo 输出 super-secret-value 以及 abc";

        String out = LogRedactor.redact(text, List.of("super-secret-value", "abc", "  "));

        assertFalse(out.contains("super-secret-value"), out);
        assertTrue(out.contains("abc"), "短值不打码，否则正常日志会被切碎：" + out);
    }

    @Test
    @DisplayName("空输入原样返回（不抛异常）")
    void nullAndEmptyAreSafe() {
        assertEquals(null, LogRedactor.redact(null));
        assertEquals("", LogRedactor.redact(""));
        assertEquals(null, LogRedactor.redact(null, List.of("x")));
        assertEquals("plain", LogRedactor.redact("plain", null));
    }
}
