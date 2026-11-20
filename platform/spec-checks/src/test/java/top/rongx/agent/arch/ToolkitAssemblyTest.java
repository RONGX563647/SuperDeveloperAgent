package top.rongx.agent.arch;

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

/**
 * 工具面装配断言（BUG-30）。
 *
 * <p>约束：{@code agentToolkit}（非生产完整面）的工具集合必须是 {@code readOnlyToolkit}
 * （生产只读面）的超集——「只读面有的工具主面必须有」，否则对话面会出现
 * 「生产能问、非生产反而不能问」的能力倒挂（ask_user 曾经就是这样漏掉的）。
 *
 * <p>检查方式：解析 {@code ToolCatalog.java} 源码，收集 {@code agentToolkit} 方法体与
 * {@code registerReadOnlyTools} 方法体里的 {@code toolkit.registerTool(...)} 参数类名，
 * 断言只读面注册的每个类也出现在主面。这是装配层（源码级）的静态断言：
 * 与 ArchUnit 的字节码扫描互补，能在不启动 Spring 上下文的情况下拦住漏注册。
 */
class ToolkitAssemblyTest {

    private static final Pattern REGISTER_CALL = Pattern.compile(
            "toolkit\\.registerTool\\(\\s*(\\w+)\\s*\\)");

    @Test
    @DisplayName("agentToolkit 工具名集合 ⊇ readOnlyToolkit（BUG-30 装配断言）")
    void agentToolkitCoversReadOnlyToolkit() throws IOException {
        String source = toolCatalogSource();

        String readOnlyBlock = methodBody(source, "registerReadOnlyTools");
        String agentBlock = methodBody(source, "agentToolkit");
        String supervisorBlock = methodBody(source, "supervisorToolkit")
                + methodBody(source, "registerReadOnlyTools");
        String deployBlock = methodBody(source, "deployToolkit")
                + methodBody(source, "registerReadOnlyTools");

        Set<String> readOnly = registered(readOnlyBlock);
        Set<String> agent = registered(agentBlock);
        Set<String> supervisor = registered(supervisorBlock);
        Set<String> deploy = registered(deployBlock);

        // BUG-30 的直接触发点：ask_user 在只读面注册，主面却漏掉
        assertTrue(agent.containsAll(readOnly),
                "agentToolkit 缺少 readOnlyToolkit 注册的工具（参数类名）："
                        + difference(readOnly, agent));

        // 调度面与部署面同样复用只读面（历史设计约束），一并锁住
        assertTrue(supervisor.containsAll(readOnly),
                "supervisorToolkit 缺少 readOnlyToolkit 注册的工具（参数类名）："
                        + difference(readOnly, supervisor));
        assertTrue(deploy.containsAll(readOnly),
                "deployToolkit 缺少 readOnlyToolkit 注册的工具（参数类名）："
                        + difference(readOnly, deploy));

        // agentToolkit 必须显式注册 AskUserTools（选项卡片拦截锚点 ask_user）
        assertTrue(agent.contains("askUserTools"),
                "agentToolkit 必须注册 AskUserTools（ask_user）：AskUserMiddleware "
                        + "以 ask_user 为拦截锚点，主面没有它非生产会话出不了选项卡");
    }

    /** 其它工具面（supervisorToolkit / deployToolkit）也一样从同一套参数表取值。 */
    private static String toolCatalogSource() throws IOException {
        Path root = Path.of(System.getProperty("spec.root", ".."), "agent-tools",
                "src", "main", "java");
        Path catalog = root.resolve("top/rongx/agent/tools/ToolCatalog.java");
        if (!Files.exists(catalog)) {
            throw new AssertionError("找不到 ToolCatalog.java：" + catalog);
        }
        return Files.readString(catalog);
    }

    /** 截取方法体（大括号配平），跨方法边界不误吞。 */
    private static String methodBody(String source, String methodName) {
        int decl = source.indexOf(methodName + "(");
        if (decl < 0) {
            throw new AssertionError("找不到方法：" + methodName);
        }
        int open = source.indexOf('{', decl);
        if (open < 0) {
            throw new AssertionError("方法没有方法体：" + methodName);
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("方法体没有闭合：" + methodName);
    }

    private static Set<String> registered(String block) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = REGISTER_CALL.matcher(block);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static List<String> difference(Set<String> expected, Set<String> actual) {
        List<String> missing = new ArrayList<>(expected);
        missing.removeAll(actual);
        return missing;
    }
}
