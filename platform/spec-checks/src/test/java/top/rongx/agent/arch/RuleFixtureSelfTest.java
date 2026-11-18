package top.rongx.agent.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 禁令规则的 fixture 自测：验证"规则本身是有效的"。
 *
 * <p>解决的问题：{@link ForbiddenPatternScanTest} 为绿色只说明"生产代码里没有命中"，
 * 如果正则写错（改坏、写窄、作用域标错），它同样会永远绿——门禁静默失效。
 * 这里用 {@code fixtures/} 下的样例反向验证每条规则都必须命中。
 *
 * <p>样例布局刻意保持 {@code .../src/main/java/...} 与 {@code .../src/main/resources/...}，
 * 这样与生产扫描走完全相同的收集逻辑。fixtures 只在把 {@code spec.root} 指过来时被扫描，
 * 生产扫描（root = platform/）会跳过整个 {@code spec-checks/}。
 */
class RuleFixtureSelfTest {

    /** 样例根目录：spec.root 默认是 platform/。 */
    private static Path fixtures() {
        Path root = Path.of(System.getProperty("spec.root", ".")).toAbsolutePath().normalize();
        return root.resolve("spec-checks").resolve("fixtures");
    }

    @Test
    @DisplayName("每条禁令规则都必须命中自己的样例（防正则失效）")
    void everyRuleHitsItsSample() throws IOException {
        Path root = fixtures();
        assertTrue(Files.isDirectory(root), "缺少 fixtures 目录：" + root);

        List<String> violations = ForbiddenPatternScan.violations(root);
        Set<String> hit = violations.stream()
                .map(line -> line.substring(1, line.indexOf(']')))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> expected = ForbiddenPatternScan.rules().stream()
                .map(ForbiddenPatternScan.Rule::id)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(hit);
        assertTrue(missing.isEmpty(),
                () -> "以下规则未命中样例（正则失效或样例丢失）：" + missing
                        + "\n实际命中：" + hit);
    }

    @Test
    @DisplayName("合规范例不得产生任何违规（防规则过宽误报）")
    void cleanSampleHasNoViolations() throws IOException {
        Path root = fixtures().resolve("clean");
        assertTrue(Files.isDirectory(root), "缺少 clean 样例目录：" + root);

        int scanned = ForbiddenPatternScan.scannedFiles(root);
        assertTrue(scanned > 0, "clean 样例没有被扫描到（目录结构应为 .../src/main/java/...）");

        List<String> violations = ForbiddenPatternScan.violations(root);
        assertEquals(List.of(), violations,
                () -> "合规范例被误判：" + String.join(System.lineSeparator(), violations));
    }
}
