package top.rongx.agent.gateway.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;
import top.rongx.agent.domain.gitissue.GitIssueService;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.gateway.gitissue.GitIssueServiceImpl;

/**
 * 对话页「任务」Tab 的 Issue 任务接口（与 Agent 工具共用同一份数据）。
 *
 * <p>列表 / 详情 / 新建 / 编辑 / 关闭重开 / 评论 / 删除 / 手动同步。
 * 已绑定远端的任务由 {@link GitIssueService} 按集成配置双向同步。
 */
@RestController
@RequestMapping("/api/v1/git-issues")
public class GitIssueController {

    private static final Logger log = LoggerFactory.getLogger(GitIssueController.class);
    /** 单次导出上限：防止一次把整库任务拉进内存。 */
    private static final int EXPORT_MAX_IDS = 2000;

    private final GitIssueService issues;
    private final CurrentUser currentUser;
    private final ProjectRepoLocator repoLocator;
    /** 任务配图落盘目录：与工作区数据同源，不污染用户仓库。 */
    private final Path imageDir;
    private static final long IMAGE_MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> IMAGE_EXTS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    public GitIssueController(GitIssueService issues, CurrentUser currentUser,
                              ProjectRepoLocator repoLocator,
                              @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.issues = issues;
        this.currentUser = currentUser;
        this.repoLocator = repoLocator;
        this.imageDir = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("issues").resolve("images");
    }

    @GetMapping
    public List<GitIssue> list(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String keyword) {
        return issues.list(currentUser.requireUserId(), provider, state, keyword);
    }

    /**
     * 把「当前筛选出的任务」导出为 .xlsx。
     *
     * <p>用 POST 接收 id 列表而不是把筛选条件搬到后端重算：类型 / 优先级 / 版本 / 排序
     * 都在前端过滤（见 {@code ChatIssuesView}），后端重算等于把同一套规则写两份，
     * 迟早出现"界面看到 20 条、导出 23 条"。这里只认 id，导出内容与用户所见严格一致。
     *
     * <p>逐条 {@code get} 取而不是新增批量仓储方法：导出是低频操作，任务量在几百量级，
     * 本地库单次读取是微秒级，为此改动领域接口不划算。超过 {@value #EXPORT_MAX_IDS} 条
     * 直接拒绝，避免一次拉爆内存。
     *
     * @param body {@code {"ids": ["...", "..."]}}
     * @return xlsx 字节流（附件下载）
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody Map<String, Object> body) {
        String userId = currentUser.requireUserId();
        List<String> ids = new ArrayList<>();
        Object raw = body == null ? null : body.get("ids");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    ids.add(String.valueOf(item));
                }
            }
        }
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids 不能为空：请先筛选出要导出的任务");
        }
        if (ids.size() > EXPORT_MAX_IDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "一次最多导出 " + EXPORT_MAX_IDS + " 条，请先缩小筛选范围");
        }
        List<GitIssue> rows = new ArrayList<>(ids.size());
        for (String id : ids) {
            try {
                rows.add(issues.get(userId, id));
            } catch (RuntimeException e) {
                // 单个任务取不到（被并发删除等）不该让整次导出失败
                log.debug("导出跳过任务 {}：{}", id, e.getMessage());
            }
        }
        byte[] xlsx;
        try {
            // 评论与配图一并导出：只有元数据的表格说不清"这条缺陷到底发生了什么"
            xlsx = IssueExcelExporter.render(new IssueExcelExporter.ExportData(
                    rows, issueId -> issues.comments(userId, issueId), imageDir));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "生成 Excel 失败：" + e.getMessage(), e);
        }
        String fileName = IssueExcelExporter.fileName(Instant.now());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(xlsx.length)
                .body(xlsx);
    }

    @GetMapping("/count")
    public Map<String, Object> count() {
        return Map.of("open", issues.openCount(currentUser.requireUserId()));
    }

    @GetMapping("/{id}")
    public GitIssue get(@PathVariable String id) {
        return issues.get(currentUser.requireUserId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GitIssue create(@RequestBody Map<String, Object> body) {
        return issues.create(currentUser.requireUserId(), draft(body, null));
    }

    /**
     * 更新任务：只改请求体里真正出现的字段。
     *
     * <p>为什么按 key 判断而不是按空串：前端与 Agent 都做部分更新（只改版本号、只改标签），
     * 把缺失字段当成空串会把标题/标签一起清空——这正是「版本号写不进、标签读不出」的根。
     */
    @PutMapping("/{id}")
    public GitIssue update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String userId = currentUser.requireUserId();
        GitIssue current = issues.get(userId, id);
        return issues.update(userId, id, draft(body, current));
    }

    /**
     * 版本号候选项：当前分支推导的建议值 + 仓库现有标签。
     *
     * <p>建任务时 bug/feat 都要关联版本，UI 与 Agent 需要同一份候选来源，
     * 否则只能让用户手输一个必然写错的版本号。
     */
    @GetMapping("/versions")
    public Map<String, Object> versions(@RequestParam(required = false) String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Map.of("branch", "", "suggested", "", "tags", List.of());
        }
        String branch = repoLocator.currentBranch(projectId);
        List<String> tags = repoLocator.listTags(projectId, 50);
        return Map.of(
                "branch", branch == null ? "" : branch,
                "suggested", GitIssueServiceImpl.suggestVersion(branch, tags),
                "tags", tags);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        issues.delete(currentUser.requireUserId(), id);
        return Map.of("deleted", true);
    }

    @PostMapping("/{id}/close")
    public GitIssue close(@PathVariable String id) {
        return issues.setState(currentUser.requireUserId(), id, "CLOSED");
    }

    @PostMapping("/{id}/reopen")
    public GitIssue reopen(@PathVariable String id) {
        return issues.setState(currentUser.requireUserId(), id, "OPEN");
    }

    @PostMapping("/{id}/sync")
    public GitIssue sync(@PathVariable String id) {
        return issues.sync(currentUser.requireUserId(), id);
    }

    @GetMapping("/{id}/comments")
    public List<GitIssueComment> comments(@PathVariable String id) {
        return issues.comments(currentUser.requireUserId(), id);
    }

    @PostMapping("/{id}/comments")
    public GitIssueComment comment(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String text = str(body, "body");
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评论内容不能为空");
        }
        return issues.comment(currentUser.requireUserId(), id, text);
    }

    @DeleteMapping("/{id}/comments/{commentId}")
    public Map<String, Object> deleteComment(@PathVariable String id, @PathVariable String commentId) {
        issues.deleteComment(currentUser.requireUserId(), id, commentId);
        return Map.of("deleted", true);
    }

    // ---------------------------------------------------------------- 任务配图

    /**
     * 上传一张任务配图，返回可直接写进 Markdown 的地址。
     *
     * <p>任务正文以 Markdown 存储，图片只保存文件与本机地址；Git 远端只拿到这段文本，
     * 图片仍由平台提供（本地部署场景）。
     */
    @PostMapping("/images")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> uploadImage(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片不能为空");
        }
        if (file.getSize() > IMAGE_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片不能超过 5MB");
        }
        String ext = extension(file.getOriginalFilename(), file.getContentType());
        if (!IMAGE_EXTS.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "只支持 png / jpg / jpeg / gif / webp 图片");
        }
        Files.createDirectories(imageDir);
        String name = UUID.randomUUID() + "." + ext;
        file.transferTo(imageDir.resolve(name));
        return Map.of("url", "/api/v1/git-issue-images/" + name);
    }

    /** 扩展名：优先文件名，其次 Content-Type；未知返回空串。 */
    private static String extension(String filename, String contentType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) {
                String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (!ext.contains("/") && ext.length() <= 5) {
                    return ext;
                }
            }
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("png")) return "png";
        if (type.contains("gif")) return "gif";
        if (type.contains("webp")) return "webp";
        if (type.contains("jpeg") || type.contains("jpg")) return "jpg";
        return "";
    }

    /**
     * 请求体 → 写入模型。
     *
     * @param current 更新时的当前任务；创建时为 null。
     *                <p>非 null 表示「部分更新」：请求体里没有的 key 沿用当前值，
     *                只有显式出现的 key 才覆盖（包括有意清空的空串）。
     */
    private static GitIssue.Draft draft(Map<String, Object> body, GitIssue current) {
        String title = pick(body, "title", current == null ? "" : current.title());
        if (title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务标题不能为空");
        }
        return new GitIssue.Draft(
                pick(body, "projectId", current == null ? "" : current.projectId()),
                pick(body, "provider", current == null ? "" : current.provider()),
                pick(body, "repo", current == null ? "" : current.repo()),
                title,
                pick(body, "body", current == null ? "" : current.body()),
                pick(body, "state", current == null ? "" : current.state()),
                pick(body, "kind", current == null ? "" : current.kind()),
                pick(body, "priority", current == null ? "" : current.priority()),
                pick(body, "labels", current == null ? "" : current.labels()),
                pick(body, "assignee", current == null ? "" : current.assignee()),
                pick(body, "version", current == null ? "" : current.version()));
    }

    /** 请求体里出现的 key 用它（可为空串 = 显式清空），没出现则用兜底值。 */
    private static String pick(Map<String, Object> body, String key, String fallback) {
        if (body == null || !body.containsKey(key)) {
            return fallback;
        }
        Object value = body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}