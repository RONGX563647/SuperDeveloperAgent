package top.rongx.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ===== BUG-47 补充用例 =====

    /** 缺口 1：redactLine 能识别 PEM 边界行与紧随的 base64 主体行。 */
    @Test
    void redactLineRedactsPemBoundaryAndBodyLines() {
        assertEquals("***PEM***", redactor.redactLine("-----BEGIN RSA PRIVATE KEY-----", ctx));
        assertEquals("***PEM***", redactor.redactLine("-----END RSA PRIVATE KEY-----", ctx));
        String body = "MIIEowIBAAKCAQEA0Z3VS5JJcds3xfn/yGWywfXU8kF";
        assertEquals("***PEM***", redactor.redactLine(body, ctx));
        // 非 PEM 内容不受影响
        assertEquals("hello world 123", redactor.redactLine("hello world 123", ctx));
    }

    /** 缺口 2：isSensitiveFile 补 *.env / secrets.json / *.tfvars / .git-credentials。 */
    @Test
    void moreSensitiveFileNamesAreRejectedButTemplatesAllowed() {
        assertTrue(redactor.isSensitiveFile("deploy/prod.env"));
        assertTrue(redactor.isSensitiveFile("config/secrets.json"));
        assertTrue(redactor.isSensitiveFile("infra/terraform.tfvars"));
        assertTrue(redactor.isSensitiveFile("infra/prod.auto.tfvars"));
        assertTrue(redactor.isSensitiveFile(".git-credentials"));
        // 模板/示例类 .env 后缀仍放行
        assertFalse(redactor.isSensitiveFile(".env.example"));
        assertFalse(redactor.isSensitiveFile("dev.env.example"));
    }

    /** 缺口 3：SECRET_ASSIGN 值含空格（引号内或多词口令到行尾）整值抹掉。 */
    @Test
    void secretAssignmentValueIsNotTruncatedAtSpaces() {
        String out = redactor.redact("password: my long pass phrase", ctx);
        assertFalse(out.contains("long"), out);
        assertFalse(out.contains("phrase"), out);
        assertTrue(out.contains("[REDACTED]"), out);

        String json = redactor.redact("\"apiKey\": \"abc def ghi\"", ctx);
        assertFalse(json.contains("abc def ghi"), json);
        assertTrue(json.contains("[REDACTED]"), json);
    }

    /** 缺口 4：URL_USERINFO 支持空用户名（redis://:pass@host）。 */
    @Test
    void urlUserInfoWithEmptyUsernameIsRedacted() {
        String out = redactor.redact("redis://:s3cret@host:6379/0", ctx);
        assertFalse(out.contains("s3cret"), out);
        assertTrue(out.contains("redis://***:***@host:6379/0"), out);
    }
}
