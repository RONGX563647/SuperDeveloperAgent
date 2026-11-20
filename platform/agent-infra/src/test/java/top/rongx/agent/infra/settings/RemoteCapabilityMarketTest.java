package top.rongx.agent.infra.settings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.settings.CapabilityMarket;

/** 外部市场解析：不联网，只验证 Registry / SKILL.md 到安装模板的映射。 */
class RemoteCapabilityMarketTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode node(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    @DisplayName("npm 包 → npx stdio，必填环境变量进入提示")
    void mapsNpmPackageToStdio() throws Exception {
        JsonNode wrapper = node("""
                {
                  "server": {
                    "name": "com.acme/files",
                    "title": "Files",
                    "description": "read files",
                    "version": "1.2.3",
                    "packages": [{
                      "registryType": "npm",
                      "identifier": "acme-files-mcp",
                      "version": "1.2.3",
                      "runtimeArguments": [{"type": "positional", "value": "-y"}],
                      "transport": {"type": "stdio"},
                      "environmentVariables": [{"name": "ACME_ROOT", "isRequired": true}]
                    }]
                  },
                  "_meta": {
                    "io.modelcontextprotocol.registry/publisher-provided": {"tags": ["files", "io"]}
                  }
                }
                """);
        CapabilityMarket.Entry entry = RemoteCapabilityMarket
                .parseServer(wrapper.path("server"), wrapper).orElseThrow();
        assertEquals(CapabilityMarket.Kind.MCP, entry.kind());
        assertEquals("com.acme-files", entry.name());
        assertEquals("stdio", entry.transport());
        assertEquals("npx", entry.command());
        assertEquals(List.of("-y", "acme-files-mcp@1.2.3"), entry.args());
        assertEquals(List.of("ACME_ROOT"), entry.requiredEnv());
        assertEquals(List.of("files", "io"), entry.tags());
    }

    @Test
    @DisplayName("cacerts 不认的证书可由系统根证书库兜底，全部不认时才失败")
    void compositeTrustManagerAcceptsWhenAnyStoreTrusts() {
        RemoteCapabilityMarket.CompositeTrustManager merged =
                new RemoteCapabilityMarket.CompositeTrustManager(
                        List.of(fakeTrustManager(false), fakeTrustManager(true)));
        assertDoesNotThrow(() -> merged.checkServerTrusted(new X509Certificate[0], "RSA"));

        RemoteCapabilityMarket.CompositeTrustManager strict =
                new RemoteCapabilityMarket.CompositeTrustManager(
                        List.of(fakeTrustManager(false)));
        assertThrows(CertificateException.class,
                () -> strict.checkServerTrusted(new X509Certificate[0], "RSA"));
    }

    private static X509TrustManager fakeTrustManager(boolean trust) {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                check(trust);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                check(trust);
            }

            private void check(boolean trusted) throws CertificateException {
                if (!trusted) {
                    throw new CertificateException("untrusted");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    @Test
    @DisplayName("无 stdio 包时退回 http 远端；URL 含占位符则视为不可安装")
    void fallsBackToRemoteWithNoPlaceholder() throws Exception {
        JsonNode wrapper = node("""
                {"server": {"name": "com.acme/remote", "description": "remote",
                  "remotes": [{"type": "streamable-http", "url": "https://mcp.acme.com/mcp"}]}}
                """);
        CapabilityMarket.Entry entry = RemoteCapabilityMarket
                .parseServer(wrapper.path("server"), wrapper).orElseThrow();
        assertEquals("http", entry.transport());
        assertEquals("https://mcp.acme.com/mcp", entry.url());

        JsonNode templated = node("""
                {"server": {"name": "com.acme/tpl", "description": "tpl",
                  "remotes": [{"type": "streamable-http", "url": "https://{region}.acme.com/mcp"}]}}
                """);
        assertTrue(RemoteCapabilityMarket.parseServer(templated.path("server"), templated).isEmpty());
    }

    @Test
    @DisplayName("多源接入：多个 Registry + 多个 Skill 仓库都进入来源清单")
    void sourcesReflectMultiRepoConfig() {
        RemoteCapabilityMarket market = new RemoteCapabilityMarket(
                JSON,
                "https://registry.modelcontextprotocol.io/v0/servers, https://mcp.example.com/v0/servers/",
                "anthropics/skills, anbeime/skill");

        List<CapabilityMarket.Source> mcp = market.sources(CapabilityMarket.Kind.MCP);
        // 1 个精选 + 2 个 Registry；host 作为稳定来源 id，尾斜杠被去掉
        assertEquals(3, mcp.size());
        assertTrue(mcp.stream().anyMatch(s -> s.id().equals("Awesome-MCP-ZH") && s.builtin()));
        assertTrue(mcp.stream().anyMatch(s -> s.id().equals("registry.modelcontextprotocol.io")));
        assertTrue(mcp.stream().anyMatch(s -> s.id().equals("mcp.example.com")));

        List<CapabilityMarket.Source> skills = market.sources(CapabilityMarket.Kind.SKILL);
        assertEquals(2, skills.size());
        assertTrue(skills.stream().anyMatch(s -> s.id().equals("anthropics/skills")));
        assertTrue(skills.stream().anyMatch(s -> s.id().equals("anbeime/skill")));
    }

    @Test
    @DisplayName("多 Registry：解析出的条目携带来源 host，便于按来源筛选")
    void parseServerCarriesSource() throws Exception {
        JsonNode wrapper = node("""
                {"server": {"name": "com.acme/files", "description": "files",
                  "packages": [{"registryType": "npm", "identifier": "acme-mcp",
                    "transport": {"type": "stdio"}}]}}
                """);
        CapabilityMarket.Entry entry = RemoteCapabilityMarket
                .parseServer(wrapper.path("server"), wrapper, "mcp.example.com").orElseThrow();
        assertEquals("mcp.example.com", entry.source());
        // 兼容旧调用：不传来源时退回默认标记
        CapabilityMarket.Entry legacy = RemoteCapabilityMarket
                .parseServer(wrapper.path("server"), wrapper).orElseThrow();
        assertEquals("MCP Registry", legacy.source());
    }

    @Test
    @DisplayName("SKILL.md → 目录名 / 标题 / 描述 / 原文")
    void mapsSkillMarkdown() {
        String content = """
                ---
                name: Frontend Design
                description: Distinctive visual design guidance
                ---

                # Frontend Design
                """;
        Optional<CapabilityMarket.Entry> entry = RemoteCapabilityMarket.toSkill(
                "anthropics/skills", "skills/frontend-design/SKILL.md", content);
        CapabilityMarket.Entry skill = entry.orElseThrow();
        assertEquals(CapabilityMarket.Kind.SKILL, skill.kind());
        assertEquals("frontend-design", skill.name());
        assertEquals("Frontend Design", skill.title());
        assertEquals("Distinctive visual design guidance", skill.description());
        assertEquals(content, skill.content());
        assertEquals("skill:anthropics/skills:skills/frontend-design/SKILL.md", skill.id());
    }
}
