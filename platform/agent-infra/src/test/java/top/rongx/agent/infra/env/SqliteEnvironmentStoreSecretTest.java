package top.rongx.agent.infra.env;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.infra.security.SecretBox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 密钥引用：解析、掩码、摘要不含明文、缺失 fail-closed。 */
class SqliteEnvironmentStoreSecretTest {

    @TempDir
    Path workspace;

    private SqliteEnvironmentStore store() {
        return new SqliteEnvironmentStore(workspace.toString(),
                new SecretBox(workspace.toString(), ""));
    }

    @Test
    @DisplayName("整值 secret:// 引用解析为明文，视图与摘要不泄露")
    void resolvesWholeSecretRef() {
        SqliteEnvironmentStore store = store();
        store.saveSecret("p1", "test", "db-password", "s3cr3t-value");
        store.saveEnvVar("test", "DB_PASSWORD", "secret://db-password", true, "TEMPLATE");
        store.saveEnvVar("test", "DB_HOST", "127.0.0.1", false, "TEMPLATE");

        EffectiveEnvironment effective = store.effective("p1", "test");
        assertEquals("s3cr3t-value", effective.values().get("DB_PASSWORD"));
        assertEquals("127.0.0.1", effective.values().get("DB_HOST"));

        var view = effective.vars().stream()
                .filter(v -> v.key().equals("DB_PASSWORD")).findFirst().orElseThrow();
        assertEquals("secret://db-password", view.value());
        assertTrue(view.secret());
        assertEquals("db-password", view.secretRef());

        String digest = effective.digestValues().get("DB_PASSWORD");
        assertEquals("ref:db-password@1", digest);
        assertFalse(digest.contains("s3cr3t-value"));
        assertEquals(1, effective.secretVersions().get("DB_PASSWORD"));
        assertTrue(effective.missingSecrets().isEmpty());
    }

    @Test
    @DisplayName("嵌入 ${secret:name} 占位符解析；轮换后版本变化")
    void resolvesEmbeddedSecretAndRotates() {
        SqliteEnvironmentStore store = store();
        store.saveSecret("p1", "test", "db-password", "v1");
        store.saveEnvVar("test", "DATABASE_URL",
                "jdbc:mysql://db:3306/app?password=${secret:db-password}", true, "TEMPLATE");

        EffectiveEnvironment first = store.effective("p1", "test");
        assertEquals("jdbc:mysql://db:3306/app?password=v1",
                first.values().get("DATABASE_URL"));

        store.rotateSecret("p1", "test", "db-password", "v2");
        EffectiveEnvironment second = store.effective("p1", "test");
        assertEquals("jdbc:mysql://db:3306/app?password=v2",
                second.values().get("DATABASE_URL"));
        assertEquals(2, second.secretVersions().get("DATABASE_URL"));
        assertFalse(second.digestValues().get("DATABASE_URL").contains("v1"));
    }

    @Test
    @DisplayName("引用不存在时进入 missingSecrets，不把占位符原样注入")
    void missingSecretIsFailClosedSignal() {
        SqliteEnvironmentStore store = store();
        store.saveEnvVar("test", "API_TOKEN", "secret://missing", true, "TEMPLATE");
        store.saveEnvVar("test", "URL", "https://x?t=${secret:missing}", true, "TEMPLATE");

        EffectiveEnvironment effective = store.effective("p1", "test");
        assertTrue(effective.missingSecrets().contains("missing"));
        assertEquals("", effective.values().get("API_TOKEN"));
        assertEquals("https://x?t=", effective.values().get("URL"));
    }
}
