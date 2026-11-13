package top.rongx.agent.infra.settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.settings.GitHostingAccount;
import top.rongx.agent.domain.settings.GitHostingAccountRepository;
import top.rongx.agent.domain.settings.McpServer;
import top.rongx.agent.domain.settings.McpServerRepository;
import top.rongx.agent.infra.security.SecretBox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 新增接入面：托管账号加密与 URL 匹配、MCP 配置、Skill 目录约定。 */
class GitHostingAndSkillStoreTest {

    @TempDir
    Path workspace;

    private SecretBox secretBox() {
        return new SecretBox(workspace.toString(), "");
    }

    @Test
    @DisplayName("GitHub/GitLab：token 加密、列表只回掩码、按 URL host 自动匹配")
    void gitHostAccounts() {
        SqliteGitHostingAccountStore store =
                new SqliteGitHostingAccountStore(workspace.toString(), secretBox());
        GitHostingAccount account = store.save(
                new GitHostingAccountRepository.GitHostingAccountView(
                        null, "GITHUB", "github.com", null), "ghp_secret_token", "admin");

        assertEquals("x-access-token", account.username(), "GitHub 默认用户名");
        assertTrue(account.tokenMasked().startsWith("ghp_se"));
        assertFalse(account.tokenMasked().contains("secret_token"), "只回掩码");
        assertEquals("ghp_secret_token", store.tokenOf(account.id(), "admin").orElseThrow());

        var credential = store.credentialFor("admin",
                "https://github.com/acme/demo.git").orElseThrow();
        assertEquals("x-access-token", credential.username());
        assertEquals("ghp_secret_token", credential.token());
        assertTrue(store.credentialFor("admin", "https://gitlab.com/acme/demo.git").isEmpty());

        // 同 provider + host 再保存 = 覆盖，不新增
        GitHostingAccount updated = store.save(
                new GitHostingAccountRepository.GitHostingAccountView(
                        null, "GITHUB", "github.com", "octocat"), null, "admin");
        assertEquals(account.id(), updated.id());
        assertEquals("octocat", updated.username());
        assertEquals("ghp_secret_token", store.tokenOf(updated.id(), "admin").orElseThrow(),
                "token 留空 = 保持原值");
        assertEquals(1, store.findAll("admin").size());
    }

    @Test
    @DisplayName("MCP：stdio/http 保存、headers 加密且只回字段名")
    void mcpServers() {
        SqliteMcpServerStore store = new SqliteMcpServerStore(workspace.toString(), secretBox());
        McpServer stdio = store.save(new McpServerRepository.McpServerView(
                null, "playwright", "stdio", "npx",
                List.of("-y", "@playwright/mcp@latest"), null, true), Map.of(), "admin");
        assertEquals(List.of("-y", "@playwright/mcp@latest"), stdio.args());

        McpServer http = store.save(new McpServerRepository.McpServerView(
                null, "notion", "http", null, List.of(),
                "https://mcp.example.com/mcp", true),
                Map.of("Authorization", "Bearer secret-value"), "admin");
        assertEquals(List.of("Authorization"), http.headerNames(), "只回 header 名");
        assertEquals("Bearer secret-value", store.headersOf(http.id()).get("Authorization"));

        // 不传 headers = 保留原值
        store.save(new McpServerRepository.McpServerView(
                http.id(), "notion", "http", null, List.of(),
                "https://mcp.example.com/v2", false), Map.of(), "admin");
        assertEquals("Bearer secret-value", store.headersOf(http.id()).get("Authorization"));

        assertThrows(IllegalArgumentException.class, () -> store.save(
                new McpServerRepository.McpServerView(
                        null, "Bad Name", "stdio", "npx", List.of(), null, true),
                Map.of(), "admin"), "名称不允许空格");
    }

    @Test
    @DisplayName("Skill：SKILL.md frontmatter 发现、自动补全、目录导入")
    void skills() throws Exception {
        SkillsStore store = new SkillsStore(workspace.toString());
        var created = store.create("java-review", "Java 代码评审", "# 步骤\n先读 diff");
        assertEquals("java-review", created.name());
        assertEquals("Java 代码评审", created.description());
        assertTrue(store.content("java-review").orElseThrow().contains("name: java-review"));

        var list = store.list();
        assertEquals(1, list.size());
        assertEquals(1, list.get(0).fileCount(), "新建技能只有 SKILL.md");

        Path source = workspace.resolve("external").resolve("db-migration");
        Files.createDirectories(source);
        Files.writeString(source.resolve("SKILL.md"),
                "---\nname: db-migration\ndescription: 迁移脚本检查\n---\n正文\n");
        var imported = store.importFrom(source.toString());
        assertEquals("db-migration", imported.name());
        assertEquals(2, store.list().size());

        assertThrows(IllegalArgumentException.class,
                () -> store.importFrom(source.toString()), "同名技能不可重复导入");
        assertThrows(IllegalArgumentException.class,
                () -> store.create("../escape", "", ""), "目录名禁止路径穿越");
    }
}
