package top.rongx.agent.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目规约 §5.2：已执行的迁移不可修改。
 *
 * <p>{@code spec-checks/migration-baseline.txt} 记录已发布迁移的 sha256；
 * 内容变化即失败（Flyway 校验和会变，且线上已执行过）。
 * 基线文件缺失时跳过（首次接入）。
 */
class MigrationGuardTest {

    private static final String BASELINE = "spec-checks/migration-baseline.txt";

    @Test
    @DisplayName("已发布的迁移文件不得被修改")
    void appliedMigrationsAreImmutable() throws Exception {
        Path root = Path.of(System.getProperty("spec.root", ".")).toAbsolutePath().normalize();
        Path baseline = root.resolve(BASELINE);
        List<Path> migrations = collect(root);

        if (!Files.exists(baseline)) {
            System.out.printf("[M1] 基线文件不存在，跳过（发现 %d 个迁移）%n", migrations.size());
            return;
        }

        Map<String, String> recorded = parse(Files.readAllLines(baseline, StandardCharsets.UTF_8));
        List<String> violations = new ArrayList<>();
        List<String> unrecorded = new ArrayList<>();

        for (Path file : migrations) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String expected = recorded.get(rel);
            String actual = sha256(file);
            if (expected == null) {
                unrecorded.add(rel);
            } else if (!expected.equals(actual)) {
                violations.add(String.format("[M1] %s 已被修改（基线 %s… 当前 %s…）—— 项目规约 §5.2：已执行迁移必须新增，不得原地修改",
                        rel, expected.substring(0, 12), actual.substring(0, 12)));
            }
        }

        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));

        if (!unrecorded.isEmpty()) {
            System.out.printf("[M1] 提示：%d 个迁移尚未纳入基线（发布后补录）：%n  %s%n",
                    unrecorded.size(), String.join(System.lineSeparator() + "  ", unrecorded));
        }
    }

    private static List<Path> collect(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().replace('\\', '/').contains("/db/migration/"))
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(p -> !p.toString().contains("target"))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    private static Map<String, String> parse(List<String> lines) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\\s+", 2);
            if (parts.length == 2) {
                map.put(parts[0], parts[1]);
            }
        }
        return map;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(Files.readAllBytes(file)));
    }
}
