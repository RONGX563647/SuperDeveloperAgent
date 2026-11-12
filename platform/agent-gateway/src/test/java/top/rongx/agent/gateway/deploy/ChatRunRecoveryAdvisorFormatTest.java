package top.rongx.agent.gateway.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 修复日志的格式化规则：结论不能被截掉，工具结果压成一行。 */
class ChatRunRecoveryAdvisorFormatTest {

    @Test
    @DisplayName("步骤超长时保留开头与最终结论")
    void limitKeepsFinalConclusion() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            lines.add("步骤 " + i);
        }
        lines.add("结论：E-401 是 workdir 配置错误，需要修改 ci/actions.yml");

        List<String> out = ChatRunRecoveryAdvisor.limit(lines);

        assertTrue(out.size() <= 120, "输出行数应受控");
        assertTrue(out.get(out.size() - 1).contains("结论"), "最后一行必须是最终结论");
        assertEquals("步骤 0", out.get(0), "应保留开头步骤");
        assertTrue(out.stream().anyMatch(l -> l.contains("中间省略")), "应有省略提示");
    }

    @Test
    @DisplayName("工具结果折叠为单行并截断")
    void oneLineCollapsesWhitespace() {
        String result = ChatRunRecoveryAdvisor.oneLine("第一行\n\n  第二行\t第三行", 200);
        assertEquals("第一行 第二行 第三行", result);

        String longText = "x".repeat(300);
        String cut = ChatRunRecoveryAdvisor.oneLine(longText, 240);
        assertEquals(241, cut.length(), "应截断并追加省略号");
        assertTrue(cut.endsWith("…"));
    }
}
