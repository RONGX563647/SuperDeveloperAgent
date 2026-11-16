package top.rongx.agent.arch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 错误码枚举自身的完整性：格式统一、码值不重复。
 *
 * <p>直接扫描源码而不是加载 {@code ErrorCode} 类：spec-checks 允许单独执行
 * （{@code mvn -pl spec-checks test}），此时顶部模块的 SNAPSHOT 可能来自本地仓库的旧包，
 * 运行期加载会报 {@code NoClassDefFoundError}。规约检查只依赖仓库文件，更稳定。
 */
class ErrorCodeUniquenessTest {

    /** 枚举常量形如 {@code COMPILE_FAILED("E-101")}。 */
    private static final Pattern ENTRY =
            Pattern.compile("(?m)^\\s*([A-Z][A-Z0-9_]*)\\s*\\(\\s*\"([^\"]*)\"");

    private static final Path SOURCE = Path.of(
            System.getProperty("spec.root", "."),
            "agent-domain", "src", "main", "java", "top", "rongx", "agent",
            "domain", "error", "ErrorCode.java");

    @Test
    void codesAreUniqueAndWellFormed() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        Matcher matcher = ENTRY.matcher(source);
        Set<String> seen = new HashSet<>();
        int count = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            String code = matcher.group(2);
            count++;
            assertTrue(code.matches("E-\\d{3}"), "错误码格式不合法：" + name + " -> " + code);
            assertTrue(seen.add(code), "错误码重复：" + code + "（" + name + "）");
        }
        assertTrue(count > 0, "没有在 " + SOURCE + " 中解析到任何错误码");
    }
}
