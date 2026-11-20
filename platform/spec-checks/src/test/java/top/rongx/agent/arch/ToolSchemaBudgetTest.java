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
import java.util.Map;
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

    /** 所有 @Tool 声明（不限带 description 的形态），用于重名检查。
     *  锚定行首：Javadoc 里的 {@code @Tool(...)} 文本引用不能算声明。 */
    private static final Pattern TOOL_NAME = Pattern.compile(
            "(?m)^\\s*@Tool\\(\\s*name\\s*=\\s*\"([A-Za-z0-9_]+)\"");

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

    @Test
    @DisplayName("@Tool name 全局唯一：重名会导致装配顺序漂移与越权口径静默丢失（BUG-28）")
    void toolNamesAreGloballyUnique() throws IOException {
        // 用 List 收集而不是 Set：重名会被 LinkedHashSet 静默折叠，检查就形同虚设
        record Declaration(String name, String file) {
        }
        List<Declaration> all = new ArrayList<>();
        for (Path file : toolSources()) {
            String source = Files.readString(file);
            Matcher matcher = TOOL_NAME.matcher(source);
            while (matcher.find()) {
                all.add(new Declaration(matcher.group(1), file.toString()));
            }
        }
        List<String> names = all.stream().map(Declaration::name).toList();
        Set<String> distinct = new LinkedHashSet<>(names);
        if (distinct.size() != names.size()) {
            Map<String, List<String>> duplicates = new java.util.LinkedHashMap<>();
            for (Declaration d : all) {
                duplicates.computeIfAbsent(d.name(), k -> new ArrayList<>()).add(d.file());
            }
            String detail = duplicates.entrySet().stream()
                    .filter(e -> e.getValue().size() > 1)
                    .map(e -> e.getKey() + " -> " + e.getValue())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("unknown");
            throw new AssertionError(
                    "@Tool name 必须全局唯一（冲突时同名工具谁生效随装配顺序漂移）：\n" + detail);
        }
        assertTrue(distinct.size() == names.size());
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
