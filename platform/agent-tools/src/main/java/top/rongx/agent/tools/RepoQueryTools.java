package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.RepoInspector;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 仓库只读工具：git 状态 / 提交 / 对比，以及项目内文件浏览与读取。
 *
 * <p>复用平台已有能力（「工作区」页的 git 数据、项目本地目录），不复制实现：
 * <ul>
 *   <li>git 数据经 {@link RepoInspector}（infra 侧复用 {@code GitWorktreeManager}）；</li>
 *   <li>仓库根经 {@link ProjectRepoLocator}，与会话里的工作树保持一致；</li>
 *   <li>文件访问被限制在仓库根之内（路径逃逸直接拒绝），只读、不写盘。</li>
 * </ul>
 *
 * <p>所有方法都不接受 projectId：项目来自 {@link ProjectContext}，模型无法篡改。
 */
@Component
public class RepoQueryTools {

    /** 单次列目录最多返回的条目数。 */
    private static final int MAX_ENTRIES = 200;
    /** 单次读取文件的最大字符数（超出截断，避免把大文件塞进上下文）。 */
    private static final int MAX_FILE_CHARS = 8000;
    /** 判定二进制的探测字节数。 */
    private static final int BINARY_PROBE_BYTES = 8000;
    /** grep 单次最多扫描的文件数 / 单文件大小上限。 */
    private static final int MAX_SCAN_FILES = 20000;
    private static final long MAX_SCAN_FILE_BYTES = 512 * 1024;
    /** 构建产物目录：默认既不在 git_status 里刷屏，也不参与 grep。 */
    private static final Set<String> BUILD_DIRS = Set.of(
            "node_modules", "dist", "target", "build", "out", "coverage",
            ".next", ".turbo", ".nuxt", ".output", ".gradle", ".mvn");

    private final RepoInspector repo;
    private final ProjectRepoLocator locator;
    private final SecretRedactor redactor;

    public RepoQueryTools(RepoInspector repo, ProjectRepoLocator locator, SecretRedactor redactor) {
        this.repo = repo;
        this.locator = locator;
        this.redactor = redactor;
    }

    @Tool(name = "git_status",
            description = """
                    查询当前项目仓库的状态：分支、HEAD、upstream 领先/落后、未提交改动（按目录聚合）、
                    最近 5 条提交与工作树列表。只读。
                    默认折叠 node_modules / dist / target 等构建产物（单独给数量），
                    需要看全部时传 include_build=true。""",
            readOnly = true, concurrencySafe = true)
    public String gitStatus(ProjectContext ctx,
                            @ToolParam(name = "include_build", required = false,
                                    description = "是否包含构建产物目录的改动，默认 false")
                            Boolean includeBuild) {
        RepoInspector.RepoStatus s = repo.status(ctx.projectId());
        if ("NONE".equals(s.storageMode())) {
            return "项目尚未接入 git 仓库（未找到可读的仓库目录）。evidence: project:"
                    + ctx.projectId();
        }
        boolean withBuild = Boolean.TRUE.equals(includeBuild);
        List<String> kept = new ArrayList<>();
        int buildCount = 0;
        for (String line : s.uncommitted()) {
            String path = porcelainPath(line);
            if (!withBuild && isBuildArtifact(path)) {
                buildCount++;
            } else {
                kept.add(line);
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("mode: ").append(s.storageMode()).append('\n');
        out.append("branch: ").append(blank(s.branch(), "(detached/unknown)")).append('\n');
        out.append("head: ").append(blank(s.head(), "(none)")).append('\n');
        out.append("upstream: ").append(blank(s.upstream(), "(none)"));
        if (s.upstream() != null) {
            out.append("  ahead=").append(s.ahead()).append(" behind=").append(s.behind());
        }
        out.append('\n');
        out.append("uncommitted: ").append(kept.size());
        if (buildCount > 0) {
            out.append("（已折叠构建产物 ").append(buildCount).append(" 条；include_build=true 可看全部）");
        }
        out.append('\n');
        if (kept.isEmpty()) {
            out.append("（没有源码改动").append(buildCount > 0 ? "，只有构建产物" : "").append("）\n");
        } else {
            out.append("byDir:\n");
            aggregateByDir(kept).forEach((dir, count) ->
                    out.append("- ").append(dir).append("  ").append(count).append('\n'));
            out.append("changes:\n");
            kept.stream().limit(40).forEach(line -> out.append("- ").append(line).append('\n'));
            if (kept.size() > 40) {
                out.append("…（其余 ").append(kept.size() - 40).append(" 条省略）\n");
            }
        }
        out.append("recentCommits:\n");
        if (s.recentCommits().isEmpty()) {
            out.append("(暂无提交)\n");
        }
        s.recentCommits().forEach(c -> out.append(formatCommit(c)).append('\n'));
        out.append("worktrees: ").append(s.worktrees().size()).append('\n');
        s.worktrees().forEach(w -> out.append("- ")
                .append(w.name()).append("  ").append(w.head())
                .append("  ").append(w.role()).append("  ").append(w.path()).append('\n'));
        out.append("evidence: project:").append(ctx.projectId());
        return out.toString();
    }

    @Tool(name = "grep",
            description = """
                    在项目仓库内按正则检索文本（只读），返回 文件:行号: 内容。
                    默认跳过 node_modules / dist / target 等构建目录与二进制文件；
                    用于查配置开关、错误文案、接口路径，避免逐个 read_file 翻文件。""",
            readOnly = true, concurrencySafe = true)
    public String grep(ProjectContext ctx,
                       @ToolParam(name = "pattern", description = "Java 正则表达式")
                       String pattern,
                       @ToolParam(name = "path", required = false,
                               description = "相对仓库根的路径（文件或目录），默认整个仓库")
                       String path,
                       @ToolParam(name = "max_results", required = false,
                               description = "最多返回条数，默认 50，范围 1-200")
                       Integer maxResults,
                       @ToolParam(name = "case_sensitive", required = false,
                               description = "是否区分大小写，默认 false")
                       Boolean caseSensitive) {
        if (isBlank(pattern)) {
            throw new IllegalArgumentException("pattern 不能为空");
        }
        int limit = maxResults == null ? 50 : Math.max(1, Math.min(200, maxResults));
        int flags = Boolean.TRUE.equals(caseSensitive) ? 0 : Pattern.CASE_INSENSITIVE;
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("正则非法：" + e.getDescription());
        }
        Path root = repoRoot(ctx);
        Path target = resolveInside(root, path);
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("路径不存在：" + path);
        }
        List<String> matches = new ArrayList<>();
        int[] scanned = {0};
        StringBuilder truncated = new StringBuilder();
        try {
            if (Files.isRegularFile(target)) {
                scanFile(target, root, compiled, limit, matches, scanned, ctx);
            } else {
                Files.walkFileTree(target, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (scanned[0] >= MAX_SCAN_FILES) {
                            return FileVisitResult.TERMINATE;
                        }
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        // .agent 里是工作树副本（整仓复制），扫它会重复统计并把配额耗光
                        if (!dir.equals(target) && (BUILD_DIRS.contains(name)
                                || name.equals(".git") || name.equals(".agent"))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (matches.size() >= limit || scanned[0] >= MAX_SCAN_FILES) {
                            return FileVisitResult.TERMINATE;
                        }
                        scanFile(file, root, compiled, limit, matches, scanned, ctx);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("检索失败：" + e.getMessage());
        }
        String base = root.relativize(target).toString().replace('\\', '/');
        String scanNote = scanned[0] >= MAX_SCAN_FILES
                ? "，已达扫描上限，结果可能不完整；请缩小 path" : "";
        if (matches.isEmpty()) {
            return "pattern: %s\npath: %s\nmatches: 0（扫描 %d 个文件%s）\nevidence: project:%s"
                    .formatted(pattern, base.isBlank() ? "." : base, scanned[0], scanNote,
                            ctx.projectId());
        }
        if (matches.size() >= limit) {
            truncated.append("（已达到上限 ").append(limit).append(" 条，可能还有更多匹配）\n");
        }
        return """
                pattern: %s
                path: %s
                matches: %d（扫描 %d 个文件%s）
                ---
                %s
                ---
                evidence: project:%s""".formatted(pattern, base.isBlank() ? "." : base,
                matches.size(), scanned[0], scanNote, String.join("\n", matches) + truncated,
                ctx.projectId());
    }

    private void scanFile(Path file, Path root, Pattern pattern, int limit,
                          List<String> matches, int[] scanned, ProjectContext ctx) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_SCAN_FILE_BYTES) {
                return;
            }
            String rel = root.relativize(file).toString().replace('\\', '/');
            // 凭据文件不参与检索：结果里出现 .env / 私钥的任何一行都等于泄漏
            if (redactor.isSensitiveFile(rel)) {
                return;
            }
            byte[] head;
            try (var in = Files.newInputStream(file)) {
                head = in.readNBytes(BINARY_PROBE_BYTES);
            }
            for (byte b : head) {
                if (b == 0) {
                    return;
                }
            }
            scanned[0]++;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && matches.size() < limit; i++) {
                Matcher m = pattern.matcher(lines.get(i));
                if (m.find()) {
                    String text = lines.get(i).strip();
                    if (text.length() > 240) {
                        text = text.substring(0, 240) + "…";
                    }
                    matches.add(rel + ":" + (i + 1) + ": " + redactor.redactLine(text, ctx));
                }
            }
        } catch (IOException ignored) {
            // 单个文件读不了不影响整体检索
        }
    }

    /** {@code git status --porcelain} 行 → 文件路径（重命名取目标路径）。 */
    private static String porcelainPath(String line) {
        if (line == null || line.length() <= 3) {
            return "";
        }
        String path = line.substring(3).trim();
        int arrow = path.indexOf(" -> ");
        if (arrow >= 0) {
            path = path.substring(arrow + 4);
        }
        return path.replace('\\', '/');
    }

    private static boolean isBuildArtifact(String path) {
        for (String segment : path.split("/")) {
            if (BUILD_DIRS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /** 按顶层目录聚合改动数（构建产物已在上游折叠）。 */
    private static Map<String, Integer> aggregateByDir(List<String> lines) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String line : lines) {
            String path = porcelainPath(line);
            int slash = path.indexOf('/');
            String dir = slash > 0 ? path.substring(0, slash) : "(root)";
            counts.merge(dir, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    @Tool(name = "git_commits",
            description = "查询当前项目最近的提交历史（按时间倒序，最多 30 条）。只读。",
            readOnly = true, concurrencySafe = true)
    public String gitCommits(ProjectContext ctx,
                             @ToolParam(name = "limit", required = false,
                                     description = "返回条数，默认 10，范围 1-30")
                             Integer limit) {
        int safeLimit = limit == null ? 10 : Math.max(1, Math.min(30, limit));
        List<RepoInspector.Commit> commits = repo.commits(ctx.projectId(), safeLimit, 0);
        if (commits.isEmpty()) {
            return "当前项目暂无提交记录（或仓库未接入）。evidence: project:" + ctx.projectId();
        }
        return commits.stream()
                .map(RepoQueryTools::formatCommit)
                .collect(Collectors.joining("\n", "commits:\n", "\nevidence: project:" + ctx.projectId()));
    }

    @Tool(name = "git_diff",
            description = """
                    对比两个提交之间的差异：返回 from..to 的提交列表与 diff stat（文件改动行数）。
                    只读。用于回答"这两次提交之间改了什么"。""",
            readOnly = true, concurrencySafe = true)
    public String gitDiff(ProjectContext ctx,
                          @ToolParam(name = "from", description = "起始提交（sha / 分支 / HEAD~1）")
                          String from,
                          @ToolParam(name = "to", description = "结束提交（sha / 分支 / HEAD）")
                          String to) {
        if (isBlank(from) || isBlank(to)) {
            throw new IllegalArgumentException("from / to 不能为空");
        }
        List<String> commits = repo.logRange(ctx.projectId(), from, to);
        List<String> stat = repo.diffStat(ctx.projectId(), from, to);
        return """
                range: %s..%s
                commits: %d
                %s
                diffStat:
                ---
                %s
                ---
                evidence: project:%s""".formatted(from, to, commits.size(),
                commits.isEmpty() ? "(无)" : String.join("\n", commits),
                stat.isEmpty() ? "(无改动)" : String.join("\n", stat), ctx.projectId());
    }

    @Tool(name = "list_files",
            description = """
                    列出项目仓库内某个目录（默认仓库根）的文件与子目录。只读。
                    path 是相对仓库根的路径，如 "platform/agent-tools"；不能访问仓库之外的目录。""",
            readOnly = true, concurrencySafe = true)
    public String listFiles(ProjectContext ctx,
                            @ToolParam(name = "path", required = false,
                                    description = "相对仓库根的目录，默认根目录；根目录传 . 或省略")
                            String path) {
        Path root = repoRoot(ctx);
        Path target = resolveInside(root, path);
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("目录不存在：" + path);
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                entries.add(p);
                if (entries.size() >= MAX_ENTRIES) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取目录：" + e.getMessage());
        }
        entries.sort(Comparator
                .comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString().toLowerCase()));
        String base = root.relativize(target).toString().replace('\\', '/');
        return entries.stream()
                .map(p -> (Files.isDirectory(p) ? "d " : "f ")
                        + (base.isBlank() ? "" : base + "/")
                        + p.getFileName().toString().replace('\\', '/'))
                .collect(Collectors.joining("\n",
                        "dir: " + (base.isBlank() ? "." : base) + "\n",
                        "\nevidence: project:" + ctx.projectId()));
    }

    @Tool(name = "read_file",
            description = """
                    读取项目仓库内的一个文本文件（相对路径，最多约 8000 字符）。
                    只读。用于回答"这段配置/代码是怎么写的"；二进制文件与仓库外路径会被拒绝。
                    .env、私钥与凭据文件不提供原文（请用 env_summary / env_var_probe 查看变量指向），
                    其它文本中的密钥值会被替换为 [REDACTED]。""",
            readOnly = true, concurrencySafe = true)
    public String readFile(ProjectContext ctx,
                           @ToolParam(name = "path", description = "相对仓库根的文件路径")
                           String path) {
        Path root = repoRoot(ctx);
        Path target = resolveInside(root, path);
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("文件不存在：" + path);
        }
        String relative = root.relativize(target).toString().replace('\\', '/');
        if (redactor.isSensitiveFile(relative)) {
            throw new IllegalArgumentException(
                    "该文件可能包含凭据（.env / 私钥 / 凭据文件），平台不返回原文：" + relative
                            + "；请用 env_summary / env_var_probe 查看变量数量与指向");
        }
        try {
            byte[] head = new byte[BINARY_PROBE_BYTES];
            int read;
            try (var in = Files.newInputStream(target)) {
                read = in.read(head);
            }
            for (int i = 0; i < read; i++) {
                if (head[i] == 0) {
                    throw new IllegalArgumentException("这是二进制文件，无法按文本读取：" + path);
                }
            }
            String text = redactor.redact(Files.readString(target, StandardCharsets.UTF_8), ctx);
            boolean truncated = text.length() > MAX_FILE_CHARS;
            String body = truncated
                    ? text.substring(0, MAX_FILE_CHARS) + "\n…（已截断，原文件 " + text.length() + " 字符）"
                    : text;
            return """
                    file: %s
                    ---
                    %s
                    ---
                    evidence: project:%s""".formatted(relative, body, ctx.projectId());
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取文件：" + e.getMessage());
        }
    }

    /** 仓库根：会话指定了工作树则用工作树，否则用主仓库。 */
    private Path repoRoot(ProjectContext ctx) {
        Path base = locator.repoDir(ctx.projectId()).orElseThrow(() ->
                new IllegalArgumentException("项目尚未接入 git 仓库，无法浏览文件：" + ctx.projectId()));
        if (ctx.worktree() == null) {
            return base;
        }
        Path worktree = locator.worktreeRoot(ctx.projectId())
                .map(root -> root.resolve(ctx.worktree()).normalize())
                .orElseThrow(() -> new IllegalArgumentException(
                        "找不到工作树根：" + ctx.worktree()));
        if (!Files.isDirectory(worktree)) {
            throw new IllegalArgumentException("工作树不存在：" + ctx.worktree());
        }
        return worktree;
    }

    /** 解析相对路径并保证不逃逸出仓库根。 */
    private static Path resolveInside(Path root, String path) {
        String relative = path == null ? "" : path.trim();
        Path resolved = (relative.isEmpty() ? root : root.resolve(relative)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("路径超出仓库范围：" + path);
        }
        return resolved;
    }

    private static String formatCommit(RepoInspector.Commit c) {
        return "- %s  %s  %s  %s".formatted(c.sha(), c.author(), c.relativeTime(), c.subject());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }
}
