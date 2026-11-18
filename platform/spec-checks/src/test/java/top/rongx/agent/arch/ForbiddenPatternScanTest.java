package top.rongx.agent.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 禁令扫描（项目规约 §5 / §7 / §10）。
 *
 * <p>规则与扫描实现见 {@link ForbiddenPatternScan}——规则只有一份，
 * 本类负责"生产代码必须干净"，{@link RuleFixtureSelfTest} 负责"规则本身有效"。
 */
class ForbiddenPatternScanTest {

    @Test
    @DisplayName("生产代码不得命中禁令规则")
    void noForbiddenPatterns() throws IOException {
        Path root = Path.of(System.getProperty("spec.root", ".")).toAbsolutePath().normalize();
        List<String> violations = ForbiddenPatternScan.violations(root);
        int scanned = ForbiddenPatternScan.scannedFiles(root);

        assertTrue(violations.isEmpty(),
                () -> String.format("发现 %d 处规约违规（扫描 %d 个文件实例）：%n%s",
                        violations.size(), scanned, String.join(System.lineSeparator(), violations)));
    }
}
