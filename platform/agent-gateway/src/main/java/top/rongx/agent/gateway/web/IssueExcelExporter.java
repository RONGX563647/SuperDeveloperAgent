package top.rongx.agent.gateway.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;

/**
 * 任务导出为 .xlsx：三张工作表（任务 / 评论 / 图片），配图以真实图片对象嵌入。
 *
 * <p><b>为什么手写 OOXML 而不引 POI</b>：整个工程此前没有任何 Excel 依赖，为一张表格
 * 引入 POI（含传递依赖 10MB+）不划算，离线构建环境还要额外预热。xlsx 本质是 zip +
 * 一组固定 XML 部件，这里用 JDK 自带的 {@link ZipOutputStream} 直接拼，零依赖，
 * 而且测试能把生成物解压回来看内容。
 *
 * <p><b>图片为什么单独一张表</b>：塞进任务行会把行高撑爆、列宽失衡；单列成表后每行
 * 「编码 / 标题 / 文件名 / 说明 + 缩略图」，既能看内容又不破坏主表可读性。
 * 不支持的格式（Excel 不认 webp）与缺失文件只登记文件名并注明原因，不让整份导出失败。
 */
final class IssueExcelExporter {

    private static final String XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String NS_MAIN =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String NS_PKG_REL =
            "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String NS_DRAWING =
            "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String NS_A =
            "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("+08:00"));

    /** 样式索引：与 styles.xml 的 cellXfs 顺序一致。 */
    private static final int STYLE_BODY = 0;
    private static final int STYLE_HEADER = 1;
    private static final int STYLE_WRAP = 2;

    /** 单元格文本上限：Excel 单格最多 32767 字符，超了整份文件会被判损坏。 */
    private static final int MAX_CELL_CHARS = 32_000;

    /** 正文里的配图引用：{@code ![](/api/v1/git-issue-images/xxx.png)}。 */
    private static final Pattern IMAGE_REF =
            Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)\\)");
    private static final String IMAGE_URL_PREFIX = "/api/v1/git-issue-images/";

    /** 嵌入图片的固定外框（像素）：等比缩放到这个方框内。 */
    private static final int IMAGE_BOX_PX = 140;

    /** 导出输入：任务、评论读取函数、配图目录。 */
    record ExportData(List<GitIssue> issues,
                      Function<String, List<GitIssueComment>> comments,
                      Path imageDir) {
    }

    /** 一列：标题、宽度、取值、单元格样式。 */
    private record Column(String title, int width, Function<GitIssue, String> pick, int style) {

        Column(String title, int width, Function<GitIssue, String> pick) {
            this(title, width, pick, STYLE_BODY);
        }
    }

    /** 图片表的一行；{@code data == null} 表示不可嵌入（缺失/格式不支持/路径非法）。 */
    private record ImageRow(String code, String title, String fileName, String note,
                            byte[] data, String extension) {
    }

    private static final String COL_COMMENTS = "评论数";
    private static final String COL_IMAGES = "图片数";

    private static final List<Column> TASK_COLUMNS = List.of(
            new Column("编码", 14, GitIssue::code),
            new Column("类型", 8, i -> label(i.kind(), "BUG")),
            new Column("标题", 38, GitIssue::title),
            new Column("状态", 10, i -> "OPEN".equalsIgnoreCase(i.state()) ? "待处理" : "已完成"),
            new Column("优先级", 9, i -> label(i.priority(), "P2")),
            new Column("版本", 12, GitIssue::version),
            new Column("负责人", 12, GitIssue::assignee),
            new Column("标签", 16, GitIssue::labels),
            // 正文：需求/缺陷的实际内容，导出到 Excel 主要就是为了看它。
            // 用自动换行样式，否则多行正文会挤成一条看不见的横线。
            new Column("描述正文", 60, GitIssue::body, STYLE_WRAP),
            new Column(COL_COMMENTS, 9, null),
            new Column(COL_IMAGES, 9, null),
            new Column("远端", 18, IssueExcelExporter::remoteRef),
            new Column("同步", 10, GitIssue::syncStatus),
            new Column("创建时间", 17, i -> time(i.createdAt())),
            new Column("更新时间", 17, i -> time(i.updatedAt())));

    private IssueExcelExporter() {
    }

    /**
     * 渲染 xlsx 字节。
     *
     * @param data 任务、评论读取函数与配图目录；空任务也生成只有表头的文件
     */
    static byte[] render(ExportData data) throws IOException {
        List<GitIssue> issues = data.issues() == null ? List.of() : data.issues();
        Map<String, List<GitIssueComment>> comments = collectComments(issues, data.comments());
        Map<String, Integer> imageCounts = countImages(issues);
        List<ImageRow> images = collectImages(issues, data.imageDir());
        // 只有真的嵌入了图片才写 drawing 部件：否则 sheet 会引用不存在的部件，
        // Excel 打开时报"内容有问题"（webp / 文件丢失这两种情况很常见）。
        boolean hasDrawing = images.stream().anyMatch(image -> image.data() != null);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypes(images, hasDrawing));
            put(zip, "_rels/.rels", rootRels());
            put(zip, "xl/workbook.xml", workbook());
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels());
            put(zip, "xl/styles.xml", styles());
            put(zip, "xl/worksheets/sheet1.xml",
                    taskSheet(issues, comments, imageCounts));
            put(zip, "xl/worksheets/sheet2.xml", commentSheet(issues, comments));
            put(zip, "xl/worksheets/sheet3.xml", imageSheet(images, hasDrawing));
            if (hasDrawing) {
                put(zip, "xl/worksheets/_rels/sheet3.xml.rels", sheetRels());
                put(zip, "xl/drawings/drawing1.xml", drawing(images));
                put(zip, "xl/drawings/_rels/drawing1.xml.rels", drawingRels(images));
                for (int i = 0; i < images.size(); i++) {
                    ImageRow image = images.get(i);
                    if (image.data() == null) {
                        continue;   // 不可嵌入的行只留在表里做说明
                    }
                    // 文件名沿用原索引，与 drawingRels 的 Target 严格对应
                    putBytes(zip, "xl/media/image" + (i + 1) + "." + image.extension(),
                            image.data());
                }
            }
        }
        return buffer.toByteArray();
    }

    // ---------------------------------------------------------------- 数据准备

    private static Map<String, List<GitIssueComment>> collectComments(
            List<GitIssue> issues, Function<String, List<GitIssueComment>> loader) {
        Map<String, List<GitIssueComment>> result = new LinkedHashMap<>();
        if (loader == null) {
            return result;
        }
        for (GitIssue issue : issues) {
            try {
                List<GitIssueComment> rows = loader.apply(issue.id());
                result.put(issue.id(), rows == null ? List.of() : rows);
            } catch (RuntimeException e) {
                // 单个任务的评论取不到不影响整份导出
                result.put(issue.id(), List.of());
            }
        }
        return result;
    }

    /** 从正文里抽出配图引用并读数；读不到或格式不支持时只登记说明。 */
    private static List<ImageRow> collectImages(List<GitIssue> issues, Path imageDir) {
        List<ImageRow> rows = new ArrayList<>();
        if (imageDir == null) {
            return rows;
        }
        for (GitIssue issue : issues) {
            for (String name : imageNames(issue.body())) {
                Path file = resolveImage(imageDir, name);
                if (file == null) {
                    rows.add(new ImageRow(issue.code(), issue.title(), name,
                            "文件名非法（不允许目录分隔或上跳），已跳过", null, null));
                    continue;
                }
                if (!Files.isRegularFile(file)) {
                    rows.add(new ImageRow(issue.code(), issue.title(), name,
                            "文件已不存在", null, null));
                    continue;
                }
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    String type = contentTypeOf(bytes);
                    if (type == null) {
                        rows.add(new ImageRow(issue.code(), issue.title(), name,
                                "Excel 不支持的格式（" + extension(name) + "），仅登记文件名",
                                null, null));
                        continue;
                    }
                    rows.add(new ImageRow(issue.code(), issue.title(), name, "",
                            bytes, extensionOf(type)));
                } catch (IOException e) {
                    rows.add(new ImageRow(issue.code(), issue.title(), name,
                            "读取失败：" + e.getMessage(), null, null));
                }
            }
        }
        return rows;
    }

    /** 正文里出现过的配图文件名（去重、保序，只认自家图片地址）。 */
    static List<String> imageNames(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = IMAGE_REF.matcher(body);
        while (matcher.find()) {
            String url = matcher.group(1);
            if (url.startsWith(IMAGE_URL_PREFIX)) {
                names.add(url.substring(IMAGE_URL_PREFIX.length()));
            }
        }
        return List.copyOf(names);
    }

    /** 只允许配图目录下的裸文件名：挡住 {@code ../} 与绝对路径。 */
    private static Path resolveImage(Path imageDir, String name) {
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        Path base = imageDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(name).normalize();
        return resolved.startsWith(base) && !resolved.equals(base) ? resolved : null;
    }

    private static Map<String, Integer> countImages(List<GitIssue> issues) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GitIssue issue : issues) {
            counts.put(issue.id(), imageNames(issue.body()).size());
        }
        return counts;
    }

    /** 按文件头判类型，不信任后缀：Excel 只认 png / jpeg / gif。 */
    static String contentTypeOf(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (startsWith(bytes, 0x89, 'P', 'N', 'G')) {
            return "image/png";
        }
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(bytes, 'G', 'I', 'F', '8')) {
            return "image/gif";
        }
        return null;
    }

    private static String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpeg";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != (prefix[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- 各工作表

    private static String taskSheet(List<GitIssue> issues,
                                    Map<String, List<GitIssueComment>> comments,
                                    Map<String, Integer> imageCounts) {
        StringBuilder xml = sheetHead(TASK_COLUMNS.stream().map(Column::width).toList());
        String[] headers = TASK_COLUMNS.stream().map(Column::title).toArray(String[]::new);
        xml.append("<row r=\"1\">");
        for (int i = 0; i < headers.length; i++) {
            cell(xml, ref(i, 1), headers[i], STYLE_HEADER);
        }
        xml.append("</row>");
        int row = 2;
        for (GitIssue issue : issues) {
            xml.append("<row r=\"").append(row).append("\">");
            for (int i = 0; i < TASK_COLUMNS.size(); i++) {
                Column column = TASK_COLUMNS.get(i);
                String value = switch (column.title()) {
                    case COL_COMMENTS -> String.valueOf(
                            comments.getOrDefault(issue.id(), List.of()).size());
                    case COL_IMAGES -> String.valueOf(imageCounts.getOrDefault(issue.id(), 0));
                    default -> column.pick() == null ? "" : column.pick().apply(issue);
                };
                cell(xml, ref(i, row), value == null ? "" : value, column.style());
            }
            xml.append("</row>");
            row++;
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    private static String commentSheet(List<GitIssue> issues,
                                       Map<String, List<GitIssueComment>> comments) {
        StringBuilder xml = sheetHead(List.of(14, 28, 14, 10, 58, 17));
        String[] headers = {"任务编码", "任务标题", "作者", "来源", "评论内容", "时间"};
        xml.append("<row r=\"1\">");
        for (int i = 0; i < headers.length; i++) {
            cell(xml, ref(i, 1), headers[i], STYLE_HEADER);
        }
        xml.append("</row>");
        int row = 2;
        for (GitIssue issue : issues) {
            for (GitIssueComment comment : comments.getOrDefault(issue.id(), List.of())) {
                xml.append("<row r=\"").append(row).append("\">");
                cell(xml, ref(0, row), issue.code(), STYLE_BODY);
                cell(xml, ref(1, row), issue.title(), STYLE_BODY);
                cell(xml, ref(2, row), comment.author(), STYLE_BODY);
                cell(xml, ref(3, row), comment.source(), STYLE_BODY);
                cell(xml, ref(4, row), comment.body(), STYLE_WRAP);
                cell(xml, ref(5, row), time(comment.createdAt()), STYLE_BODY);
                xml.append("</row>");
                row++;
            }
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    /** 图片表：每行「编码 / 标题 / 文件名 / 说明」，可嵌入的图片锚在 E 列。 */
    private static String imageSheet(List<ImageRow> images, boolean hasDrawing) {
        StringBuilder xml = sheetHead(List.of(14, 28, 26, 34, 24));
        String[] headers = {"任务编码", "任务标题", "文件名", "说明", "图片"};
        xml.append("<row r=\"1\">");
        for (int i = 0; i < headers.length; i++) {
            cell(xml, ref(i, 1), headers[i], STYLE_HEADER);
        }
        xml.append("</row>");
        int row = 2;
        for (ImageRow image : images) {
            xml.append("<row r=\"").append(row).append("\">");
            cell(xml, ref(0, row), image.code(), STYLE_BODY);
            cell(xml, ref(1, row), image.title(), STYLE_BODY);
            cell(xml, ref(2, row), image.fileName(), STYLE_BODY);
            cell(xml, ref(3, row), image.note() == null ? "" : image.note(), STYLE_WRAP);
            xml.append("</row>");
            row++;
        }
        xml.append("</sheetData>");
        if (hasDrawing) {
            xml.append("<drawing r:id=\"rId1\"/>");
        }
        return xml.append("</worksheet>").toString();
    }

    private static StringBuilder sheetHead(List<Integer> widths) {
        StringBuilder xml = new StringBuilder(16 * 1024);
        xml.append(XML_HEAD)
                .append("<worksheet xmlns=\"").append(NS_MAIN)
                .append("\" xmlns:r=\"").append(NS_REL).append("\">");
        xml.append("<sheetViews><sheetView workbookViewId=\"0\">")
                .append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("</sheetView></sheetViews>");
        xml.append("<cols>");
        for (int i = 0; i < widths.size(); i++) {
            xml.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                    .append("\" width=\"").append(widths.get(i))
                    .append("\" customWidth=\"1\"/>");
        }
        xml.append("</cols><sheetData>");
        return xml;
    }

    // ---------------------------------------------------------------- drawing

    /** 每张可嵌入图片一个 oneCellAnchor，锚在图片表 E 列对应行。 */
    private static String drawing(List<ImageRow> images) {
        StringBuilder xml = new StringBuilder(8 * 1024);
        xml.append(XML_HEAD).append("<xdr:wsDr xmlns:xdr=\"").append(NS_DRAWING)
                .append("\" xmlns:a=\"").append(NS_A)
                .append("\" xmlns:r=\"").append(NS_REL).append("\">");
        int embedIndex = 0;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).data() == null) {
                continue;
            }
            embedIndex++;
            // 行号：表头占第 1 行，图片行 index i 对应 Excel 第 i+1 行（0 基）
            int rowIndex = i + 1;
            xml.append("<xdr:oneCellAnchor>")
                    .append("<xdr:from><xdr:col>4</xdr:col><xdr:colOff>0</xdr:colOff>")
                    .append("<xdr:row>").append(rowIndex)
                    .append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>")
                    .append("<xdr:ext cx=\"").append(px(IMAGE_BOX_PX))
                    .append("\" cy=\"").append(px(IMAGE_BOX_PX)).append("\"/>")
                    .append("<xdr:pic><xdr:nvPicPr>")
                    .append("<xdr:cNvPr id=\"").append(embedIndex).append("\" name=\"image")
                    .append(embedIndex).append("\"/>")
                    .append("<xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr>")
                    .append("</xdr:nvPicPr>")
                    .append("<xdr:blipFill><a:blip r:embed=\"rId").append(embedIndex)
                    .append("\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>")
                    .append("<xdr:spPr><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr>")
                    .append("</xdr:pic><xdr:clientData/></xdr:oneCellAnchor>");
        }
        return xml.append("</xdr:wsDr>").toString();
    }

    /** 像素转 EMU（1px = 9525 EMU）。 */
    private static long px(int pixels) {
        return (long) pixels * 9525L;
    }

    private static String drawingRels(List<ImageRow> images) {
        StringBuilder xml = new StringBuilder(2 * 1024);
        xml.append(XML_HEAD).append("<Relationships xmlns=\"").append(NS_PKG_REL).append("\">");
        int embedIndex = 0;
        for (int i = 0; i < images.size(); i++) {
            ImageRow image = images.get(i);
            if (image.data() == null) {
                continue;
            }
            embedIndex++;
            xml.append("<Relationship Id=\"rId").append(embedIndex)
                    .append("\" Type=\"").append(NS_REL).append("/image\"")
                    .append(" Target=\"../media/image").append(i + 1)
                    .append('.').append(image.extension()).append("\"/>");
        }
        return xml.append("</Relationships>").toString();
    }

    private static String sheetRels() {
        return XML_HEAD
                + "<Relationships xmlns=\"" + NS_PKG_REL + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + NS_REL + "/drawing\""
                + " Target=\"../drawings/drawing1.xml\"/>"
                + "</Relationships>";
    }

    // ---------------------------------------------------------------- 包结构

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        putBytes(zip, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static String contentTypes(List<ImageRow> images, boolean hasDrawing) {
        Set<String> extensions = new LinkedHashSet<>();
        for (ImageRow image : images) {
            if (image.data() != null) {
                extensions.add(image.extension());
            }
        }
        StringBuilder xml = new StringBuilder();
        xml.append(XML_HEAD)
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        for (String ext : extensions) {
            xml.append("<Default Extension=\"").append(ext).append("\" ContentType=\"")
                    .append("image/" + ("jpeg".equals(ext) ? "jpeg" : ext)).append("\"/>");
        }
        // workbook 部件的 Override 必须在：读取器（Excel / openpyxl）靠内容类型定位工作簿，
        // 少了它整份文件被判"没有有效工作簿"，只补 worksheet 是没用的。
        xml.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        for (int i = 1; i <= 3; i++) {
            xml.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        xml.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        if (hasDrawing) {
            xml.append("<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>");
        }
        return xml.append("</Types>").toString();
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
                + "<sheets>"
                + "<sheet name=\"任务\" sheetId=\"1\" r:id=\"rId1\"/>"
                + "<sheet name=\"评论\" sheetId=\"2\" r:id=\"rId2\"/>"
                + "<sheet name=\"图片\" sheetId=\"3\" r:id=\"rId3\"/>"
                + "</sheets></workbook>";
    }

    private static String workbookRels() {
        return XML_HEAD
                + "<Relationships xmlns=\"" + NS_PKG_REL + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + NS_REL + "/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"" + NS_REL + "/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"" + NS_REL + "/worksheet\" Target=\"worksheets/sheet3.xml\"/>"
                + "<Relationship Id=\"rId4\" Type=\"" + NS_REL + "/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>";
    }

    /** 三个 xf：0 = 正文，1 = 加粗表头，2 = 自动换行（正文/评论内容列）。 */
    private static String styles() {
        // cellStyles 是 Excel 的"常规"命名样式：不声明它，openpyxl 之类读取器会警告
        // "Workbook contains no default style"，某些 Excel 版本也会提示修复。
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
                + "<cellXfs count=\"3\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\">"
                + "<alignment vertical=\"top\" wrapText=\"1\"/></xf>"
                + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";
    }

    // ---------------------------------------------------------------- 单元格

    /** 内联字符串单元格；style 0=正文 1=表头加粗 2=自动换行。 */
    private static void cell(StringBuilder xml, String ref, String value, int style) {
        xml.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"");
        if (style != STYLE_BODY) {
            xml.append(" s=\"").append(style).append('"');
        }
        xml.append("><is><t xml:space=\"preserve\">")
                .append(clamp(escape(value)))
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
     * XML 文本转义 + 行尾归一。
     *
     * <p>另外剔除 XML 1.0 不允许的控制字符（0x00–0x08 等）：从终端/日志粘进来的
     * 控制符会让 Excel 判文件损坏，剔除比报错更友好。
     */
    static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(normalized.length() + 16);
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> {
                    if (c == '\t' || c == '\n') {
                        out.append(c);
                    } else if (c >= 0x20) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** 单元格字符上限保护：超出 Excel 限制会让整份文件打不开。 */
    static String clamp(String text) {
        return text.length() <= MAX_CELL_CHARS
                ? text
                : text.substring(0, MAX_CELL_CHARS) + "…（已截断）";
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

    /** 便于测试断言：任务表头（顺序即列顺序）。 */
    static List<String> headers() {
        List<String> titles = new ArrayList<>();
        for (Column column : TASK_COLUMNS) {
            titles.add(column.title());
        }
        return List.copyOf(titles);
    }

    /** 便于测试断言：工作表名（顺序即 sheet1..3）。 */
    static List<String> sheetNames() {
        return List.of("任务", "评论", "图片");
    }
}
