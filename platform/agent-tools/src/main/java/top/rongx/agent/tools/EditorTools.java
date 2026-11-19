package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.change.ChangeWire;
import top.rongx.agent.domain.change.PlanHasher;
import top.rongx.agent.domain.error.PlatformException;
import top.rongx.agent.domain.port.ChangeStore;
import top.rongx.agent.domain.port.SessionWorkspaceEditor;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 会话工作树编辑工具：Agent 在「本会话独占的 worktree」里读写代码。
 *
 * <p>边界：
 * <ul>
 *   <li>生产环境直接拒绝；</li>
 *   <li>必须已在会话里选择专属工作树（{@code ctx.worktree()} 非空）；</li>
 *   <li>写/删/提交需要<b>分支级一次授权</b>：登记 {@code editor_branch:<branch>} 变更单，
 *       用户在确认卡片上确认后放行；Agent 自己无法签发确认令牌。</li>
 * </ul>
 * 只读操作（open/read/list/diff）不需要授权。
 */
@Component
public class EditorTools {

    /** 单次写入内容上限（字符）。 */
    private static final int MAX_CONTENT_CHARS = 200_000;
    /** 编辑授权类变更的 kind 前缀：{@code editor_branch:<branch>}。 */
    private static final String EDITOR_PREFIX = "editor_branch:";

    private final SessionWorkspaceEditor editor;
    private final ChangeStore changes;

    public EditorTools(SessionWorkspaceEditor editor, ChangeStore changes) {
        this.editor = editor;
        this.changes = changes;
    }

    @Tool(name = "workspace_open", description = "Open this session's isolated worktree.",
            readOnly = false, concurrencySafe = false)
    public String open(ProjectContext ctx) {
        String blocked = guard(ctx);
        if (blocked != null) {
            return blocked;
        }
        try {
            SessionWorkspaceEditor.Session session =
                    editor.open(ctx.projectId(), ctx.worktree());
            return """
                    worktree: %s
                    branch: %s
                    head: %s
                    created: %s
                    evidence: worktree:%s
                    """.formatted(session.worktree(), session.branch(), session.head(),
                    session.created(), ctx.worktree());
        } catch (PlatformException | IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }
    }

    @Tool(name = "workspace_write", description = "Write a file in the session worktree.",
            readOnly = false, concurrencySafe = false)
    public String write(
            @ToolParam(name = "path", description = "相对工作树根的文件路径，如 src/main/App.java") String path,
            @ToolParam(name = "content", description = "文件完整新内容（UTF-8，上限 20 万字符）") String content,
            ProjectContext ctx) {
        String blocked = guard(ctx);
        if (blocked != null) {
            return blocked;
        }
        if (path == null || path.isBlank()) {
            return "err: path 不能为空";
        }
        if (content != null && content.length() > MAX_CONTENT_CHARS) {
            return "err: content 超过 " + MAX_CONTENT_CHARS + " 字符，请拆分后分次写入";
        }
        try {
            String branch = editor.open(ctx.projectId(), ctx.worktree()).branch();
            String pending = authorization(ctx, branch);
            if (pending != null) {
                return pending;
            }
            editor.write(ctx.projectId(), ctx.worktree(), path, content);
            return """
                    ok: wrote %s (%d chars)
                    branch: %s
                    next: 用 workspace_diff 查看改动，workspace_commit 提交。
                    """.formatted(path.trim(), content == null ? 0 : content.length(), branch);
        } catch (PlatformException | IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }
    }

    @Tool(name = "workspace_read", description = "Read a file from the session worktree.",
            readOnly = true, concurrencySafe = true)
    public String read(
            @ToolParam(name = "path", description = "相对工作树根的文件路径") String path,
            ProjectContext ctx) {
        String blocked = guard(ctx);
        if (blocked != null) {
            return blocked;
        }
        if (path == null || path.isBlank()) {
            return "err: path 不能为空";
        }
        try {
            return editor.read(ctx.projectId(), ctx.worktree(), path);
        } catch (PlatformException | IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }
    }

    @Tool(name = "workspace_list", description = "List files in the session worktree.",
            readOnly = true, concurrencySafe = true)
    public String list(
            @ToolParam(name = "path", description = "相对目录，空 = 工作树根", required = false) String path,
            @ToolParam(name = "depth", description = "递归深度 1-3，默认 1", required = false) int depth,
            ProjectContext ctx) {
        String blocked = guard(ctx);
        if (blocked != null) {
            return blocked;
        }
        try {
            List<String> entries = editor.list(ctx.projectId(), ctx.worktree(), path, depth);
            if (entries.isEmpty()) {
                return "empty: 目录没有文件";
            }
            return String.join("\n", entries);
        } catch (PlatformException | IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }
    }

    @Tool(name = "workspace_delete", description = "Delete a file in the session worktree.",
            readOnly = false, concurrencySafe = false)
    public String delete(
            @ToolParam(name = "path", description = "相对工作树根的文件路径") String path,
            ProjectContext ctx) {
        String blocked = guard(ctx);
        if (blocked != null) {
            return blocked;
        }
        if (path == null || path.isBlank()) {
            return "err: path 不能为空";
        }
        try {
            String branch = editor.open(ctx.projectId(), ctx.worktree()).branch();
            String pending = authorization(ctx, branch);
            if (pending != null) {
                return pending;
            }
            boolean removed = editor.delete(ctx.projectId(), ctx.worktree(), path);
            return removed
                    ? "ok: deleted " + path.trim() + "\nbranch: " + branch
                    : "ok: 文件不存在，无需删除：" + path.trim();
        } catch (PlatformException | IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }
    }

    @Tool(name = "workspace_diff", description = "Show uncommitted changes in worktree.",
            readOnly = true, concurrencySafe = true)
    public String diff(ProjectContext ctx) {
        String blocked = guard(ctx);
        if (blocked != null) {
            return blocked;
        }
        try {
            SessionWorkspaceEditor.Diff diff = editor.diff(ctx.projectId(), ctx.worktree());
            String changes = diff.changes().isEmpty() ? "clean" : String.join("\n", diff.changes());
            String stat = diff.stat() == null || diff.stat().isBlank() ? "" : diff.stat();
            return """
                    changes: %s
                    %s
                    """.formatted(changes, stat);
        } catch (PlatformException | IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }
    }

    @Tool(name = "workspace_commit", description = "Commit session worktree changes.",
            readOnly = false, concurrencySafe = false)
    public String commit(
            @ToolParam(name = "message", description = "提交信息（写清改了什么、为什么）") String message,
            ProjectContext ctx) {
        String blocked = guard(ctx);
        if (blocked != null) {
            return blocked;
        }
        String text = message == null || message.isBlank()
                ? "agent: session workspace update" : message.trim();
        try {
            String branch = editor.open(ctx.projectId(), ctx.worktree()).branch();
            String pending = authorization(ctx, branch);
            if (pending != null) {
                return pending;
            }
            SessionWorkspaceEditor.Commit commit =
                    editor.commit(ctx.projectId(), ctx.worktree(), text);
            return """
                    ok: committed
                    sha: %s
                    branch: %s
                    files:
                    %s
                    """.formatted(commit.sha(), commit.branch(),
                    commit.files().stream().map(f -> "- " + f)
                            .collect(java.util.stream.Collectors.joining("\n")));
        } catch (PlatformException | IllegalArgumentException e) {
            return "err: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 统一前置检查：生产环境与未选工作树都在进入文件系统前拒绝。 */
    private String guard(ProjectContext ctx) {
        if (ctx.isProduction()) {
            return "err: 生产环境不允许编辑文件；请先在 dev/test 验证";
        }
        if (ctx.worktree() == null || ctx.worktree().isBlank()) {
            return "err: 当前会话没有专属工作树；请先在会话里选择工作树，再让 Agent 编辑";
        }
        return null;
    }

    /**
     * 分支级授权：返回 null = 已授权；否则返回待确认文本（含 changeId，触发确认卡）。
     *
     * <p>同一分支已有 PENDING 单时复用单号，避免模型每写一次就新登记一张卡。
     */
    private String authorization(ProjectContext ctx, String branch) {
        String kind = EDITOR_PREFIX + branch;
        Optional<ChangePlan> latest = changes.findLatest(ctx.projectId(), kind);
        if (latest.isPresent() && kind.equals(latest.get().kind())) {
            ChangePlan plan = latest.get();
            if (plan.status() == ChangePlan.Status.CONFIRMED
                    || plan.status() == ChangePlan.Status.APPLIED) {
                return null;
            }
            if (plan.status() == ChangePlan.Status.PENDING) {
                return needsConfirmation(plan);
            }
        }
        String summary = "Agent 编辑分支写权限：" + branch;
        ChangePlan plan = changes.registerWithHash(ctx.projectId(), ctx.env(), kind, summary,
                PlanHasher.hash(ctx.projectId(), ctx.env(), kind), false);
        return needsConfirmation(plan);
    }

    private static String needsConfirmation(ChangePlan plan) {
        return """
                status: NEEDS_CONFIRMATION
                %s
                kind: %s
                env: %s
                next: 请在对话中的确认卡片上确认（分支级一次授权）；确认后重试本工具，确认前不会写入任何文件。
                """.formatted(ChangeWire.line(plan.changeId()), plan.kind(), plan.env());
    }
}
