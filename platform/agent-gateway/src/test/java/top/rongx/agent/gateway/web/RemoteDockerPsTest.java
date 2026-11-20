package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 远端 docker ps 解析：标签优先、旧容器回退、跨项目隔离。 */
class RemoteDockerPsTest {

    @Test
    @DisplayName("带标签：env/version/sha 直接用标签，状态与端口解析正确")
    void parsesLabeledContainers() {
        String output = "agent-deploy-proj-prod-0.1.0|ci-prod-abc|Up 2 hours|"
                + "0.0.0.0:18080->8080/tcp|prod|0.1.0|abc1234def|2026-11-20 10:00:00 +0800 CST";
        List<RemoteDockerPs.Row> rows = RemoteDockerPs.parse("proj", output);
        assertEquals(1, rows.size());
        RemoteDockerPs.Row row = rows.get(0);
        assertEquals("prod", row.env());
        assertEquals("0.1.0", row.version());
        assertEquals("abc1234def", row.sha());
        assertTrue(row.running());
        assertEquals("0.0.0.0:18080->8080/tcp", row.ports());
    }

    @Test
    @DisplayName("无标签旧容器：从容器名推断环境与版本，版本缺失时回退短 sha")
    void infersLegacyContainerIdentity() {
        String output = "agent-deploy-proj-prod|ci-prod-abc|Exited (1) 2 hours ago||||"
                + "abcdef1234567890|2026-11-20 10:00:00 +0800 CST";
        RemoteDockerPs.Row row = RemoteDockerPs.parse("proj", output).get(0);
        assertEquals("prod", row.env());
        assertEquals("abcdef123456", row.version());
        assertTrue(!row.running());
    }

    @Test
    @DisplayName("容器名带版本：env 后面的整段作为版本号")
    void readsVersionFromName() {
        String output = "agent-deploy-proj-test-0.2.3|ci-test-abc|Up 1 minute||||abc|"
                + "2026-11-20 10:00:00 +0800 CST";
        RemoteDockerPs.Row row = RemoteDockerPs.parse("proj", output).get(0);
        assertEquals("test", row.env());
        assertEquals("0.2.3", row.version());
    }

    @Test
    @DisplayName("跨项目/畸形输出：按前缀边界过滤，忽略空行与不完整行")
    void filtersOtherProjectsAndMalformedLines() {
        String output = "\n"
                + "agent-deploy-proj2-prod|img|Up 1 hour||prod|0.1.0|abc|2026-11-20 10:00:00 +0800 CST\n"
                + "agent-deploy-proj-dev|img|Up 1 hour\n"
                + "random|line\n";
        assertTrue(RemoteDockerPs.parse("proj", output).isEmpty());
    }

    @Test
    @DisplayName("CreatedAt：按远端时区偏移解析，不受本机时区影响")
    void parsesCreatedAtWithOffset() {
        long expected = OffsetDateTime.parse("2026-11-20T10:00:00+08:00")
                .toInstant().toEpochMilli();
        assertEquals(expected, RemoteDockerPs.parseCreatedAt("2026-11-20 10:00:00 +0800 CST"));
        assertEquals(0, RemoteDockerPs.parseCreatedAt(""));
    }
}
