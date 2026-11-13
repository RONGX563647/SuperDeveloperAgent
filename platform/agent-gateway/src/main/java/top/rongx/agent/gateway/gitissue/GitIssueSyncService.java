package top.rongx.agent.gateway.gitissue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.gitissue.GitIssueComment;
import top.rongx.agent.domain.gitissue.GitIssueIntegration;
import top.rongx.agent.domain.gitissue.GitIssueIntegrationRepository;
import top.rongx.agent.domain.gitissue.GitIssueRepository;
import top.rongx.agent.domain.bugfix.BugFixTrigger;
import top.rongx.agent.domain.port.ProjectRepository;

/**
 * Git Issue webhook 的异步处理：去重 → 映射为平台任务 → 同步状态/评论。
 *
 * <p>回调线程只做「验签 + 入队 + 立刻 200」；解析与落库全部在工作线程完成，
 * 不在回调里跑任何耗时逻辑（更不会跑大模型）。
 */
@Service
public class GitIssueSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitIssueSyncService.class);
    private static final String FALLBACK_USER = "admin";
    /** 平台刚推送后的冷却窗口：避免「平台改 → webhook 回灌 → 再改」循环。 */
    private static final long COOLDOWN_MS = 15_000L;

    private final GitIssueRepository issues;
    private final GitIssueIntegrationRepository integrations;
    private final GitIssueAgentWaker waker;
    private final BugFixTrigger bugFixTrigger;
    private final ProjectRepository projects;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService executor;

    public GitIssueSyncService(GitIssueRepository issues,
                               GitIssueIntegrationRepository integrations,
                               GitIssueAgentWaker waker,
                               BugFixTrigger bugFixTrigger,
                               ProjectRepository projects) {
        this.issues = issues;
        this.integrations = integrations;
        this.waker = waker;
        this.bugFixTrigger = bugFixTrigger;
        this.projects = projects;
        // 虚拟线程执行器：webhook 回调只入队，解析/落库异步执行
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 提交一个已通过验签的事件。
     *
     * @return true=首次收到（已入队）；false=重复事件（忽略）
     */
    public boolean submit(String provider, String eventKey, String eventType, String rawBody) {
        String key = provider.toUpperCase(Locale.ROOT) + ":" + eventKey;
        if (!issues.markEvent(key)) {
            return false;
        }
        executor.submit(() -> {
            try {
                process(provider.toUpperCase(Locale.ROOT), eventType, rawBody);
            } catch (Exception e) {
                log.warn("Git Issue webhook 处理失败（{} / {}）：{}", provider, eventType,
                        e.getMessage(), e);
            }
        });
        return true;
    }

    private void process(String provider, String eventType, String rawBody) {
        GitIssueIntegration config = integrations.find(provider).orElse(null);
        if (config == null || !config.enabled()) {
            return;
        }
        if (!config.pullEnabled()) {
            return;
        }
        ParsedEvent event = parse(provider, eventType, rawBody);
        if (event == null || event.repo().isBlank() || event.number().isBlank()) {
            return;
        }
        GitIssue issue = issues.findByRemote(provider, event.repo(), event.number()).orElse(null);
        if (issue == null) {
            if (!config.autoCreateTask() || !config.allowsRepo(event.repo())) {
                return;
            }
            issue = create(config, event);
        } else {
            update(config, issue, event);
        }
        if (event.comment() != null) {
            addComment(issue, event.comment());
            waker.wakeIfCommand(issue, event.comment().body());
        }
    }

    private GitIssue create(GitIssueIntegration config, ParsedEvent event) {
        long now = System.currentTimeMillis();
        String owner = config.ownerUserId().isBlank() ? FALLBACK_USER : config.ownerUserId();
        String kind = event.kind().isBlank()
                ? GitIssue.kindOfLabels(event.labels()) : event.kind();
        GitIssue issue = new GitIssue("task-" + randomHex(), owner, matchingProject(event.repo()),
                event.provider(), event.repo(), event.number(),
                event.title().isBlank() ? "Git Issue " + event.number() : event.title(),
                event.body(), event.state(), kind, event.labels(), event.url(), "GIT", "SYNCED", "",
                "", now, now);
        log.info("Git Issue 已映射为平台任务：{} {}#{} → {}", event.provider(), event.repo(),
                event.number(), issue.id());
        GitIssue saved = issues.save(issue);
        // 新建的 Bug 任务按策略触发自动修复（异步，不阻塞 webhook）
        bugFixTrigger.onIssueCreated(saved);
        return saved;
    }

    private void update(GitIssueIntegration config, GitIssue issue, ParsedEvent event) {
        if (!config.allowsRepo(event.repo()) && issue.bound()) {
            // 已绑定的任务不受白名单收紧影响，避免历史任务突然失联
            log.debug("仓库不在白名单但任务已绑定，继续同步：{}", event.repo());
        }
        boolean platformWins = "PLATFORM_WINS".equals(config.conflictPolicy());
        long sincePush = System.currentTimeMillis() - issue.updatedAt();
        if (platformWins && "PLATFORM".equals(issue.source()) && sincePush < COOLDOWN_MS) {
            // 冷却窗口内忽略状态回灌；评论仍会追加
            return;
        }
        String kind = !event.kind().isBlank() ? event.kind()
                : event.labels().isBlank() ? issue.kind() : GitIssue.kindOfLabels(event.labels());
        GitIssue updated = new GitIssue(issue.id(), issue.userId(), issue.projectId(),
                issue.provider(), issue.repo(), issue.number(),
                event.title().isBlank() ? issue.title() : event.title(),
                event.body().isBlank() ? issue.body() : event.body(),
                event.state().isBlank() ? issue.state() : event.state(),
                kind,
                event.labels().isBlank() ? issue.labels() : event.labels(),
                event.url().isBlank() ? issue.url() : event.url(),
                "GIT", "SYNCED", "", issue.assignee(), issue.createdAt(),
                System.currentTimeMillis());
        issues.save(updated);
        // 远端重新打开/补标签后再次评估自动修复（关闭状态会被触发器拒绝）
        bugFixTrigger.onIssueUpdated(updated);
    }

    /**
     * 按仓库地址自动关联平台项目：命中后评论里的指令才能唤醒 Agent。
     * 未命中返回空串（任务仍会创建，只是需要人工补关联）。
     */
    private String matchingProject(String repo) {
        String target = GitIssueRemoteClient.cleanRepo(repo);
        if (target.isBlank()) {
            return "";
        }
        return projects.findAll().stream()
                .filter(p -> p.repoUrl() != null && !p.repoUrl().isBlank())
                .filter(p -> GitIssueRemoteClient.cleanRepo(p.repoUrl()).equalsIgnoreCase(target))
                .map(p -> p.id())
                .findFirst()
                .orElse("");
    }

    private void addComment(GitIssue issue, RemoteComment comment) {
        String id = comment.id().isBlank()
                ? "git-" + issue.provider().toLowerCase(Locale.ROOT) + "-"
                + hash(comment.body() + comment.createdAt())
                : "git-" + issue.provider().toLowerCase(Locale.ROOT) + "-" + comment.id();
        issues.addComment(new GitIssueComment(id, issue.id(), comment.author(), comment.body(),
                "GIT", comment.createdAt() > 0 ? comment.createdAt() : System.currentTimeMillis()));
    }

    // ---------------------------------------------------------------- 解析

    private ParsedEvent parse(String provider, String eventType, String rawBody) {
        String type = eventType == null ? "" : eventType.trim();
        return switch (provider) {
            case "GITHUB" -> parseGithub(type, rawBody);
            case "GITEE" -> parseGitee(type, rawBody);
            case "GITLAB" -> parseGitlab(type, rawBody);
            default -> null;
        };
    }

    private ParsedEvent parseGithub(String eventType, String raw) {
        try {
            JsonNode root = json.readTree(raw);
            JsonNode issue = root.path("issue");
            if (issue.isMissingNode()) {
                return null;
            }
            RemoteComment comment = null;
            JsonNode c = root.path("comment");
            if ("issue_comment".equalsIgnoreCase(eventType) && !c.isMissingNode()) {
                comment = new RemoteComment(text(c, "id"), text(c.path("user"), "login"),
                        text(c, "body"), 0);
            }
            return new ParsedEvent("GITHUB", text(root.path("repository"), "full_name"),
                    text(issue, "number"), text(issue, "title"), text(issue, "body"),
                    normalizeState(text(issue, "state")), labels(issue.path("labels")),
                    text(issue, "html_url"), text(root, "action"),
                    kindOfType(issue.path("type")), comment);
        } catch (Exception e) {
            log.warn("GitHub webhook 解析失败：{}", e.getMessage());
            return null;
        }
    }

    private ParsedEvent parseGitee(String eventType, String raw) {
        try {
            JsonNode root = json.readTree(raw);
            JsonNode issue = root.path("issue");
            if (issue.isMissingNode()) {
                return null;
            }
            RemoteComment comment = null;
            JsonNode c = root.path("comment");
            if (eventType.toLowerCase(Locale.ROOT).contains("comment") && !c.isMissingNode()) {
                comment = new RemoteComment(text(c, "id"), text(c.path("user"), "login"),
                        text(c, "body"), 0);
            }
            String repo = text(root.path("repository"), "full_name");
            if (repo.isBlank()) {
                repo = text(root.path("repository"), "path_with_namespace");
            }
            String number = text(issue, "number");
            if (number.isBlank()) {
                number = text(issue, "id");
            }
            // Gitee webhook 用 type_name 返回任务类型（缺陷 / 需求 / 任务）
            String remoteKind = kindOfType(issue.path("type_name"));
            if (remoteKind.isBlank()) {
                remoteKind = kindOfType(issue.path("issue_type"));
            }
            return new ParsedEvent("GITEE", repo, number, text(issue, "title"),
                    text(issue, "body"), normalizeState(text(issue, "state")),
                    labels(issue.path("labels")), text(issue, "html_url"),
                    text(root, "action"), remoteKind, comment);
        } catch (Exception e) {
            log.warn("Gitee webhook 解析失败：{}", e.getMessage());
            return null;
        }
    }

    private ParsedEvent parseGitlab(String eventType, String raw) {
        try {
            JsonNode root = json.readTree(raw);
            String kind = text(root, "object_kind");
            String repo = text(root.path("project"), "path_with_namespace");
            JsonNode issue;
            RemoteComment comment = null;
            if ("note".equalsIgnoreCase(kind)) {
                issue = root.path("issue");
                JsonNode note = root.path("object_attributes");
                comment = new RemoteComment(text(note, "id"), text(root.path("user"), "username"),
                        text(note, "note"), 0);
            } else {
                issue = root.has("object_attributes") ? root.path("object_attributes") : root;
            }
            if (issue.isMissingNode()) {
                return null;
            }
            String number = text(issue, "iid");
            if (number.isBlank()) {
                number = text(issue, "id");
            }
            String state = text(issue, "state");
            return new ParsedEvent("GITLAB", repo, number, text(issue, "title"),
                    text(issue, "description"), normalizeState(state),
                    labels(issue.path("labels")), text(issue, "url"),
                    text(root, "action"), kindOfType(issue.path("issue_type")), comment);
        } catch (Exception e) {
            log.warn("GitLab webhook 解析失败：{}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String labels(JsonNode array) {
        if (!array.isArray()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode item : array) {
            String name = item.isTextual() ? item.asText()
                    : item.has("name") ? item.path("name").asText() : item.path("title").asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return String.join(",", names);
    }

    /** 远端原生 Issue 类型 → 平台分类；无法识别时返回空串，交给标签推导。 */
    private static String kindOfType(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        String name = node.isTextual() ? node.asText() : node.path("name").asText("");
        String text = name.toLowerCase(Locale.ROOT);
        if (text.contains("feature") || text.contains("enhancement") || text.contains("需求")) {
            return "FEAT";
        }
        if (text.contains("bug") || text.contains("defect") || text.contains("缺陷")) {
            return "BUG";
        }
        return "";
    }

    private static String normalizeState(String state) {
        String s = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
        return "closed".equals(s) ? "CLOSED" : "OPEN";
    }

    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    value == null ? new byte[0] : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }

    private static String randomHex() {
        byte[] raw = new byte[5];
        new java.security.SecureRandom().nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    record RemoteComment(String id, String author, String body, long createdAt) {
    }

    record ParsedEvent(String provider, String repo, String number, String title, String body,
                       String state, String labels, String url, String action,
                       String kind, RemoteComment comment) {
    }
}
