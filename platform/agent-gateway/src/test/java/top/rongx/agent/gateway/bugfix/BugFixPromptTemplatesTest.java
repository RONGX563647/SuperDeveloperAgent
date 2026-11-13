package top.rongx.agent.gateway.bugfix;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 提示词模板随包发布、占位符完整，避免运行时才发现模板缺失。 */
class BugFixPromptTemplatesTest {

    private final BugFixPromptTemplates prompts = new BugFixPromptTemplates();

    @Test
    void rendersAllStageTemplates() {
        Map<String, String> values = Map.ofEntries(
                Map.entry("projectId", "p1"), Map.entry("issueNumber", "1"),
                Map.entry("issueTitle", "标题"), Map.entry("issueBody", "描述"),
                Map.entry("baseSha", "abc"), Map.entry("branch", "agent/bugfix-1"),
                Map.entry("analysis", "分析"), Map.entry("testEnv", "test"),
                Map.entry("fixSha", "def"), Map.entry("fixSummary", "修复"),
                Map.entry("smoke", "通过"), Map.entry("evidence", "证据"));

        for (String name : new String[]{"analyze", "fix", "reproduce", "verify", "pull-request"}) {
            String text = prompts.render(name, values);
            assertFalse(text.isBlank(), name + " 模板不应为空");
            assertFalse(text.contains("{{"), name + " 模板仍含未替换占位符");
        }
    }

    @Test
    void missingPlaceholderFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> prompts.render("analyze", Map.of("projectId", "p1")));
    }
}
