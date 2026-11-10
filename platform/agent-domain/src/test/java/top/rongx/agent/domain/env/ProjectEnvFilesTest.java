package top.rongx.agent.domain.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectEnvFilesTest {

    @Test
    void envWinsOverExampleAndBlankNeverMasksValue(@TempDir Path root) throws IOException {
        Path docker = Files.createDirectories(root.resolve("docker"));
        write(docker.resolve(".env.example"), """
                # ── 外部 MySQL（必填）──
                DB_HOST=
                DB_PASSWORD=
                # ── 邮件（163 SMTP，可选）──
                SMTP_USERNAME=
                RONGX_AES_KEY_PREVIOUS=
                """);
        write(docker.resolve(".env"), """
                DB_HOST=111.230.60.71
                DB_PASSWORD=mysql_secret
                SMTP_USERNAME=
                """);
        write(docker.resolve("entrypoint.sh"), """
                require DB_HOST      "外部数据库地址"
                require REDIS_HOST   "外部 Redis 地址"
                require TOKEN_SECRET "JWT 签名密钥"
                """);

        ProjectEnvFiles.Declared declared = ProjectEnvFiles.scan(root);

        assertEquals("111.230.60.71", declared.values().get("DB_HOST"));
        assertEquals("mysql_secret", declared.values().get("DB_PASSWORD"));
        assertFalse(declared.values().containsKey("SMTP_USERNAME"), "空值不应进入注入集合");
        assertEquals(Set.of("DB_HOST", "REDIS_HOST", "TOKEN_SECRET"), declared.required());
        assertTrue(declared.keys().contains("SMTP_USERNAME"), "模板声明的 key 仍需展示");
    }

    @Test
    void blankInDotEnvDoesNotMaskExampleDefault(@TempDir Path root) throws IOException {
        write(root.resolve(".env.example"), "DB_PORT=3306\n");
        write(root.resolve(".env"), "DB_PORT=\n");

        ProjectEnvFiles.Declared declared = ProjectEnvFiles.scan(root);

        assertEquals("3306", declared.values().get("DB_PORT"));
    }

    @Test
    void fallbackSkipsOptionalCommentsAndConfigDefaults(@TempDir Path root) throws IOException {
        write(root.resolve(".env.example"), """
                # ── 邮件（163 SMTP，可选）──
                SMTP_USERNAME=

                # ── 数据库（必填）──
                DB_HOST=

                # 上传超时
                UPLOAD_TIMEOUT=
                """);
        write(root.resolve("src/main/resources/application.yml"), """
                upload:
                  timeout: ${UPLOAD_TIMEOUT:30s}
                """);

        ProjectEnvFiles.Declared declared = ProjectEnvFiles.scan(root);

        assertEquals(Set.of("DB_HOST"), declared.required());
    }

    @Test
    void composeRequiredDeclarationIsAuthoritative(@TempDir Path root) throws IOException {
        write(root.resolve("docker-compose.yml"), """
                services:
                  app:
                    environment:
                      DB_HOST: ${DB_HOST:?数据库地址必填}
                      REDIS_HOST: ${REDIS_HOST:-127.0.0.1}
                """);

        ProjectEnvFiles.Declared declared = ProjectEnvFiles.scan(root);

        assertEquals(Set.of("DB_HOST"), declared.required());
    }

    @Test
    void missingDirectoryReturnsEmpty(@TempDir Path root) {
        ProjectEnvFiles.Declared declared = ProjectEnvFiles.scan(root.resolve("not-exists"));

        assertTrue(declared.values().isEmpty());
        assertTrue(declared.keys().isEmpty());
        assertTrue(declared.required().isEmpty());
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
