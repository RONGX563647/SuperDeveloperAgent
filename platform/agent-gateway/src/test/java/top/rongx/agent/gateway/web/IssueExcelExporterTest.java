package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.gitissue.GitIssue;

/**
 * 任务导出 xlsx 的契约。
 *
 * <p>手写 OOXML 的风险全在"Excel 能不能打开"上，而这件事没法靠肉眼看字符串判断，
 * 所以这里把生成的字节**解压回来看**：部件齐不齐、内容对不对、特殊字符有没有转义。
 */
class IssueExcelExporterTest {

    private static GitIssue issue(String code, String title, String version) {
        return new GitIssue(
                "id-" + code, code, "u1", "p1", "GITEE", "acme/demo", "12",
                title, "正文", "OPEN", "BUG", "P0", "backend,ui", "https://example/1",
                "PLATFORM", "SYNCED", "", "alice", 1_700_000_000_000L, 1_700_000_600_000L,
                version);
    }

    /** 解压成 名称 → 文本。 */
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

    @Test
    @DisplayName("xlsx 部件齐全：缺任何一个 Excel 都会判文件损坏")
    void hasAllRequiredParts() throws IOException {
        Map<String, String> parts = unzip(IssueExcelExporter.render(List.of(issue("BUG-1", "标题", ""))));

        assertEquals(List.of(
                        "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                        "xl/_rels/workbook.xml.rels", "xl/styles.xml",
                        "xl/worksheets/sheet1.xml"),
                List.copyOf(parts.keySet()));
        assertTrue(parts.get("[Content_Types].xml").contains("spreadsheetml.sheet.main+xml"));
        assertTrue(parts.get("xl/workbook.xml").contains("name=\"任务\""));
    }

    @Test
    @DisplayName("表头与数据行都写对了")
    void writesHeaderAndRows() throws IOException {
        String sheet = unzip(IssueExcelExporter.render(
                List.of(issue("BUG-7", "登录页偶发 500", "0.1.10"))))
                .get("xl/worksheets/sheet1.xml");

        for (String header : IssueExcelExporter.headers()) {
            assertTrue(sheet.contains(header), "缺少表头：" + header);
        }
        assertTrue(sheet.contains("BUG-7"));
        assertTrue(sheet.contains("登录页偶发 500"));
        assertTrue(sheet.contains("0.1.10"));
        assertTrue(sheet.contains("alice"));
        // 状态与优先级按界面口径翻译，不直接吐 OPEN/P0
        assertTrue(sheet.contains("待处理"), "状态应显示中文");
        assertTrue(sheet.contains("acme/demo#12"), "远端引用应带仓库名");
        // 两行：表头 + 1 条数据
        assertEquals(2, sheet.split("<row ", -1).length - 1);
    }

    @Test
    @DisplayName("特殊字符必须转义，否则文件打不开")
    void escapesXmlSpecialChars() throws IOException {
        String sheet = unzip(IssueExcelExporter.render(
                List.of(issue("BUG-9", "A & B <script>alert(1)</script> \"引号\"", ""))))
                .get("xl/worksheets/sheet1.xml");

        assertTrue(sheet.contains("A &amp; B &lt;script&gt;"), "& 与尖括号必须转义");
        assertFalse(sheet.contains("<script>"), "不得把原始标签写进 XML");
        assertTrue(sheet.contains("&quot;引号&quot;"));
    }

    @Test
    @DisplayName("空列表也产出只有表头的合法文件")
    void rendersHeaderOnlyWhenNoRows() throws IOException {
        String sheet = unzip(IssueExcelExporter.render(List.of())).get("xl/worksheets/sheet1.xml");

        assertEquals(1, sheet.split("<row ", -1).length - 1, "空导出应只有表头行");
        assertTrue(sheet.contains("编码"));
    }

    @Test
    @DisplayName("控制字符被剔除：终端粘贴进来的 0x00–0x1F 会让 Excel 判损坏")
    void stripsIllegalControlChars() {
        assertEquals("ab", IssueExcelExporter.escape("a\u0000\u0001b"));
        assertEquals("a\tb\nc", IssueExcelExporter.escape("a\tb\nc"), "制表与换行要保留");
        assertEquals("&amp;&lt;&gt;", IssueExcelExporter.escape("&<>"));
    }

    @Test
    @DisplayName("列号映射：Z 之后要进位成 AA，否则 27 列以上会串列")
    void mapsColumnRefs() {
        assertEquals("A1", IssueExcelExporter.ref(0, 1));
        assertEquals("L2", IssueExcelExporter.ref(11, 2));
        assertEquals("Z5", IssueExcelExporter.ref(25, 5));
        assertEquals("AA6", IssueExcelExporter.ref(26, 6), "第 27 列必须进位");
        assertEquals("AB7", IssueExcelExporter.ref(27, 7));
        assertEquals("BA8", IssueExcelExporter.ref(52, 8));
    }

    @Test
    @DisplayName("列标题数量与导出列一致，供界面/文档引用")
    void exposesHeaders() {
        assertEquals(List.of("编码", "类型", "标题", "状态", "优先级", "版本",
                        "负责人", "标签", "远端", "同步", "创建时间", "更新时间"),
                IssueExcelExporter.headers());
    }

    @Test
    @DisplayName("下载文件名带时间戳，便于多次导出区分")
    void buildsFileName() {
        String name = IssueExcelExporter.fileName(Instant.parse("2026-11-19T12:57:00Z"));
        assertEquals("tasks-20261119-2057.xlsx", name);
    }
}
