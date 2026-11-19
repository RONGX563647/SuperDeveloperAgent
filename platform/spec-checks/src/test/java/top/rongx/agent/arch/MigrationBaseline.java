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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 迁移基线（{@code spec-checks/migration-baseline.txt}）的读写口径。
 *
 * <p>把口径从测试里拆出来是为了能对临时目录做自测：
 * <ul>
 *   <li>{@link #hash(Path)}：<b>LF 归一化</b>后的 sha256 —— 与 Flyway 的 checksum 同口径；</li>
 *   <li>{@link #releaseTag(Path)}：最近一次发布 tag（{@code x.y.z}，仓库约定不带 {@code v}），
 *       它是「哪些迁移算已发布」的真相源；</li>
 *   <li>{@link #check(Path)}：以 tag 为锚把当前迁移分成三类——被改 / 已发布未登记 / 未发布；</li>
 *   <li>{@link #rewrite(Path)}：按当前内容重算整份基线（生成物，禁止手改哈希）；</li>
 *   <li>{@link #prescription()}：失败时给出的处方文本。</li>
 * </ul>
 *
 * <p><b>为什么基线是生成物</b>：曾有人手算 {@code Get-FileHash}——算的是工作区原始字节，
 * Windows（CRLF）上生成的基线在 Linux CI（LF）上必然对不上，三个迁移被误报。
 * 口径只能由代码定义，人只负责决定「什么时候重算」。
 *
 * <p><b>为什么要 tag 锚点</b>：没有锚点时只能"未登记即失败"，等于连还没发布的迁移
 * 也被提前冻结。有了 release tag，未发布迁移可以自由演进（只要在发布切点重算基线），
 * 已发布的迁移仍然一律新增。
 */
final class MigrationBaseline {

    /** 相对于 {@code platform/} 的基线文件位置。 */
    static final String FILE = "spec-checks/migration-baseline.txt";

    /** 重算开关（与 {@code -Derror.code.baseline.update=true} 同款用法）。 */
    static final String UPDATE_PROPERTY = "migration.baseline.update";

    /** 发布 tag 形态：仓库约定 {@code x.y.z}，不带 {@code v}（见 domain/release/ReleaseVersion）。 */
    static final String RELEASE_TAG_GLOB = "[0-9]*.[0-9]*.[0-9]*";

    private static final String HEADER = """
            # 迁移基线（生成物，不要手改哈希）：已发布迁移的 LF 归一化 sha256
            #
            # 口径：先把文件里的 CRLF / 孤立 CR 归一成 LF，再算 sha256。
            #       这与 Flyway 自己的 checksum 一致（它同样忽略行尾差异），
            #       所以 Windows（CRLF 检出）与 Linux CI（LF 检出）算出同一个值。
            #       ——不要用 Get-FileHash / sha256sum 手算，那是原始字节口径，必然误报。
            #
            # 锚点：最近一次发布 tag（x.y.z）决定「哪些迁移算已发布」。tag 树里的迁移 = 已发布、
            #       不可原地修改；tag 之后新增的迁移 = 未发布，可以改，但发布切点必须重算基线。
            #       CI 的 backend job 需 fetch-depth: 0，否则浅克隆没有 tag，会把未发布迁移误判成已发布。
            #
            # 规则（项目规约 §5.2）：已发布迁移改内容会让 Flyway 校验和变化，线上已执行过的库
            #       下次启动直接失败；演进只能新增 V(n+1)__xxx.sql，用 ALTER TABLE /
            #       CREATE TABLE IF NOT EXISTS / DO 块。
            #
            # 重算（唯一正确姿势；发布切点或改未发布迁移时执行）：
            #   cd platform && mvn -pl spec-checks -Dtest=MigrationGuardTest -Dmigration.baseline.update=true test
            """;

    private MigrationBaseline() {
    }

    /**
     * 校验结果。
     *
     * @param violations 已发布且已登记，但内容被改
     * @param unrecorded 已发布但基线里没有（守不住的缺口）
     * @param unreleased 未发布（允许改动，发布切点再补录）
     * @param anchor     本次判定用的发布 tag；无法解析时为 {@code null}
     */
    record Snapshot(List<String> violations, List<String> unrecorded,
                    List<String> unreleased, String anchor) {

        boolean ok() {
            return violations.isEmpty() && unrecorded.isEmpty();
        }

        int total() {
            return violations.size() + unrecorded.size();
        }

        String anchorLabel() {
            return anchor == null ? "无（按全部已发布处理）" : anchor;
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

    /**
     * 以发布 tag 为锚校验基线；基线文件不存在时返回全空的快照（首次接入不判失败）。
     *
     * <p>解析不到 tag（浅克隆、或仓库还没发过版）时退化成"全部按已发布处理"：
     * 宁可让未发布迁移也要求登记，也不能让已发布迁移的改动漏过去。
     */
    static Snapshot check(Path root) throws Exception {
        Path file = root.resolve(FILE);
        if (!Files.exists(file)) {
            return new Snapshot(List.of(), List.of(), List.of(), releaseTag(root));
        }
        String anchor = releaseTag(root);
        Set<String> released = anchor == null ? Set.of() : releasedMigrations(root, anchor);
        return check(root, released, anchor);
    }

    /**
     * 纯逻辑校验：{@code released} 为空集表示"无法解析 tag，全部按已发布处理"。
     *
     * <p>拆出这一层是为了让三条分支（被改 / 已发布未登记 / 未发布）能用临时目录直接自测，
     * 不必在测试里造 git 仓库和 tag。
     */
    static Snapshot check(Path root, Set<String> released, String anchor) throws Exception {
        Path file = root.resolve(FILE);
        Map<String, String> recorded = Files.exists(file)
                ? parse(Files.readAllLines(file, StandardCharsets.UTF_8))
                : Map.of();
        List<String> violations = new ArrayList<>();
        List<String> unrecorded = new ArrayList<>();
        List<String> unreleased = new ArrayList<>();
        for (Path migration : migrations(root)) {
            String rel = relative(root, migration);
            String expected = recorded.get(rel);
            boolean isReleased = released.isEmpty() || released.contains(rel);
            if (!isReleased) {
                unreleased.add(rel);
                continue;
            }
            if (expected == null) {
                unrecorded.add(rel);
            } else if (!expected.equals(hash(migration))) {
                violations.add(String.format("%s（基线 %s… 当前 %s…）",
                        rel, expected.substring(0, Math.min(12, expected.length())),
                        hash(migration).substring(0, 12)));
            }
        }
        return new Snapshot(violations, unrecorded, unreleased, anchor);
    }

    /** 最近一次发布 tag；没有 tag 或 git 不可用时返回 {@code null}。 */
    static String releaseTag(Path root) {
        Path repo = repoRoot(root);
        if (repo == null) {
            return null;
        }
        String tag = git(repo, "describe", "--tags", "--abbrev=0", "--match", RELEASE_TAG_GLOB);
        return tag == null || tag.isBlank() ? null : tag.trim();
    }

    /** 某次发布 tag 树里的迁移文件（相对 {@code platform/}，与基线写法一致）。 */
    static Set<String> releasedMigrations(Path root, String tag) {
        Path repo = repoRoot(root);
        if (repo == null) {
            return Set.of();
        }
        String listing = git(repo, "ls-tree", "-r", "--name-only", tag, "--", "platform");
        if (listing == null) {
            return Set.of();
        }
        Set<String> released = new LinkedHashSet<>();
        for (String line : listing.split("\\R")) {
            String path = line.trim().replace('\\', '/');
            if (!path.contains("/db/migration/") || !path.endsWith(".sql")) {
                continue;
            }
            released.add(path.startsWith("platform/") ? path.substring("platform/".length()) : path);
        }
        return released;
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

    /** 失败处方：口径 + 三条修复路径 + 重算命令。 */
    static String prescription() {
        return String.join(System.lineSeparator(), List.of(
                "处方：",
                "  1) 【已发布】迁移被改 → 恢复原文件，改动落成新迁移：",
                "     git log --oneline -- <路径>    # 找到含旧内容的提交",
                "     git checkout <sha> -- <路径>",
                "     新增 V<n+1>__<slug>.sql，用 ALTER TABLE / CREATE TABLE IF NOT EXISTS / DO $$ … $$",
                "  2) 【已发布】但没登记 → 说明发布切点漏了补录：确认文件内容与 tag 一致后重算基线。",
                "  3) 【未发布】迁移要改 → 直接改，发布切点重算基线即可（未发布的不受冻结约束）。",
                "  重算（生成物，别手改哈希）：",
                "     cd platform && mvn -pl spec-checks -Dtest=MigrationGuardTest "
                        + "-D" + UPDATE_PROPERTY + "=true test",
                "口径：LF 归一化后的 sha256（与 Flyway checksum 一致，行尾差异不算改动）。",
                "锚点：最近一次发布 tag（x.y.z）；CI 需 fetch-depth: 0 才能看到 tag。"));
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

    /** 从 {@code platform} 往上找含 {@code .git} 的仓库根；找不到返回 {@code null}。 */
    private static Path repoRoot(Path root) {
        for (Path dir = root.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(".git")) || Files.isRegularFile(dir.resolve(".git"))) {
                return dir;
            }
        }
        return null;
    }

    /** 跑一条 git 子命令；git 不存在或非 0 退出时返回 {@code null}（调用方按"无锚点"降级）。 */
    private static String git(Path repo, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(List.of(args));
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 ? output : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
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
