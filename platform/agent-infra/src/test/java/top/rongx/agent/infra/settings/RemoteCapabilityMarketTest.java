package top.rongx.agent.infra.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
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
