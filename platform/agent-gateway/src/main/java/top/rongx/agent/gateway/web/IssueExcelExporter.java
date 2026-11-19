package top.rongx.agent.gateway.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import top.rongx.agent.domain.gitissue.GitIssue;

/**
 * 任务列表导出为 .xlsx（单表）。
 *
 * <p><b>为什么手写 OOXML 而不引 POI</b>：整个工程此前没有任何 Excel 依赖，
 * 为一张简单表格引入 POI（含传递依赖约 10MB+）不划算，且离线构建环境下
 * 新依赖还需要额外预热。xlsx 本质是个 zip + 几个固定 XML 部件，这里用 JDK 自带的
 * {@link ZipOutputStream} 直接拼出来，零依赖、可单测（测试里能把它解压回来看内容）。
 *
 * <p>覆盖的能力刚好够用：表头加粗、首行冻结、列宽、内联字符串。不生成
 * 共享字符串表（sharedStrings）——行数少时内联更简单，Excel 也完全接受。
 *
 * <p>所有写入文本都过 {@link #escape}：任务标题里常出现 {@code &}、{@code <} 等字符，
 * 不转义会直接产出打不开的损坏文件。
 */
final class IssueExcelExporter {

    private static final String XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String NS_MAIN =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String NS_PKG_REL =
            "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("+08:00"));

    /** 表头与取值一一对应。 */
    private record Column(String title, int width, java.util.function.Function<GitIssue, String> pick) {
    }

    private static final List<Column> COLUMNS = List.of(
            new Column("编码", 14, GitIssue::code),
            new Column("类型", 8, i -> label(i.kind(), "BUG")),
            new Column("标题", 46, GitIssue::title),
            new Column("状态", 10, i -> "OPEN".equalsIgnoreCase(i.state()) ? "待处理" : "已完成"),
            new Column("优先级", 9, i -> label(i.priority(), "P2")),
            new Column("版本", 12, GitIssue::version),
            new Column("负责人", 12, GitIssue::assignee),
            new Column("标签", 18, GitIssue::labels),
            new Column("远端", 20, i -> remoteRef(i)),
            new Column("同步", 10, GitIssue::syncStatus),
            new Column("创建时间", 18, i -> time(i.createdAt())),
            new Column("更新时间", 18, i -> time(i.updatedAt())));

    private IssueExcelExporter() {
    }

    /**
     * 渲染 xlsx 字节。
     *
     * @param issues 行数据；空列表也生成只有表头的文件（用户会想知道"确实没有"）
     */
    static byte[] render(List<GitIssue> issues) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(16 * 1024);
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypes());
            put(zip, "_rels/.rels", rootRels());
            put(zip, "xl/workbook.xml", workbook());
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels());
            put(zip, "xl/styles.xml", styles());
            put(zip, "xl/worksheets/sheet1.xml", sheet(issues));
        }
        return buffer.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes() {
        return XML_HEAD
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "</Types>";
    }

    private static String rootRels() {
        return XML_HEAD
                + "<Relationships xmlns=\"" + NS_PKG_REL + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + NS_REL + "/officeDocument\""
                + " Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private static String workbook() {
        return XML_HEAD
                + "<workbook xmlns=\"" + NS_MAIN + "\" xmlns:r=\"" + NS_REL + "\">"
                + "<sheets><sheet name=\"任务\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "</workbook>";
    }

    private static String workbookRels() {
        return XML_HEAD
                + "<Relationships xmlns=\"" + NS_PKG_REL + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + NS_REL + "/worksheet\""
                + " Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"" + NS_REL + "/styles\""
                + " Target=\"styles.xml\"/>"
                + "</Relationships>";
    }

    /** 两个 xf：0 = 正文，1 = 加粗表头。 */
    private static String styles() {
        return XML_HEAD
                + "<styleSheet xmlns=\"" + NS_MAIN + "\">"
                + "<fonts count=\"2\">"
                + "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "</fonts>"
                + "<fills count=\"2\">"
                + "<fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill>"
                + "</fills>"
                + "<borders count=\"1\"><border/></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "</cellXfs>"
                + "</styleSheet>";
    }

    private static String sheet(List<GitIssue> issues) {
        StringBuilder xml = new StringBuilder(8 * 1024);
        xml.append(XML_HEAD).append("<worksheet xmlns=\"").append(NS_MAIN).append("\">");
        // 冻结首行：任务多时滚动仍能看到表头
        xml.append("<sheetViews><sheetView workbookViewId=\"0\">")
                .append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("</sheetView></sheetViews>");
        xml.append("<cols>");
        for (int i = 0; i < COLUMNS.size(); i++) {
            xml.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                    .append("\" width=\"").append(COLUMNS.get(i).width())
                    .append("\" customWidth=\"1\"/>");
        }
        xml.append("</cols>");
        xml.append("<sheetData>");
        xml.append("<row r=\"1\">");
        for (int i = 0; i < COLUMNS.size(); i++) {
            cell(xml, ref(i, 1), COLUMNS.get(i).title(), 1);
        }
        xml.append("</row>");
        int rowNumber = 2;
        for (GitIssue issue : issues) {
            xml.append("<row r=\"").append(rowNumber).append("\">");
            for (int i = 0; i < COLUMNS.size(); i++) {
                String value = COLUMNS.get(i).pick().apply(issue);
                cell(xml, ref(i, rowNumber), value == null ? "" : value, 0);
            }
            xml.append("</row>");
            rowNumber++;
        }
        xml.append("</sheetData></worksheet>");
        return xml.toString();
    }

    /** 内联字符串单元格；style 0=正文 1=表头加粗。 */
    private static void cell(StringBuilder xml, String ref, String value, int style) {
        xml.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"");
        if (style != 0) {
            xml.append(" s=\"").append(style).append('"');
        }
        xml.append("><is><t xml:space=\"preserve\">")
                .append(escape(value))
                .append("</t></is></c>");
    }

    /** 列号转 A/B/.../AA 并拼行号。包内可见：进位逻辑要被单测覆盖。 */
    static String ref(int columnIndex, int rowNumber) {
        StringBuilder letters = new StringBuilder();
        int index = columnIndex;
        while (true) {
            letters.insert(0, (char) ('A' + index % 26));
            index = index / 26 - 1;
            if (index < 0) {
                break;
            }
        }
        return letters + Integer.toString(rowNumber);
    }

    /**
     * XML 文本转义。
     *
     * <p>另外剔除 XML 1.0 不允许的控制字符（0x00–0x08 等）：任务描述里粘进来的
     * 终端控制符会让 Excel 判文件损坏，剔除比报错更友好。
     */
    static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (c == '\t' || c == '\n' || c == '\r') {
                        out.append(c);
                    } else if (c >= 0x20) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String label(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String time(long epochMillis) {
        return epochMillis <= 0 ? "" : TIME_FMT.format(Instant.ofEpochMilli(epochMillis));
    }

    /** 远端引用：有绑定显示 repo#number，仅本地显示提示。 */
    private static String remoteRef(GitIssue issue) {
        if (issue.number() == null || issue.number().isBlank()) {
            return "仅本地";
        }
        String repo = issue.repo() == null || issue.repo().isBlank() ? "" : issue.repo() + "#";
        return repo + issue.number();
    }

    /** 供控制器拼文件名：tasks-20261119-2057.xlsx。 */
    static String fileName(Instant now) {
        return "tasks-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .withZone(ZoneId.of("+08:00")).format(now) + ".xlsx";
    }

    /** 便于测试断言：导出列标题（顺序即列顺序）。 */
    static List<String> headers() {
        List<String> titles = new ArrayList<>();
        for (Column column : COLUMNS) {
            titles.add(column.title());
        }
        return List.copyOf(titles);
    }
}
