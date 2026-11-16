package top.rongx.agent.arch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API 文档契约：每个 HTTP 映射方法必须有 Javadoc 或 {@code @Operation(summary=...)}。
 *
 * <p>存量接口很多没有注解，直接全量卡死会让构建长期红着。这里用"基线棘轮"：
 * {@code api-doc-baseline.txt} 记录已知缺口；
 * <ul>
 *   <li>新增接口没有文档 → 失败（不允许继续恶化）；</li>
 *   <li>基线里的接口补上了文档 → 失败（必须删除基线行，防止永久豁免）。</li>
 * </ul>
 *
 * <p>补文档后执行：{@code mvn -pl spec-checks -Dtest=ApiDocContractTest -Dapi.doc.baseline.update=true test}
 * 重新生成基线并提交。
 */
class ApiDocContractTest {

    private static final Pattern MAPPING =
            Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\b");
    private static final Pattern METHOD_DECL =
            Pattern.compile("\\b(?:public|protected|private)\\b[^;=]*?\\b(\\w+)\\s*\\(");
    private static final Pattern OPERATION_SUMMARY =
            Pattern.compile("@Operation\\s*\\([^)]*summary\\s*=\\s*\"([^\"]+)\"");

    @Test
    @DisplayName("每个 Mapping 方法都有 Javadoc 或 @Operation(summary)")
    void everyMappingIsDocumented() throws IOException {
        Path root = Path.of(System.getProperty("spec.root", "..")).toAbsolutePath().normalize();
        Path gateway = root.resolve("agent-gateway/src/main/java");
        Path baseline = Path.of("api-doc-baseline.txt").toAbsolutePath().normalize();

        Set<String> gaps = scan(gateway);
        if (Boolean.getBoolean("api.doc.baseline.update")) {
            writeBaseline(baseline, gaps);
            return;
        }

        Set<String> known = readBaseline(baseline);
        Set<String> fresh = new LinkedHashSet<>(gaps);
        fresh.removeAll(known);
        Set<String> stale = new LinkedHashSet<>(known);
        stale.removeAll(gaps);

        List<String> problems = new ArrayList<>();
        if (!fresh.isEmpty()) {
            problems.add("新增未文档化接口（补 Javadoc 或 @Operation(summary)）：\n  "
                    + String.join("\n  ", fresh));
        }
        if (!stale.isEmpty()) {
            problems.add("基线已过期（这些接口已补文档，请从 " + baseline.getFileName() + " 删除）：\n  "
                    + String.join("\n  ", stale));
        }
        assertTrue(problems.isEmpty(), String.join("\n\n", problems)
                + "\n\n重新生成基线：mvn -pl spec-checks -Dtest=ApiDocContractTest "
                + "-Dapi.doc.baseline.update=true test");
    }

    private Set<String> scan(Path gateway) throws IOException {
        Set<String> gaps = new LinkedHashSet<>();
        if (!Files.isDirectory(gateway)) {
            return gaps;
        }
        try (Stream<Path> files = Files.walk(gateway)) {
            files.filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                    .forEach(file -> scanFile(file, gaps));
        }
        return gaps;
    }

    private void scanFile(Path file, Set<String> gaps) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new IllegalStateException("读取失败：" + file, e);
        }
        String className = file.getFileName().toString().replace(".java", "");
        for (int i = 0; i < lines.size(); i++) {
            if (!MAPPING.matcher(lines.get(i)).find()) {
                continue;
            }
            int annotationEnd = annotationEnd(lines, i);
            int methodLine = nextCodeLine(lines, annotationEnd + 1);
            if (methodLine < 0) {
                continue;
            }
            String text = lines.get(methodLine);
            Matcher decl = METHOD_DECL.matcher(text);
            if (!decl.find()) {
                continue;
            }
            String method = decl.group(1);
            if (!documented(lines, i, annotationEnd)) {
                gaps.add(className + "#" + method);
            }
        }
    }

    /** 多行注解的结束行（括号配平）。 */
    private int annotationEnd(List<String> lines, int start) {
        int depth = 0;
        for (int i = start; i < lines.size(); i++) {
            depth += count(lines.get(i), '(') - count(lines.get(i), ')');
            if (depth <= 0) {
                return i;
            }
        }
        return start;
    }

    /** 跳过注解与空行，返回第一个像方法声明的行。 */
    private int nextCodeLine(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("@") || line.startsWith("//")) {
                continue;
            }
            if (line.startsWith("private") && line.endsWith(";")) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private boolean documented(List<String> lines, int mappingLine, int annotationEnd) {
        // 1) @Operation(summary = "...")：允许在 mapping 前后任意注解行
        int annotationStart = annotationBlockStart(lines, mappingLine);
        StringBuilder annotations = new StringBuilder();
        for (int i = annotationStart; i <= annotationEnd; i++) {
            annotations.append(lines.get(i)).append(' ');
        }
        Matcher summary = OPERATION_SUMMARY.matcher(annotations);
        if (summary.find() && !summary.group(1).isBlank()) {
            return true;
        }
        if (annotations.indexOf("@Operation") >= 0) {
            // 有 @Operation 但没写 summary：仍视为未文档化
            return false;
        }
        // 2) 紧邻的 Javadoc
        int i = annotationStart - 1;
        while (i >= 0 && lines.get(i).isBlank()) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        String line = lines.get(i).trim();
        if (!line.endsWith("*/")) {
            return false;
        }
        while (i >= 0) {
            String current = lines.get(i).trim();
            if (current.startsWith("/**")) {
                return true;
            }
            if (current.startsWith("*/") || current.startsWith("*")) {
                i--;
                continue;
            }
            return false;
        }
        return false;
    }

    /**
     * 注解块起始行：从 mapping 行向上，跨过单行注解与多行注解的续行。
     * 例如 {@code @ApiResponse(\n content = @Content(...))} 的续行不以 @ 开头。
     */
    private int annotationBlockStart(List<String> lines, int mappingLine) {
        int start = mappingLine;
        for (int i = mappingLine - 1; i >= 0; i--) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            boolean annotationLine = line.startsWith("@");
            boolean continuation = line.endsWith(")") || line.endsWith(",");
            if (annotationLine || continuation) {
                start = i;
                continue;
            }
            break;
        }
        return start;
    }

    private void writeBaseline(Path baseline, Set<String> gaps) throws IOException {
        List<String> sorted = new ArrayList<>(gaps);
        sorted.sort(String::compareTo);
        StringBuilder out = new StringBuilder();
        out.append("# API 文档基线：已知没有 Javadoc / @Operation(summary) 的映射方法。\n");
        out.append("# 只允许减少：补文档后删除对应行；新增接口必须先写文档。\n");
        for (String gap : sorted) {
            out.append(gap).append('\n');
        }
        Files.writeString(baseline, out.toString(), StandardCharsets.UTF_8);
    }

    private Set<String> readBaseline(Path baseline) throws IOException {
        Set<String> known = new LinkedHashSet<>();
        if (!Files.isRegularFile(baseline)) {
            return known;
        }
        for (String line : Files.readAllLines(baseline, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                known.add(trimmed);
            }
        }
        return known;
    }

    private static int count(String text, char c) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                total++;
            }
        }
        return total;
    }
}
