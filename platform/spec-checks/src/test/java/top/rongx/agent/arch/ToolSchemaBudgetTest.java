package top.rongx.agent.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.tool.ToolPluginIndex;
import top.rongx.agent.domain.tool.ToolPlugins;

/**
 * 工具 schema 预算：description 只留「做什么」的一句话英文，行为约束在系统提示里。
 *
 * <p>工具 schema 每轮都常驻 prompt 最前面，中文长描述是最大的隐性 token 开销；
 * 这里把上限固化成测试，避免后续新增工具又把描述写回长篇流程说明。
 */
class ToolSchemaBudgetTest {

    /** 单个工具 description 的字符上限（英文）。 */
    private static final int MAX_DESCRIPTION_CHARS = 40;
    /** 全部工具 description 的总字符上限：超过说明又有人塞流程说明进来了。 */
    private static final int MAX_TOTAL_CHARS = 4_000;

    private static final Pattern TOOL_DESCRIPTION = Pattern.compile(
            "@Tool\\(name\\s*=\\s*\"([A-Za-z0-9_]+)\",\\s*description\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    @DisplayName("每个 @Tool description 是 ≤40 字符的英文一句话")
    void descriptionsAreShortEnglish() throws IOException {
        List<String> violations = new ArrayList<>();
        int total = 0;
        int count = 0;
        for (Path file : toolSources()) {
            String source = Files.readString(file);
            Matcher matcher = TOOL_DESCRIPTION.matcher(source);
            while (matcher.find()) {
                String name = matcher.group(1);
                String description = matcher.group(2);
                count++;
                total += description.length();
                if (description.length() > MAX_DESCRIPTION_CHARS) {
                    violations.add(name + " (" + description.length() + "): " + description);
                }
                if (!isAscii(description)) {
                    violations.add(name + " 含非 ASCII 字符: " + description);
                }
            }
        }
        assertTrue(count >= 70, "工具数量异常，实际匹配到 " + count + " 个 @Tool description");
        assertTrue(violations.isEmpty(), "description 超长/非英文：" + violations);
        assertTrue(total <= MAX_TOTAL_CHARS,
                "工具 description 总字符 " + total + " 超过预算 " + MAX_TOTAL_CHARS);
    }

    @Test
    @DisplayName("核心工具只有发现类 8 个，会话/任务/工作区工具默认不可见")
    void coreIsDiscoveryOnly() {
        assertEquals(8, ToolPlugins.CORE.size());
        for (String name : ToolPlugins.CORE) {
            assertFalse(name.startsWith("conversation_"), name + " 不应常驻");
            assertFalse(name.startsWith("workspace_"), name + " 不应常驻");
            assertFalse(name.startsWith("task_"), name + " 不应常驻");
        }

        ToolPluginIndex index = ToolPluginIndex.of(ToolPlugins.standard(), allToolNames(),
                ToolPlugins.CORE);
        assertEquals(ToolPlugins.CORE, index.alwaysVisible());
        assertTrue(index.visible("project_info", Set.of()));
        assertTrue(index.visible("tool_load", Set.of()));
        assertFalse(index.visible("conversation_list", Set.of()));
        assertFalse(index.visible("ci_run", Set.of()));
        assertTrue(index.visible("conversation_list", Set.of("dispatch")));
        assertTrue(index.visible("ci_run", Set.of("ci")));
    }

    /** 插件目录里声明的全部工具名 + 核心工具，用来构造「真实工具面」。 */
    private static Set<String> allToolNames() {
        Set<String> names = new LinkedHashSet<>(ToolPlugins.CORE);
        ToolPlugins.standard().forEach(plugin -> names.addAll(plugin.tools()));
        return names;
    }

    private static List<Path> toolSources() throws IOException {
        Path root = Path.of(System.getProperty("spec.root", ".."), "agent-tools", "src", "main", "java");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static boolean isAscii(String text) {
        return text.chars().allMatch(c -> c > 0 && c < 128);
    }
}
