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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    @DisplayName("行尾归一化：CRLF 与 LF 检出算出的哈希必须相同")
    void lineEndingsDoNotAffectHash() throws Exception {
        // M1 曾误报的根因：基线在某一台机器上算出，而 git 的 `* text=auto eol=lf`
        // 只约束仓库内的 blob，工作区实际字节取决于 core.autocrlf 与检出平台。
        // Windows 检出 CRLF、Linux 检出 LF，同一份文件得到两个哈希。
        Path lf = Files.createTempFile("mig-lf", ".sql");
        Path crlf = Files.createTempFile("mig-crlf", ".sql");
        Path mixed = Files.createTempFile("mig-mixed", ".sql");
        try {
            String text = "CREATE TABLE t (id TEXT);\nINSERT INTO t VALUES ('a');\n";
            Files.write(lf, text.getBytes(StandardCharsets.UTF_8));
            Files.write(crlf, text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
            // 混合行尾（编辑器或补丁工具只转换了一部分）也要归一到同一哈希
            Files.write(mixed, text.replace(";\n", ";\r\n").getBytes(StandardCharsets.UTF_8));

            String lfHash = sha256(lf);
            assertEquals(lfHash, sha256(crlf), "CRLF 与 LF 必须算出同一哈希");
            assertEquals(lfHash, sha256(mixed), "混合行尾必须归一到同一哈希");
        } finally {
            Files.deleteIfExists(lf);
            Files.deleteIfExists(crlf);
            Files.deleteIfExists(mixed);
        }
    }

    @Test
    @DisplayName("归一化不得掩盖真实内容改动")
    void normalizationStillDetectsRealEdits() throws Exception {
        // 反向保证：归一化只吃掉行尾差异，内容变了必须还是不同哈希。
        Path before = Files.createTempFile("mig-before", ".sql");
        Path after = Files.createTempFile("mig-after", ".sql");
        try {
            Files.write(before, "CREATE TABLE t (id TEXT);\n".getBytes(StandardCharsets.UTF_8));
            Files.write(after, "CREATE TABLE t (id TEXT, name TEXT);\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            assertNotEquals(sha256(before), sha256(after), "内容被改动时必须仍然能被发现");
        } finally {
            Files.deleteIfExists(before);
            Files.deleteIfExists(after);
        }
    }

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
        return HexFormat.of().formatHex(md.digest(normalized(Files.readAllBytes(file))));
    }

    /**
     * 行尾归一化为 LF 后再算哈希。
     *
     * <p><b>为什么必须归一化</b>：基线是在某台机器上算出来的，而 git 的
     * {@code * text=auto eol=lf} 只约束仓库内的 blob；工作区里的实际字节取决于
     * {@code core.autocrlf} 与检出平台。Windows 上检出为 CRLF、Linux CI 上检出为 LF，
     * 同一份文件会得到两个哈希——于是"已在基线中的迁移被改动"在 CI 上误报，
     * 真正被改动的文件反倒可能因两边都 CRLF 而漏报。
     *
     * <p>归一化之后，哈希只反映<b>内容</b>，与检出平台无关。这也是 Flyway 自己的判定
     * 口径：它的校验和同样忽略行尾差异。
     */
    private static byte[] normalized(byte[] raw) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length);
        for (int i = 0; i < raw.length; i++) {
            byte b = raw[i];
            if (b == '\r') {
                // CRLF → LF；孤立的 CR 也一并归成 LF，避免混合行尾产生第三个哈希
                out.write('\n');
                if (i + 1 < raw.length && raw[i + 1] == '\n') {
                    i++;
                }
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }
}
