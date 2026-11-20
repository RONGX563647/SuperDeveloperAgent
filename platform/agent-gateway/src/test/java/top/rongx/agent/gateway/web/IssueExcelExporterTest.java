package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;

/**
 * 任务导出 xlsx 的契约：三张表，正文 / 评论 / 图片都要在。
 *
 * <p>手写 OOXML 的风险全在"Excel 能不能打开、内容对不对"上，肉眼看字符串不算验证，
 * 所以这里把生成物**解压回来看**：部件齐不齐、引用关系对不对、文本有没有转义。
 */
class IssueExcelExporterTest {

    /** 可识别的 PNG 头。 */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
    };
    /** webp：Excel 不支持，应只登记文件名。 */
    private static final byte[] WEBP_BYTES = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    private static GitIssue issue(String id, String code, String title, String body) {
        return new GitIssue(
                id, code, "u1", "p1", "GITEE", "acme/demo", "12",
                title, body, "OPEN", "BUG", "P0", "backend,ui", "https://example/1",
                "PLATFORM", "SYNCED", "", "alice", 1_700_000_000_000L, 1_700_000_600_000L,
                "0.1.10");
    }

    private static GitIssueComment comment(String author, String body) {
        return new GitIssueComment("c-" + author, "i1", author, body, "PLATFORM",
                1_700_000_300_000L);
    }

    private static Map<String, String> unzip(byte[] xlsx) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(xlsx), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    private static byte[] render(List<GitIssue> issues,
                                 Map<String, List<GitIssueComment>> comments,
                                 Path imageDir) throws IOException {
        return IssueExcelExporter.render(new IssueExcelExporter.ExportData(
                issues, id -> comments.getOrDefault(id, List.of()), imageDir));
    }

    @Test
    @DisplayName("三张工作表都在：任务 / 评论 / 图片")
    void hasThreeSheets() throws IOException {
        Map<String, String> parts = unzip(render(
                List.of(issue("i1", "BUG-1", "标题", "正文")), Map.of(), null));
        String workbook = parts.get("xl/workbook.xml");

        for (String name : IssueExcelExporter.sheetNames()) {
            assertTrue(workbook.contains("name=\"" + name + "\""), "缺工作表：" + name);
        }
        for (int i = 1; i <= 3; i++) {
            assertTrue(parts.containsKey("xl/worksheets/sheet" + i + ".xml"), "缺 sheet" + i);
        }
        assertTrue(parts.get("[Content_Types].xml").contains("/xl/worksheets/sheet3.xml"));
    }

    @Test
    @DisplayName("任务表带正文与评论数/图片数")
    void taskSheetCarriesBodyAndCounts() throws IOException {
        String body = "复现步骤：\n1. 打开页面\n2. 点击保存 → 500";
        Map<String, List<GitIssueComment>> comments = Map.of(
                "i1", List.of(comment("bob", "我也遇到了"), comment("carol", "已修")));

        String sheet = unzip(render(
                List.of(issue("i1", "BUG-7", "登录页偶发 500", body)), comments, null))
                .get("xl/worksheets/sheet1.xml");

        for (String header : IssueExcelExporter.headers()) {
            assertTrue(sheet.contains(header), "缺少表头：" + header);
        }
        assertTrue(sheet.contains("BUG-7"));
        assertTrue(sheet.contains("复现步骤"), "正文必须导出");
        assertTrue(sheet.contains("点击保存"), "多行正文要完整保留");
        assertTrue(sheet.contains(">2<"), "评论数应为 2");
        assertTrue(sheet.contains(">0<"), "无配图时图片数为 0");
    }

    @Test
    @DisplayName("评论表逐条列出作者与内容")
    void commentSheetListsEveryComment() throws IOException {
        Map<String, List<GitIssueComment>> comments = Map.of("i1", List.of(
                comment("bob", "我也遇到了 & 复现了"),
                comment("carol", "已修 <b>v2</b>")));

        String sheet = unzip(render(
                List.of(issue("i1", "BUG-9", "标题", "")), comments, null))
                .get("xl/worksheets/sheet2.xml");

        assertTrue(sheet.contains("BUG-9"), "评论要能对回任务编码");
        assertTrue(sheet.contains("bob"));
        assertTrue(sheet.contains("carol"));
        assertTrue(sheet.contains("我也遇到了 &amp; 复现了"), "评论内容要转义");
        assertTrue(sheet.contains("已修 &lt;b&gt;v2&lt;/b&gt;"));
        assertEquals(3, sheet.split("<row ", -1).length - 1, "表头 + 2 条评论");
    }

    @Test
    @DisplayName("配图真正嵌入：media 与 drawing 关系齐全")
    void embedsImagesReferencedFromBody(@TempDir Path imageDir) throws Exception {
        Files.createDirectories(imageDir);
        Files.write(imageDir.resolve("shot.png"), PNG_BYTES);
        String body = "见截图：\n![登录失败](/api/v1/git-issue-images/shot.png)";

        Map<String, String> parts = unzip(render(
                List.of(issue("i1", "BUG-3", "截图任务", body)), Map.of(), imageDir));

        assertTrue(parts.containsKey("xl/media/image1.png"), "图片字节要进 media");
        assertTrue(parts.get("xl/worksheets/sheet3.xml").contains("<drawing r:id=\"rId1\"/>"));
        assertTrue(parts.get("xl/worksheets/_rels/sheet3.xml.rels")
                .contains("../drawings/drawing1.xml"));
        assertTrue(parts.get("xl/drawings/drawing1.xml").contains("r:embed=\"rId1\""));
        assertTrue(parts.get("xl/drawings/_rels/drawing1.xml.rels")
                .contains("../media/image1.png"));
        assertTrue(parts.get("[Content_Types].xml").contains("Extension=\"png\""),
                "png 要有 content type 默认项");
        assertTrue(parts.get("[Content_Types].xml").contains("drawing1.xml"));
        assertTrue(parts.get("xl/worksheets/sheet3.xml").contains("BUG-3"));
        assertTrue(parts.get("xl/worksheets/sheet3.xml").contains("shot.png"));
        // 图片表挂了 drawing，任务表的图片数也要跟着是 1
        assertTrue(parts.get("xl/worksheets/sheet1.xml").contains(">1<"));
    }

    @Test
    @DisplayName("Excel 不支持的格式只登记文件名，不塞进 media")
    void skipsUnsupportedImageFormat(@TempDir Path imageDir) throws Exception {
        Files.createDirectories(imageDir);
        Files.write(imageDir.resolve("shot.webp"), WEBP_BYTES);
        String body = "![](/api/v1/git-issue-images/shot.webp)";

        Map<String, String> parts = unzip(render(
                List.of(issue("i1", "BUG-4", "webp 任务", body)), Map.of(), imageDir));

        assertFalse(parts.containsKey("xl/media/image1.webp"), "webp 不能被当成图片嵌入");
        String sheet = parts.get("xl/worksheets/sheet3.xml");
        assertTrue(sheet.contains("shot.webp"));
        assertTrue(sheet.contains("不支持"), "要说明为什么没嵌图");
        assertFalse(sheet.contains("<drawing"), "没有可嵌入图片时不该挂 drawing");
        assertFalse(parts.containsKey("xl/drawings/drawing1.xml"),
                "没有可嵌入图片时不该写 drawing 部件（否则 Excel 报内容有问题）");
    }

    @Test
    @DisplayName("配图文件名不能逃出目录：带 ../ 的一律跳过")
    void rejectsPathTraversal(@TempDir Path imageDir) throws Exception {
        Files.createDirectories(imageDir);
        String body = "![](/api/v1/git-issue-images/../secret.png)";

        String sheet = unzip(render(
                List.of(issue("i1", "BUG-5", "越权任务", body)), Map.of(), imageDir))
                .get("xl/worksheets/sheet3.xml");

        assertTrue(sheet.contains("文件名非法"), sheet);
    }

    @Test
    @DisplayName("文件不在了只登记说明，不让整份导出失败")
    void missingImageIsReportedNotFatal(@TempDir Path imageDir) throws Exception {
        Files.createDirectories(imageDir);
        String body = "![](/api/v1/git-issue-images/gone.png)";

        String sheet = unzip(render(
                List.of(issue("i1", "BUG-6", "缺图任务", body)), Map.of(), imageDir))
                .get("xl/worksheets/sheet3.xml");

        assertTrue(sheet.contains("gone.png"));
        assertTrue(sheet.contains("文件已不存在"), sheet);
    }

    @Test
    @DisplayName("特殊字符与超长正文都不得产出损坏文件")
    void escapesAndClamps() throws IOException {
        String huge = "x".repeat(40_000);
        String sheet = unzip(render(
                List.of(issue("i1", "BUG-8", "A & B <script> \"q\"", huge)), Map.of(), null))
                .get("xl/worksheets/sheet1.xml");

        assertTrue(sheet.contains("A &amp; B &lt;script&gt;"));
        assertFalse(sheet.contains("<script>"));
        assertTrue(sheet.contains("已截断"), "超过单元格上限必须截断");
    }

    @Test
    @DisplayName("控制字符被剔除，制表与换行保留")
    void stripsIllegalControlChars() {
        assertEquals("ab", IssueExcelExporter.escape("a\u0000\u0001b"));
        assertEquals("a\tb\nc", IssueExcelExporter.escape("a\tb\nc"));
        assertEquals("行1\n行2", IssueExcelExporter.escape("行1\r\n行2"), "CRLF 归一为 LF");
        assertEquals("&amp;&lt;&gt;", IssueExcelExporter.escape("&<>"));
    }

    @Test
    @DisplayName("列号映射：Z 之后进位成 AA")
    void mapsColumnRefs() {
        assertEquals("A1", IssueExcelExporter.ref(0, 1));
        assertEquals("O2", IssueExcelExporter.ref(14, 2));
        assertEquals("Z5", IssueExcelExporter.ref(25, 5));
        assertEquals("AA6", IssueExcelExporter.ref(26, 6));
        assertEquals("BA8", IssueExcelExporter.ref(52, 8));
    }

    @Test
    @DisplayName("正文里的配图引用被解析出来（去重、只认自家地址）")
    void parsesImageNames() {
        String body = "![](/api/v1/git-issue-images/a.png)"
                + "![](/api/v1/git-issue-images/a.png)"
                + "![x](https://other/b.png)"
                + "![](/api/v1/git-issue-images/c.webp)";

        assertEquals(List.of("a.png", "c.webp"), IssueExcelExporter.imageNames(body));
        assertTrue(IssueExcelExporter.imageNames(null).isEmpty());
    }

    @Test
    @DisplayName("图片类型按文件头判定，不信后缀")
    void detectsImageTypeByHeader() {
        assertEquals("image/png", IssueExcelExporter.contentTypeOf(PNG_BYTES));
        assertNull(IssueExcelExporter.contentTypeOf(WEBP_BYTES));
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0};
        assertEquals("image/jpeg", IssueExcelExporter.contentTypeOf(jpeg));
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a'};
        assertEquals("image/gif", IssueExcelExporter.contentTypeOf(gif));
        assertNull(IssueExcelExporter.contentTypeOf(null));
    }

    @Test
    @DisplayName("空任务也产出三张合法表")
    void rendersEmptyWorkbook() throws IOException {
        Map<String, String> parts = unzip(render(List.of(), Map.of(), null));

        for (int i = 1; i <= 3; i++) {
            String sheet = parts.get("xl/worksheets/sheet" + i + ".xml");
            assertNotNull(sheet, "缺 sheet" + i);
            assertEquals(1, sheet.split("<row ", -1).length - 1, "空导出只应有表头");
        }
    }

    @Test
    @DisplayName("下载文件名带时间戳")
    void buildsFileName() {
        assertEquals("tasks-20261119-2057.xlsx",
                IssueExcelExporter.fileName(Instant.parse("2026-11-19T12:57:00Z")));
    }
}
