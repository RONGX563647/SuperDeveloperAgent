package top.rongx.agent.arch;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 项目规约 §5.2：已执行的迁移不可修改。
 *
 * <p>{@code spec-checks/migration-baseline.txt} 记录已发布迁移的 <b>LF 归一化</b> sha256，
 * 而"哪些迁移算已发布"由最近一次发布 tag（{@code x.y.z}）决定。于是迁移被分成三类：
 * <table border="1">
 *   <caption>M1 三类判定</caption>
 *   <tr><th>类别</th><th>判定</th><th>理由</th></tr>
 *   <tr><td>已发布 + 已登记 + 内容一致</td><td>通过</td><td>—</td></tr>
 *   <tr><td>已发布 + 已登记 + 内容变了</td><td><b>失败</b></td><td>Flyway 校验和会变，线上库启动即失败</td></tr>
 *   <tr><td>已发布 + 未登记</td><td><b>失败</b></td><td>基线漏登记 = 这个文件没人守</td></tr>
 *   <tr><td>未发布（tag 之后新增）</td><td>通过（提示）</td><td>还没上过线，可以自由演进</td></tr>
 * </table>
 *
 * <p>重算基线：{@code mvn -pl spec-checks -Dtest=MigrationGuardTest -Dmigration.baseline.update=true test}
 */
class MigrationGuardTest {

    private static final String UPDATE = MigrationBaseline.UPDATE_PROPERTY;

    private static final String MIGRATION_DIR =
            "agent-infra/src/main/resources/db/migration/common/";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("行尾归一化：CRLF 与 LF 检出算出的哈希必须相同")
    void lineEndingsDoNotAffectHash() throws Exception {
        // M1 曾误报的根因：基线在某一台机器上算出，而 git 的 `* text=auto eol=lf`
        // 只约束仓库内的 blob，工作区实际字节取决于 core.autocrlf 与检出平台。
        String text = "CREATE TABLE t (id TEXT);\nINSERT INTO t VALUES ('a');\n";
        Path lf = write(tempDir.resolve("lf.sql"), text);
        Path crlf = write(tempDir.resolve("crlf.sql"), text.replace("\n", "\r\n"));
        // 混合行尾（编辑器或补丁工具只转换了一部分）也要归一到同一哈希
        Path mixed = write(tempDir.resolve("mixed.sql"), text.replace(";\n", ";\r\n"));

        String lfHash = MigrationBaseline.hash(lf);
        assertEquals(lfHash, MigrationBaseline.hash(crlf), "CRLF 与 LF 必须算出同一哈希");
        assertEquals(lfHash, MigrationBaseline.hash(mixed), "混合行尾必须归一到同一哈希");
    }

    @Test
    @DisplayName("归一化不得掩盖真实内容改动")
    void normalizationStillDetectsRealEdits() throws Exception {
        Path before = write(tempDir.resolve("before.sql"), "CREATE TABLE t (id TEXT);\n");
        Path after = write(tempDir.resolve("after.sql"),
                "CREATE TABLE t (id TEXT, name TEXT);\r\n");
        assertNotEquals(MigrationBaseline.hash(before), MigrationBaseline.hash(after),
                "内容被改动时必须仍然能被发现");
    }

    @Test
    @DisplayName("已发布迁移被改：失败，并给出恢复原文件 + 新增迁移的处方")
    void editedReleasedMigrationFailsWithPrescription() throws Exception {
        Path root = fixtureRoot("V1__platform_schema.sql", "CREATE TABLE a (id TEXT);\n");
        MigrationBaseline.rewrite(root);
        write(root.resolve(MIGRATION_DIR + "V1__platform_schema.sql"),
                "CREATE TABLE a (id TEXT, name TEXT);\n");
        Set<String> released = Set.of(MIGRATION_DIR + "V1__platform_schema.sql");

        MigrationBaseline.Snapshot snapshot = MigrationBaseline.check(root, released, "0.1.0");

        assertFalse(snapshot.ok(), "改历史迁移必须失败");
        assertEquals(1, snapshot.violations().size());
        assertTrue(snapshot.violations().get(0).contains("V1__platform_schema.sql"));
        assertTrue(snapshot.unrecorded().isEmpty());
        assertTrue(snapshot.unreleased().isEmpty());

        String prescription = MigrationBaseline.prescription();
        assertTrue(prescription.contains("git checkout"), "处方要给恢复原文件的命令");
        assertTrue(prescription.contains("V<n+1>__<slug>.sql"), "处方要指向新增迁移");
        assertTrue(prescription.contains(UPDATE), "处方要给重算命令");
    }

    @Test
    @DisplayName("已发布但未登记：失败（不再只打 hint）")
    void releasedButUnrecordedFailsLoudly() throws Exception {
        Path root = fixtureRoot("V1__platform_schema.sql", "CREATE TABLE a (id TEXT);\n");
        writeBaseline(root);  // 基线存在但一条都没登记
        Set<String> released = Set.of(MIGRATION_DIR + "V1__platform_schema.sql");

        MigrationBaseline.Snapshot snapshot = MigrationBaseline.check(root, released, "0.1.0");

        assertFalse(snapshot.ok(), "已发布却没登记必须失败");
        assertEquals(1, snapshot.unrecorded().size());
        assertTrue(snapshot.unrecorded().get(0).endsWith("V1__platform_schema.sql"));
        assertTrue(snapshot.violations().isEmpty());
    }

    @Test
    @DisplayName("未发布迁移（tag 之后新增）：放行，不进红名单")
    void unreleasedMigrationIsEditable() throws Exception {
        Path root = fixtureRoot("V1__platform_schema.sql", "CREATE TABLE a (id TEXT);\n");
        MigrationBaseline.rewrite(root);
        write(root.resolve(MIGRATION_DIR + "V2__identity_tables.sql"),
                "CREATE TABLE b (id BIGINT GENERATED BY DEFAULT AS IDENTITY);\r\n");
        // V1 已在 0.1.0 里发布，V2 是 tag 之后新增的
        Set<String> released = Set.of(MIGRATION_DIR + "V1__platform_schema.sql");

        MigrationBaseline.Snapshot snapshot = MigrationBaseline.check(root, released, "0.1.0");

        assertTrue(snapshot.ok(), "未发布迁移不该判红");
        assertEquals(List.of(MIGRATION_DIR + "V2__identity_tables.sql"), snapshot.unreleased());
        assertEquals("0.1.0", snapshot.anchor());
    }

    @Test
    @DisplayName("重算基线：按 LF 口径写回、保留注释头、重算后自洽")
    void rewriteMakesBaselineCanonical() throws Exception {
        Path root = fixtureRoot("V1__platform_schema.sql", "CREATE TABLE a (id TEXT);\r\n");
        Path baseline = root.resolve(MigrationBaseline.FILE);

        MigrationBaseline.rewrite(root);

        String text = Files.readString(baseline, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("# 迁移基线"), "注释头必须由代码写回，保留口径说明");
        assertTrue(text.contains(UPDATE), "注释头要带重算命令");
        assertTrue(text.contains("锚点"), "注释头要写明 tag 锚点语义");
        Path lfTwin = write(tempDir.resolve("twin.sql"), "CREATE TABLE a (id TEXT);\n");
        assertTrue(text.contains(MigrationBaseline.hash(lfTwin)), "基线必须记 LF 归一化哈希");

        assertTrue(MigrationBaseline.check(root, Set.of(), null).ok(), "重算后必须自洽");
    }

    @Test
    @DisplayName("发布锚点可用：最近一次 tag 的迁移集合与基线一致")
    void releaseAnchorMatchesTagTree() throws Exception {
        Path root = Path.of(System.getProperty("spec.root", ".")).toAbsolutePath().normalize();
        String anchor = MigrationBaseline.releaseTag(root);
        Assumptions.assumeTrue(anchor != null,
                "解析不到发布 tag（浅克隆？CI 的 backend job 需要 fetch-depth: 0），跳过锚点回归");
        assertTrue(anchor.matches(MigrationBaseline.RELEASE_TAG_GLOB),
                "发布 tag 必须形如 x.y.z，实际 " + anchor);

        Set<String> released = MigrationBaseline.releasedMigrations(root, anchor);
        assertFalse(released.isEmpty(), "tag " + anchor + " 下应当能读到迁移文件");
        for (String path : released) {
            assertTrue(path.endsWith(".sql") && path.contains("db/migration/"),
                    "锚点集合只该包含迁移：" + path);
        }
    }

    @Test
    @DisplayName("已发布的迁移文件不得被修改（真实仓库）")
    void appliedMigrationsAreImmutable() throws Exception {
        Path root = Path.of(System.getProperty("spec.root", ".")).toAbsolutePath().normalize();

        if (Boolean.getBoolean(UPDATE)) {
            // 生成模式：只重算，不判红——这是"未发布迁移要改"与"发布切点补录"的正路
            var changes = MigrationBaseline.rewrite(root);
            System.out.printf("[M1] 已重算迁移基线（%s=true）：%d 项变化%n", UPDATE, changes.size());
            changes.forEach(change -> System.out.println("  " + change));
            return;
        }

        if (!Files.exists(root.resolve(MigrationBaseline.FILE))) {
            System.out.printf("[M1] 基线文件不存在，跳过（发现 %d 个迁移）%n",
                    MigrationBaseline.migrations(root).size());
            return;
        }

        MigrationBaseline.Snapshot snapshot = MigrationBaseline.check(root);
        if (snapshot.ok()) {
            System.out.printf("[M1] 迁移基线一致（%d 个迁移，锚点 %s，未发布 %d 个）%n",
                    MigrationBaseline.migrations(root).size(), snapshot.anchorLabel(),
                    snapshot.unreleased().size());
            snapshot.unreleased().forEach(path -> System.out.println("  - 未发布：" + path));
            return;
        }
        StringBuilder report = new StringBuilder("[M1] 迁移基线守卫失败（")
                .append(snapshot.total()).append(" 项，锚点 ").append(snapshot.anchorLabel())
                .append("）").append(System.lineSeparator());
        snapshot.violations().forEach(v -> report.append("  - 已发布被修改：").append(v)
                .append(System.lineSeparator()));
        snapshot.unrecorded().forEach(u -> report.append("  - 已发布未登记：").append(u)
                .append(System.lineSeparator()));
        report.append(MigrationBaseline.prescription());
        fail(report.toString());
    }

    /** 造一个只有一条迁移（和空基线）的临时仓库根。 */
    private Path fixtureRoot(String fileName, String content) throws Exception {
        write(tempDir.resolve(MIGRATION_DIR + fileName), content);
        return tempDir;
    }

    /** 只写注释头的基线：文件存在但一条都没登记。 */
    private void writeBaseline(Path root) throws Exception {
        Path file = root.resolve(MigrationBaseline.FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# 迁移基线（生成物）\n# 锚点：暂无\n", StandardCharsets.UTF_8);
    }

    private static Path write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
