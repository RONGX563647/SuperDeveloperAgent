package top.rongx.agent.arch;

import java.io.ByteArrayOutputStream;
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

/**
 * 迁移基线（{@code spec-checks/migration-baseline.txt}）的读写口径。
 *
 * <p>本类只做四件事，把它们从测试里拆出来是为了能对临时目录做自测：
 * <ul>
 *   <li>{@link #hash(Path)}：<b>LF 归一化</b>后的 sha256 —— 与 Flyway 的 checksum 同口径，
 *       行尾差异（Windows 的 CRLF / Linux 的 LF）不算改动；</li>
 *   <li>{@link #check(Path)}：校验当前迁移是否与基线一致，分出「内容被改」与「未登记」两类问题；</li>
 *   <li>{@link #rewrite(Path)}：按当前内容重算整份基线（生成物，禁止手改哈希）；</li>
 *   <li>{@link #prescription()}：失败时给出的处方文本。</li>
 * </ul>
 *
 * <p><b>为什么基线是生成物</b>：上一轮事故就是人手 {@code Get-FileHash} 算哈希——
 * 算的是工作区原始字节，于是 Windows 上生成的基线在 Linux CI 上必然对不上。
 * 口径只能由代码定义，人只负责决定「什么时候重算」。
 */
final class MigrationBaseline {

    /** 相对于 {@code platform/} 的基线文件位置。 */
    static final String FILE = "spec-checks/migration-baseline.txt";

    /** 重算开关（与 {@code -Derror.code.baseline.update=true} 同款用法）。 */
    static final String UPDATE_PROPERTY = "migration.baseline.update";

    private static final String HEADER = """
            # 迁移基线（生成物，不要手改哈希）：已发布迁移的 LF 归一化 sha256
            #
            # 口径：先把文件里的 CRLF / 孤立 CR 归一成 LF，再算 sha256。
            #       这与 Flyway 自己的 checksum 一致（它同样忽略行尾差异），
            #       所以 Windows（CRLF 检出）与 Linux CI（LF 检出）算出同一个值。
            #       ——不要用 Get-FileHash / sha256sum 手算，那是原始字节口径，必然误报。
            #
            # 规则（项目规约 §5.2）：出现在本文件里的迁移 = 已发布、不可原地修改。
            #       改内容会让 Flyway 校验和变化，线上已执行过的库下次启动直接失败；
            #       演进只能新增 V(n+1)__xxx.sql，用 ALTER TABLE / CREATE TABLE IF NOT EXISTS / DO 块。
            #
            # 重算（唯一正确姿势）：
            #   cd platform && mvn -pl spec-checks -Dtest=MigrationGuardTest -Dmigration.baseline.update=true test
            #
            # 什么时候该重算：发布切点；或确实要修改「尚未发布」的迁移时。已发布的只能新增。
            """;

    private MigrationBaseline() {
    }

    /**
     * 校验结果。
     *
     * @param violations 内容被改的迁移（已登记但哈希不符）
     * @param unrecorded 未登记的迁移（基线里没有 → 等于没守住）
     */
    record Snapshot(List<String> violations, List<String> unrecorded) {

        boolean ok() {
            return violations.isEmpty() && unrecorded.isEmpty();
        }

        int total() {
            return violations.size() + unrecorded.size();
        }
    }

    /** 扫描当前项目里所有迁移文件（{@code db/migration} 下的 {@code .sql}，排除 target）。 */
    static List<Path> migrations(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().replace('\\', '/').contains("/db/migration/"))
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(p -> !p.toString().contains("target"))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    /** 按当前内容校验基线；基线文件不存在时返回全空的快照（首次接入不判失败）。 */
    static Snapshot check(Path root) throws Exception {
        Path file = root.resolve(FILE);
        List<String> violations = new ArrayList<>();
        List<String> unrecorded = new ArrayList<>();
        if (!Files.exists(file)) {
            return new Snapshot(violations, unrecorded);
        }
        Map<String, String> recorded = parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        for (Path migration : migrations(root)) {
            String rel = relative(root, migration);
            String expected = recorded.get(rel);
            if (expected == null) {
                unrecorded.add(rel);
            } else if (!expected.equals(hash(migration))) {
                violations.add(String.format("%s（基线 %s… 当前 %s…）",
                        rel, expected.substring(0, Math.min(12, expected.length())),
                        hash(migration).substring(0, 12)));
            }
        }
        return new Snapshot(violations, unrecorded);
    }

    /**
     * 按当前内容重算整份基线（含注释头）。
     *
     * @return 相对上次记录发生变化的描述，供调用方打印
     */
    static List<String> rewrite(Path root) throws Exception {
        Path file = root.resolve(FILE);
        Map<String, String> before = Files.exists(file)
                ? parse(Files.readAllLines(file, StandardCharsets.UTF_8))
                : Map.of();
        Map<String, String> after = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder(HEADER);
        for (Path migration : migrations(root)) {
            String rel = relative(root, migration);
            String value = hash(migration);
            after.put(rel, value);
            text.append(rel).append(' ').append(value).append('\n');
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        return diff(before, after);
    }

    /** 失败处方：口径 + 两条修复路径 + 重算命令。 */
    static String prescription() {
        return String.join(System.lineSeparator(), List.of(
                "处方：",
                "  1) 这是【已发布】迁移 → 恢复原文件，改动落成新迁移：",
                "     git log --oneline -- <路径>    # 找到含旧内容的提交",
                "     git checkout <sha> -- <路径>",
                "     新增 V<n+1>__<slug>.sql，用 ALTER TABLE / CREATE TABLE IF NOT EXISTS / DO $$ … $$",
                "  2) 这是【尚未发布】迁移，或确认要接受现状 → 重算基线（生成物，别手改哈希）：",
                "     cd platform && mvn -pl spec-checks -Dtest=MigrationGuardTest "
                        + "-D" + UPDATE_PROPERTY + "=true test",
                "口径：LF 归一化后的 sha256（与 Flyway checksum 一致，行尾差异不算改动）。"));
    }

    /** 计算迁移文件的 LF 归一化 sha256。 */
    static String hash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(normalized(Files.readAllBytes(file))));
    }

    /**
     * 行尾归一化为 LF 后再算哈希。
     *
     * <p>git 的 {@code * text=auto eol=lf} 只约束仓库内的 blob，工作区实际字节取决于
     * {@code core.autocrlf} 与检出平台：Windows 上是 CRLF、Linux CI 上是 LF。
     * 按原始字节算哈希，同一份内容会有两个值 —— 那是误报而不是改动。
     * 孤立 CR 一并归成 LF，避免混合行尾产生第三个值。
     */
    static byte[] normalized(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        for (int i = 0; i < raw.length; i++) {
            byte b = raw[i];
            if (b == '\r') {
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

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static Map<String, String> parse(List<String> lines) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length == 2) {
                map.put(parts[0], parts[1]);
            }
        }
        return map;
    }

    private static List<String> diff(Map<String, String> before, Map<String, String> after) {
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, String> e : after.entrySet()) {
            String old = before.get(e.getKey());
            if (old == null) {
                changes.add("新增 " + e.getKey());
            } else if (!old.equals(e.getValue())) {
                changes.add("更新 " + e.getKey() + "（" + old.substring(0, 8) + "… → "
                        + e.getValue().substring(0, 8) + "…）");
            }
        }
        for (String path : before.keySet()) {
            if (!after.containsKey(path)) {
                changes.add("移除 " + path);
            }
        }
        return changes;
    }
}
