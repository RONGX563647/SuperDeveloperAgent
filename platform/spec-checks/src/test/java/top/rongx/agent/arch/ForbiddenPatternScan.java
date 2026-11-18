package top.rongx.agent.arch;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 禁令规则（项目规约 §5 / §7 / §10）与扫描实现。
 *
 * <p>规则只有这一份：{@link ForbiddenPatternScanTest} 扫生产代码，
 * {@link RuleFixtureSelfTest} 扫 {@code spec-checks/fixtures} 下的正/负样例，
 * 用来验证"规则本身没写错"（正则失效会让门禁永远绿）。
 *
 * <p>只扫描 <b>生产代码</b>：{@code src/main/java} 与 {@code src/main/resources}。
 * 测试代码不受这些规则约束（测试里允许 {@code System.out} 等）。
 *
 * <p>豁免：违规行或其上一行写 {@code // spec-ignore: R06 原因}，原因不足 3 字视为无效。
 */
final class ForbiddenPatternScan {

    /** 规则：编号 + 说明 + 正则 + 作用域（java / xml / both）。 */
    record Rule(String id, String message, Pattern pattern, String scope) {
    }

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
                    // 整文件匹配：@Autowired 换行后是字段声明（以 ; 或 = 结尾）。
                    // 构造器注入形如 "@Autowired\npublic Foo(...)"，以 "(" 结尾，不命中。
                    Pattern.compile("@Autowired\\s*\\R\\s*(?:private|protected|public)\\s+"
                            + "(?:final\\s+)?[\\w$.<>\\[\\], ]+?\\s+\\w+\\s*[;=]"), "java"),
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
                    Pattern.compile("\\bis_deleted\\b|\\bisDeleted\\b"), "both"),
            new Rule("R14", "错误码必须使用 ErrorCode 枚举，禁止硬编码 E-xxx 字面量",
                    Pattern.compile("\"E-\\d{3}"), "java")
    );

    /** 错误码定义文件本身允许出现字面量，其余生产代码一律走枚举。 */
    private static final String ERROR_CODE_OWNER =
            "agent-domain/src/main/java/top/rongx/agent/domain/error/ErrorCode.java";

    private static final Pattern IGNORE = Pattern.compile("spec-ignore:\\s*(R\\d{2})\\s*(.*)");

    private ForbiddenPatternScan() {
    }

    static List<Rule> rules() {
        return RULES;
    }

    /**
     * 扫描 root 下的生产代码，返回违规清单（空 = 通过）。
     *
     * <p><b>整文件匹配而不是逐行匹配</b>：像"空 catch 块"「{@code @Autowired} 换行后是字段声明」
     * 这类规则天然跨行，逐行匹配会让规则永远不可能命中（fixture 自测就是用来抓这种死规则的）。
     * 命中后再把字符偏移换算回行号，报告格式不变。
     */
    static List<String> violations(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Rule rule : RULES) {
            for (Path file : collect(root, rule.scope())) {
                if (ERROR_CODE_OWNER.equals(root.relativize(file).toString().replace('\\', '/'))) {
                    continue;
                }
                String text = readUtf8(root, file);
                List<String> lines = text.lines().toList();
                Matcher matcher = rule.pattern().matcher(text);
                while (matcher.find()) {
                    int line = lineOf(text, matcher.start());
                    if (isIgnored(rule, lines, line)) {
                        continue;
                    }
                    violations.add(String.format("[%s] %s:%d  %s%n        规约条款见《规约自动化检查》§2；"
                                    + "如为误报请加 `// spec-ignore: %s <原因>` 并登记项目规约 §13",
                            rule.id(), root.relativize(file), line + 1, rule.message(), rule.id()));
                }
            }
        }
        return violations;
    }

    /** 字符偏移 → 0 基行号。 */
    private static int lineOf(String text, int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** 参与扫描的文件数（fixture 自测用来断言样例真的被读到）。 */
    static int scannedFiles(Path root) throws IOException {
        int scanned = 0;
        for (Rule rule : RULES) {
            scanned += collect(root, rule.scope()).size();
        }
        return scanned;
    }

    /** UTF-8 读取；文件含非法字节时报出具体路径（MalformedInputException 默认不带路径，难定位）。 */
    private static String readUtf8(Path root, Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new IOException("文件不是合法 UTF-8（Input length = " + e.getInputLength()
                    + "）：" + root.relativize(file) + "，请转码为 UTF-8 或登记豁免", e);
        }
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
        boolean java = "java".equals(scope) || "both".equals(scope);
        boolean xml = "xml".equals(scope) || "both".equals(scope);
        // 用 walkFileTree 而不是 walk：容器里挂载 node_modules 后全量遍历要十几分钟，
        // 而编译产物/依赖/工作区本来就不该被扫描，直接剪枝。
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root)) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (name.equals("target") || name.equals(".git") || name.equals("node_modules")
                            || name.equals(".agent") || name.equals("dist")
                            // 工作区（data/）存放的是"接入的第三方项目"，不是平台生产代码
                            || (name.equals("data") && root.equals(dir.getParent()))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) {
                String normalized = p.toString().replace('\\', '/');
                // 规约检查自身（含 fixtures 正/负样例）不参与生产扫描：
                // 实现类里就是这些正则字面量，样例本身就是"故意违规"的代码。
                // 用相对 root 的路径判断，这样把 root 指到 fixtures 时样例仍能被扫到。
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("spec-checks/")) {
                    return FileVisitResult.CONTINUE;
                }
                String name = p.getFileName().toString();
                if ((java && normalized.contains("/src/main/java/") && name.endsWith(".java"))
                        || (xml && normalized.contains("/src/main/resources/") && name.endsWith(".xml"))) {
                    files.add(p);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 挂载卷上的权限/符号链接错误不应让规约扫描整体失败：跳过该文件。
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
}
