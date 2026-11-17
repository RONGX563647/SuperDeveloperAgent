package top.rongx.agent.domain.process;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import top.rongx.agent.domain.error.PlatformException;

/** 命令解释器前缀：OS 自适应、可覆盖、配置错误不影响启动。 */
class CommandShellTest {

    @Test
    @DisplayName("默认前缀按操作系统推断，且不含任何机器绑定路径")
    void defaultPrefixMatchesOs() {
        List<String> prefix = CommandShell.defaultPrefix();
        assertEquals(2, prefix.size());
        if (CommandShell.isWindows()) {
            assertEquals(List.of("cmd.exe", "/c"), prefix);
        } else {
            assertEquals(List.of("/bin/sh", "-c"), prefix);
        }
        prefix.forEach(token -> assertFalse(token.contains("F:/"),
                "解释器路径不应绑定具体安装目录：" + token));
    }

    @Test
    @DisplayName("配置留空时回退到 OS 自适应默认值")
    void blankConfigFallsBackToDefault() {
        assertEquals(CommandShell.defaultPrefix(), CommandShell.prefix(null));
        assertEquals(CommandShell.defaultPrefix(), CommandShell.prefix("   "));
    }

    @Test
    @DisplayName("可显式覆盖为其它解释器")
    void configuredPrefixIsParsed() {
        assertEquals(List.of("/bin/bash", "-c"), CommandShell.prefix("  /bin/bash -c  "));
        assertEquals(List.of("pwsh", "-Command"), CommandShell.prefix("pwsh -Command"));
    }

    @Test
    @DisplayName("带空格的 Windows 路径用引号包成一个 token")
    void quotedPathStaysOneToken() {
        List<String> prefix = CommandShell.prefix("\"C:/Program Files/Git/bin/bash.exe\" -c");
        assertEquals(List.of("C:/Program Files/Git/bin/bash.exe", "-c"), prefix);
    }

    @Test
    @DisplayName("引号未闭合 / token 过多都给出可照做的错误，而不是启动期崩溃")
    void invalidConfigIsExplicit() {
        PlatformException unclosed = assertThrows(PlatformException.class,
                () -> CommandShell.prefix("\"/bin/bash -c"));
        assertTrue(unclosed.getMessage().contains("未闭合"));

        PlatformException tooMany = assertThrows(PlatformException.class,
                () -> CommandShell.prefix("a b c d e"));
        assertTrue(tooMany.getMessage().contains("配置非法"));
    }

    @Test
    @DisplayName("业务命令只作为最后一个整体参数，不被拆词")
    void commandIsSingleTrailingArgument() {
        String command = "mvn -q test && npm run build";
        List<String> argv = CommandShell.argv(List.of("/bin/sh", "-c"), command);
        assertEquals(3, argv.size());
        assertEquals(command, argv.get(2));
    }
}
