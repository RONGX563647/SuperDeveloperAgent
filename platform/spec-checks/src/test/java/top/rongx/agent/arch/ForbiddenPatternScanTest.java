package top.rongx.agent.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 禁令扫描（项目规约 §5 / §7 / §10）。
 *
 * <p>只扫描 <b>生产代码</b>：{@code src/main/java} 与 {@code src/main/resources}。
 * 测试代码不受这些规则约束（测试里允许 {@code System.out} 等）。
 *
 * <p>豁免：违规行或其上一行写 {@code // spec-ignore: R06 原因}，原因不足 3 字视为无效。
 */
class ForbiddenPatternScanTest {

    /** 规则：编号 + 说明 + 正则 + 作用域（java / xml）。 */
    private record Rule(String id, String message, Pattern pattern, String scope) {}

    private static final List<Rule> RULES = List.of(
            new Rule("R01", "禁止 Runtime.exec：命令必须经执行面",
                    Pattern.compile("Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec"), "java"),
            new Rule("R02", "禁止调用 shell 解释器（sh -c / bash -c / cmd /c / powershell）",
                    Pattern.compile("[\"'](sh|bash|cmd|powershell|pwsh)[\"']\\s*,\\s*[\"'](-c|/c|-Command)[\"']",
                            Pattern.CASE_INSENSITIVE), "java"),
            new Rule("R03", "MyBatis 禁止 ${} 拼接：必须用 #{} 参数绑定",
                    Pattern.compile("\\$\\{"), "xml"),
            new Rule("R04", "禁止 new Thread()：使用受管线程池或虚拟线程执行器",
                    Pattern.compile("new\\s+Thread\\s*\\("), "java"),
            new Rule("R05", "禁止字段注入：@Autowired 不得标注在字段上",
                    Pattern.compile("@Autowired\\s*\\R\\s*(private|protected|public)\\s+"), "java"),
            new Rule("R06", "金额/费用禁止用 double：必须用 BigDecimal",
                    Pattern.compile("\\bdouble\\s+\\w*(amount|price|money|balance|fee|cost)\\w*\\b",
                            Pattern.CASE_INSENSITIVE), "java"),
            new Rule("R07", "日志禁止打印凭证类字段",
                    Pattern.compile("(log|logger|LOGGER)\\s*\\.\\s*\\w+\\s*\\([^)]*"
                            + "(password|passwd|token|secret|credential|accessKey)", Pattern.CASE_INSENSITIVE), "java"),
            new Rule("R08", "禁止 SELECT *：必须显式列出字段",
                    Pattern.compile("\\bSELECT\\s+\\*", Pattern.CASE_INSENSITIVE), "both"),
            new Rule("R09", "禁止 java.util.Date / new Date()：必须用 java.time",
                    Pattern.compile("\\bjava\\.util\\.Date\\b|\\bnew\\s+Date\\s*\\("), "java"),
            new Rule("R10", "禁止空 catch 块（吞异常）",
                    Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}"), "java"),
            new Rule("R11", "禁止 System.out/err：使用结构化日志",
                    Pattern.compile("System\\s*\\.\\s*(out|err)\\s*\\.\\s*print"), "java"),
            new Rule("R12", "禁止动态脚本执行（ScriptEngine / eval）",
                    Pattern.compile("ScriptEngine|\\beval\\s*\\("), "java"),
            new Rule("R13", "软删必须用 deleted_at，禁止 is_deleted 布尔字段",
                    Pattern.compile("\\bis_deleted\\b|\\bisDeleted\\b"), "both")
    );

    private static final Pattern IGNORE = Pattern.compile("spec-ignore:\\s*(R\\d{2})\\s*(.*)");

    @Test
    @DisplayName("生产代码不得命中禁令规则")
    void noForbiddenPatterns() throws IOException {
        Path root = Path.of(System.getProperty("spec.root", ".")).toAbsolutePath().normalize();
        List<String> violations = new ArrayList<>();
        int scanned = 0;

        for (Rule rule : RULES) {
            for (Path file : collect(root, rule.scope())) {
                scanned++;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (!rule.pattern().matcher(lines.get(i)).find()) {
                        continue;
                    }
                    if (isIgnored(rule, lines, i)) {
                        continue;
                    }
                    violations.add(String.format("[%s] %s:%d  %s%n        规约条款见《规约自动化检查》§2；"
                                    + "如为误报请加 `// spec-ignore: %s <原因>` 并登记项目规约 §13",
                            rule.id(), root.relativize(file), i + 1, rule.message(), rule.id()));
                }
            }
        }

        final int scannedFiles = scanned;
        assertTrue(violations.isEmpty(),
                () -> String.format("发现 %d 处规约违规（扫描 %d 个文件实例）：%n%s",
                        violations.size(), scannedFiles, String.join(System.lineSeparator(), violations)));
    }

    private static boolean isIgnored(Rule rule, List<String> lines, int index) {
        for (int i = index; i >= Math.max(0, index - 1); i--) {
            Matcher m = IGNORE.matcher(lines.get(i));
            if (m.find() && m.group(1).equals(rule.id()) && m.group(2).trim().length() > 3) {
                return true;
            }
        }
        return false;
    }

    /** 收集生产代码：java 取 src/main/java，xml 取 src/main/resources。 */
    private static List<Path> collect(Path root, String scope) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/.git/"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/spec-checks/"))
                    // 工作区（data/）存放的是"接入的第三方项目"，不是平台生产代码
                    .filter(p -> !p.toString().replace('\\', '/').matches(".*(\\\\|/)data(\\\\|/).*"))
                    .filter(p -> {
                        String p2 = p.toString().replace('\\', '/');
                        String name = p.getFileName().toString();
                        boolean java = "java".equals(scope) || "both".equals(scope);
                        boolean xml = "xml".equals(scope) || "both".equals(scope);
                        return (java && p2.contains("/src/main/java/") && name.endsWith(".java"))
                                || (xml && p2.contains("/src/main/resources/") && name.endsWith(".xml"));
                    })
                    .forEach(files::add);
        }
        return files;
    }
}
