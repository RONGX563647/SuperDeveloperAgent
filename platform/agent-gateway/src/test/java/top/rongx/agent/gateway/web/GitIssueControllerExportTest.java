package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;
import top.rongx.agent.domain.gitissue.GitIssueService;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * 导出接口的契约：响应头能被浏览器当附件、非法入参给可读 400、字节是合法 xlsx。
 *
 * <p>不启 Spring：导出逻辑本身在 {@link IssueExcelExporter} 已单测，这里只钉控制器这层
 * 容易写错的细节（Content-Disposition / Content-Type / 上限保护）。
 */
class GitIssueControllerExportTest {

    /** 固定登录态：绕过 Sa-Token 静态调用。 */
    private static final class StubUser extends CurrentUser {
        @Override
        public String requireUserId() {
            return "u1";
        }
    }

    /** 只实现导出用得到的两个方法，其余抛错避免被误调用。 */
    private static final class StubService implements GitIssueService {
        private final Map<String, GitIssue> rows = new LinkedHashMap<>();

        void put(GitIssue issue) {
            rows.put(issue.id(), issue);
        }

        @Override
        public GitIssue get(String userId, String id) {
            GitIssue found = rows.get(id);
            if (found == null) {
                throw new IllegalStateException("任务不存在：" + id);
            }
            return found;
        }

        @Override
        public List<GitIssue> list(String userId, String provider, String state, String keyword) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public GitIssue create(String userId, GitIssue.Draft draft) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GitIssue update(String userId, String id, GitIssue.Draft draft) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String userId, String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GitIssue setState(String userId, String id, String state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<GitIssueComment> comments(String userId, String issueId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GitIssueComment comment(String userId, String issueId, String body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteComment(String userId, String issueId, String commentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GitIssue sync(String userId, String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long openCount(String userId) {
            return rows.size();
        }
    }

    private static GitIssue issue(String id, String code, String title) {
        return new GitIssue(id, code, "u1", "p1", "", "", "", title, "", "OPEN", "BUG", "P1",
                "", "", "PLATFORM", "LOCAL", "", "", 1_700_000_000_000L, 1_700_000_000_000L, "0.1.2");
    }

    private GitIssueController controller(StubService service) {
        return new GitIssueController(service, new StubUser(), null, "./data/workspaces");
    }

    private static Map<String, String> unzip(byte[] xlsx) throws Exception {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    @Test
    @DisplayName("正常导出：xlsx 内容类型 + 附件文件名 + 字节可解压")
    void exportsAsAttachment() throws Exception {
        StubService service = new StubService();
        service.put(issue("i1", "BUG-1", "第一条"));
        service.put(issue("i2", "FEAT-2", "第二条"));

        ResponseEntity<byte[]> response = controller(service)
                .export(Map.of("ids", List.of("i1", "i2")));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                response.getHeaders().getContentType());
        String disposition = response.getHeaders().getFirst("Content-Disposition");
        assertTrue(disposition != null && disposition.startsWith("attachment; filename=\"tasks-"),
                "必须是附件下载，实际：" + disposition);
        assertTrue(disposition.endsWith(".xlsx\""), disposition);

        Map<String, String> parts = unzip(response.getBody());
        assertTrue(parts.containsKey("xl/worksheets/sheet1.xml"));
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("BUG-1"));
        assertTrue(sheet.contains("FEAT-2"));
        assertTrue(sheet.contains("第一条"));
    }

    @Test
    @DisplayName("ids 为空：400 并说明要先筛选")
    void rejectsEmptyIds() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller(new StubService()).export(Map.of("ids", List.of())));

        assertEquals(400, error.getStatusCode().value());
        assertTrue(String.valueOf(error.getReason()).contains("ids 不能为空"));
    }

    @Test
    @DisplayName("超过上限：400 且提示缩小范围，避免一次拉爆内存")
    void rejectsTooManyIds() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            ids.add("id-" + i);
        }

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller(new StubService()).export(Map.of("ids", ids)));

        assertEquals(400, error.getStatusCode().value());
        assertTrue(String.valueOf(error.getReason()).contains("最多导出"));
    }

    @Test
    @DisplayName("单个任务取不到时跳过，不让整次导出失败")
    void skipsMissingIssue() throws Exception {
        StubService service = new StubService();
        service.put(issue("i1", "BUG-1", "保留"));
        // 用反射塞一个"取不到"的假 id：get 会抛，控制器应跳过
        ResponseEntity<byte[]> response = controller(service)
                .export(Map.of("ids", List.of("i1", "ghost")));

        assertEquals(200, response.getStatusCode().value());
        String sheet = unzip(response.getBody()).get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("BUG-1"));
        assertEquals(2, sheet.split("<row ", -1).length - 1, "表头 + 1 条（ghost 被跳过）");
    }
}
