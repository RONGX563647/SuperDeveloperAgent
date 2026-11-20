package top.rongx.agent.arch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 沙箱分层守卫：AgentScope 的宿主 shell 工具不得进入工具面。
 *
 * <p>背景（本仓库沙箱的分层契约，2026-11-20 分析定稿）：
 * <ul>
 *   <li><b>AgentScope 层</b>＝「工具准入」：PermissionMode / 规则引擎决定"模型能不能调这个工具"；
 *       {@code ShellCommandTool} 是宿主进程直跑（{@code ProcessBuilder sh -c}），只做命令白名单，
 *       <b>没有任何执行隔离</b>——AgentScope 官方 BOM 里的 sandbox 扩展（kubernetes/agentrun/
 *       daytona/e2b）全是远程沙箱，core 不带本地容器隔离，ReActAgent 的 javadoc 也明说
 *       BYPASS 要"paired with a sandboxed environment"（环境自带）。</li>
 *   <li><b>本仓库层</b>＝「执行隔离」：{@code DockerSandbox}（cap-drop ALL / no-new-privileges /
 *       只读根文件系统 / pids-limit / 网络 / 资源上限）承接 CI 构建里<b>不可信的第三方仓库代码</b>；
 *       模型侧则刻意不暴露 shell——命令只来自 ci/actions.yml 配方（E-201 白名单 / E-202 禁 shell）。</li>
 * </ul>
 *
 * <p>守卫内容：全平台源码里不得出现 {@code io.agentscope.core.tool.coding.ShellCommandTool}
 * 与 {@code io.agentscope.core.tool.file.WriteFileTool} 的注册/引用。它们跑在宿主进程与宿主
 * 文件系统上，一旦注册进任何 Toolkit，模型就获得绕过容器沙箱在宿主机执行任意命令/写任意
 * 路径的通道（白名单挡不住 "mvn" 里 plugin 执行的任意代码）。若未来确实要给模型开放 shell，
 * 正确做法是：新写一个把命令转发进 {@code DockerSandbox.wrap(...)} 的 AgentTool，而不是注册
 * AgentScope 的宿主实现。
 */
class HostToolExclusionTest {

    /** 禁止进入工具面的 AgentScope 宿主实现（fully-qualified 简名匹配）。 */
    private static final List<String> FORBIDDEN_HOST_TOOLS = List.of(
            "ShellCommandTool",
            "UnixCommandValidator",
            "WindowsCommandValidator",
            "CommandValidator");

    /** 扫描范围：平台主代码（测试代码允许出现类型名，用于本守卫自身的断言）。 */
    private static final Path PLATFORM_MAIN = Path.of(
            "..", "agent-tools", "src", "main", "java");

    private static final Path PLATFORM_RUNTIME = Path.of(
            "..", "agent-runtime", "src", "main", "java");

    private static final Path PLATFORM_GATEWAY = Path.of(
            "..", "agent-gateway", "src", "main", "java");

    @Test
    @DisplayName("AgentScope 宿主 shell/文件工具不得被注册进任何工具面（沙箱分层守卫）")
    void hostShellToolsAreNeverRegistered() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : List.of(PLATFORM_MAIN, PLATFORM_RUNTIME, PLATFORM_GATEWAY)) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> check(p, violations));
            }
        }
        if (!violations.isEmpty()) {
            fail("AgentScope 宿主工具出现在工具面/运行层源码里（会绕过容器沙箱）：\n"
                    + String.join("\n", violations)
                    + "\n正确做法：新写转发进 DockerSandbox.wrap(...) 的 AgentTool，"
                    + "不要注册 AgentScope 的宿主实现。");
        }
    }

    private void check(Path file, List<String> violations) {
        try {
            String source = Files.readString(file);
            for (String tool : FORBIDDEN_HOST_TOOLS) {
                // import / new / registerTool 里出现该类型名即违规（注释与 javadoc 除外——
                // 本测试源码豁免自己：文件名以 HostToolExclusion 开头的不扫）。
                if (file.getFileName().toString().startsWith("HostToolExclusion")) {
                    return;
                }
                Pattern usage = Pattern.compile(
                        "(import\\s+[^;]*\\." + tool + "\\s*;|new\\s+" + tool + "\\b|"
                                + "registerTool\\([^)]*\\b" + tool + "\\b)");
                if (usage.matcher(source).find()) {
                    violations.add(file + " → " + tool);
                }
            }
        } catch (IOException ignored) {
            // 读不了就跳过（编码问题由其它守卫负责）
        }
        assertTrue(true);
    }
}
