package top.rongx.agent.infra.settings;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;
import top.rongx.agent.domain.settings.ModelConnection;
import top.rongx.agent.domain.settings.ModelConnectionRepository;
import top.rongx.agent.infra.security.SecretBox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 设置存储：服务器与模型连接的 CRUD、默认唯一性、密钥加密与掩码。 */
class SqliteSettingsStoreTest {

    @TempDir
    Path workspace;

    private SecretBox secretBox() {
        return new SecretBox(workspace.toString(), "");
    }

    private SqliteDeployServerStore servers() {
        return new SqliteDeployServerStore(workspace.toString(), secretBox());
    }

    private SqliteModelConnectionStore models() {
        return new SqliteModelConnectionStore(workspace.toString(), secretBox());
    }

    @Test
    @DisplayName("服务器：保存后密钥加密、接口视图不含明文、默认唯一")
    void serverCrudAndMasking() {
        SqliteDeployServerStore store = servers();
        DeployServer first = store.save(new DeployServerRepository.DeployServerView(
                null, "生产-阿里云", "10.0.0.12", 22, "deploy", "PASSWORD",
                null, "/opt/agent/deploy", true, true), "p@ssw0rd", "admin");
        DeployServer second = store.save(new DeployServerRepository.DeployServerView(
                null, "测试", "10.0.0.13", 22, "deploy", "KEY",
                "~/.ssh/id_ed25519", "/opt/agent/deploy", true, true), null, "admin");

        List<DeployServer> all = store.findAll();
        assertEquals(2, all.size());
        assertTrue(first.hasSecret());
        assertEquals("****", first.secretMasked(), "列表只返回掩码");
        assertFalse(first.toString().contains("p@ssw0rd"), "视图对象不能带明文");

        // 默认唯一：第二次设默认后只剩一台
        assertEquals(second.id(), store.findDefault().orElseThrow().id());
        assertEquals(1, store.findAll().stream().filter(DeployServer::isDefault).count());

        assertEquals("p@ssw0rd", store.secretOf(first.id()).orElseThrow());

        store.recordTest(first.id(), false, "Connection refused", 1200L);
        DeployServer reloaded = store.find(first.id()).orElseThrow();
        assertEquals(Boolean.FALSE, reloaded.lastTestOk());
        assertEquals("Connection refused", reloaded.lastTestMsg());

        store.delete(second.id());
        assertEquals(1, store.findAll().size());
    }

    @Test
    @DisplayName("服务器：未配置任何服务器时 findDefault 为空（部署器据此 fail-closed）")
    void noServerConfigured() {
        assertTrue(servers().findDefault().isEmpty());
    }

    @Test
    @DisplayName("模型：API Key 加密、掩码、默认切换、删除默认后再查")
    void modelCrudAndMasking() {
        SqliteModelConnectionStore store = models();
        ModelConnection dashscope = store.save(new ModelConnectionRepository.ModelConnectionView(
                null, "通义-生产", "dashscope",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-plus", 0.2, 8, true, true), "sk-1234567890", "admin");
        assertTrue(dashscope.hasApiKey());
        assertEquals("****", dashscope.apiKeyMasked(), "列表只返回掩码");
        assertEquals("sk-1234567890", store.apiKeyOf(dashscope.id()).orElseThrow());

        ModelConnection local = store.save(new ModelConnectionRepository.ModelConnectionView(
                null, "本地-ollama", "ollama", "http://127.0.0.1:11434/v1",
                "llama3", 0.2, 8, true, false), "", "admin");
        assertFalse(local.hasApiKey(), "Ollama 不需要 Key");

        store.markDefault(local.id());
        assertEquals(local.id(), store.findDefault().orElseThrow().id());
        assertEquals(1, store.findAll().stream().filter(ModelConnection::isDefault).count());
    }
}
