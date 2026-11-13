package top.rongx.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.project.ProjectContext;

/** 脱敏是最后一道保险：常见凭据形态与凭据文件名都必须被拦住。 */
class SecretRedactorTest {

    /** 环境存储传 null：这些用例只验证形态级规则（值级会因取不到环境而跳过）。 */
    private final SecretRedactor redactor = new SecretRedactor(null);

    private final ProjectContext ctx = new ProjectContext("RY", "test", "u1", "", null);

    @Test
    void bearerTokenIsFullyRedacted() {
        String out = redactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig", ctx);
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9.payload.sig"), out);
        assertTrue(out.contains("[REDACTED]"), out);
    }

    @Test
    void cookieHeaderIsRedacted() {
        String out = redactor.redact("Set-Cookie: session=abc123; Path=/; HttpOnly", ctx);
        assertFalse(out.contains("abc123"), out);
    }

    @Test
    void knownTokenPrefixesAreRedacted() {
        String github = "ghp_0123456789abcdefghijABCDEFGHIJ0123";
        String openai = "sk-0123456789abcdefghijklmnop";
        assertFalse(redactor.redact("token=" + github, ctx).contains(github));
        assertFalse(redactor.redactLine("key:" + openai, ctx).contains(openai));
    }

    @Test
    void assignmentsAndUrlsAreRedacted() {
        assertFalse(redactor.redact("spring.datasource.password=SuperSecret!", ctx)
                .contains("SuperSecret!"));
        assertFalse(redactor.redact("db: postgres://admin:s3cret@db:5432/app", ctx)
                .contains("s3cret"));
    }

    @Test
    void privateKeyBlockIsRedacted() {
        String pem = """
                -----BEGIN RSA PRIVATE KEY-----
                MIIEowIBAAKCAQEA
                -----END RSA PRIVATE KEY-----""";
        String out = redactor.redact(pem, ctx);
        assertFalse(out.contains("MIIEowIBAAKCAQEA"), out);
    }

    @Test
    void sensitiveFileNamesAreRejectedButTemplatesAllowed() {
        assertTrue(redactor.isSensitiveFile(".env"));
        assertTrue(redactor.isSensitiveFile("config/.env.prod"));
        assertTrue(redactor.isSensitiveFile("deploy/server.pem"));
        assertTrue(redactor.isSensitiveFile("home/.pgpass"));
        assertTrue(redactor.isSensitiveFile(".netrc"));
        assertFalse(redactor.isSensitiveFile(".env.example"));
        assertFalse(redactor.isSensitiveFile("src/main/resources/application.yml"));
    }
}
