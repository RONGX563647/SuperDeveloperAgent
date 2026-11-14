package top.rongx.agent.infra.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.conversation.Conversation;
import top.rongx.agent.domain.conversation.ConversationKind;
import top.rongx.agent.domain.conversation.ConversationPatch;
import top.rongx.agent.domain.conversation.ConversationRepository;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.ConversationRunRepository;
import top.rongx.agent.domain.conversation.ConversationStatus;
import top.rongx.agent.domain.conversation.RunStatus;
import top.rongx.agent.domain.conversation.TaskStatus;
import top.rongx.agent.domain.conversation.WorkspaceTask;
import top.rongx.agent.domain.conversation.WorkspaceTaskRepository;
import top.rongx.agent.infra.sqlite.SqliteConnections;

/**
 * 工作区会话 / 运行 / 任务的 SQLite 存储。
 *
 * <p>三张表放在 {@code _platform/conversations.db}：与会话正文（AgentScope 加密状态）分开，
 * 列表与状态查询不触碰加密内容，也不需要解密。
 *
 * <p>owner 过滤在 SQL 层完成：模型传入的 conversationId 不可信，越权必须在存储层挡住。
 */
@Repository
public class SqliteConversationStore
        implements ConversationRepository, ConversationRunRepository, WorkspaceTaskRepository {

    private static final String CONVERSATION_COLUMNS = """
            id, project_id, owner_user_id, title, kind, role, env, worktree, status, summary,
            pinned, archived, parent_conversation_id, last_activity_at, created_at
            """;

    private static final String RUN_COLUMNS = """
            id, project_id, conversation_id, owner_user_id, goal, goal_key, status, result, error,
            created_at, started_at, ended_at
            """;

    private static final String TASK_COLUMNS = """
            id, project_id, conversation_id, goal, expected_output, status, priority,
            created_at, updated_at
            """;

    private static final String CONVERSATION_SCHEMA = """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                owner_user_id TEXT NOT NULL,
                title TEXT NOT NULL,
                kind TEXT NOT NULL,
                role TEXT NOT NULL,
                env TEXT NOT NULL,
                worktree TEXT,
                status TEXT NOT NULL,
                summary TEXT NOT NULL DEFAULT '',
                pinned INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0,
                parent_conversation_id TEXT,
                last_activity_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """;

    private static final String CONVERSATION_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_conversations_project_owner
            ON conversations(project_id, owner_user_id, last_activity_at DESC)
            """;

    private static final String RUN_SCHEMA = """
            CREATE TABLE IF NOT EXISTS conversation_runs (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                owner_user_id TEXT NOT NULL,
                goal TEXT NOT NULL,
                goal_key TEXT NOT NULL,
                status TEXT NOT NULL,
                result TEXT NOT NULL DEFAULT '',
                error TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                started_at INTEGER NOT NULL DEFAULT 0,
                ended_at INTEGER NOT NULL DEFAULT 0
            )
            """;

    private static final String RUN_INDEX_ACTIVE = """
            CREATE INDEX IF NOT EXISTS idx_runs_active
            ON conversation_runs(project_id, goal_key, status)
            """;

    private static final String RUN_INDEX_CONVERSATION = """
            CREATE INDEX IF NOT EXISTS idx_runs_conversation
            ON conversation_runs(conversation_id, created_at DESC)
            """;

    private static final String TASK_SCHEMA = """
            CREATE TABLE IF NOT EXISTS workspace_tasks (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                conversation_id TEXT,
                goal TEXT NOT NULL,
                expected_output TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL,
                priority INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """;

    private static final String TASK_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_tasks_project
            ON workspace_tasks(project_id, status, priority)
            """;

    private final Path dbFile;

    public SqliteConversationStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("conversations.db");
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("会话数据库目录创建失败：" + e.getMessage(), e);
        }
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = open()) {
            for (String ddl : List.of(CONVERSATION_SCHEMA, CONVERSATION_INDEX,
                    RUN_SCHEMA, RUN_INDEX_ACTIVE, RUN_INDEX_CONVERSATION,
                    TASK_SCHEMA, TASK_INDEX)) {
                try (PreparedStatement st = conn.prepareStatement(ddl)) {
                    st.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("conversations 表初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return SqliteConnections.get(dbFile);
    }

    // ------------------------------------------------------------------ 会话

    @Override
    public Conversation save(Conversation conversation) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO conversations
                         (id, project_id, owner_user_id, title, kind, role, env, worktree, status,
                          summary, pinned, archived, parent_conversation_id,
                          last_activity_at, created_at)
                     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
            st.setString(1, conversation.id());
            st.setString(2, conversation.projectId());
            st.setString(3, conversation.ownerUserId());
            st.setString(4, conversation.title());
            st.setString(5, conversation.kind().name());
            st.setString(6, conversation.role());
            st.setString(7, conversation.env());
            st.setString(8, conversation.worktree());
            st.setString(9, conversation.status().name());
            st.setString(10, conversation.summary());
            st.setInt(11, conversation.pinned() ? 1 : 0);
            st.setInt(12, conversation.archived() ? 1 : 0);
            st.setString(13, conversation.parentConversationId());
            st.setLong(14, conversation.lastActivityAt());
            st.setLong(15, conversation.createdAt());
            st.executeUpdate();
            return conversation;
        } catch (SQLException e) {
            throw new IllegalStateException("会话保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Conversation> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (Connection conn = open()) {
            return find(conn, id);
        } catch (SQLException e) {
            throw new IllegalStateException("会话查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Conversation> findByOwner(String id, String ownerUserId) {
        if (id == null || id.isBlank() || ownerUserId == null || ownerUserId.isBlank()) {
            return Optional.empty();
        }
        return find(id).filter(c -> c.ownerUserId().equals(ownerUserId));
    }

    @Override
    public List<Conversation> findByProject(String projectId, String ownerUserId,
                                            boolean includeArchived) {
        String sql = "SELECT " + CONVERSATION_COLUMNS + " FROM conversations "
                + "WHERE project_id = ? AND owner_user_id = ?"
                + (includeArchived ? "" : " AND archived = 0")
                + " ORDER BY pinned DESC, last_activity_at DESC";
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, projectId);
            st.setString(2, ownerUserId);
            try (ResultSet rs = st.executeQuery()) {
                List<Conversation> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapConversation(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("会话列表查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Conversation> findSupervisor(String projectId, String ownerUserId) {
        String sql = "SELECT " + CONVERSATION_COLUMNS + " FROM conversations "
                + "WHERE project_id = ? AND owner_user_id = ? AND kind = ? "
                + "ORDER BY created_at LIMIT 1";
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, projectId);
            st.setString(2, ownerUserId);
            st.setString(3, ConversationKind.SUPERVISOR.name());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(mapConversation(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("调度会话查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Conversation> update(String id, String ownerUserId, ConversationPatch patch) {
        if (id == null || id.isBlank() || patch == null) {
            return Optional.empty();
        }
        try (Connection conn = open()) {
            Optional<Conversation> existing = find(conn, id);
            if (existing.isEmpty() || !existing.get().ownerUserId().equals(ownerUserId)) {
                return Optional.empty();
            }
            Conversation merged = merge(existing.get(), patch);
            try (PreparedStatement st = conn.prepareStatement("""
                    UPDATE conversations SET
                        title = ?, env = ?, worktree = ?, status = ?, summary = ?,
                        pinned = ?, archived = ?, last_activity_at = ?
                    WHERE id = ? AND owner_user_id = ?
                    """)) {
                st.setString(1, merged.title());
                st.setString(2, merged.env());
                st.setString(3, merged.worktree());
                st.setString(4, merged.status().name());
                st.setString(5, merged.summary());
                st.setInt(6, merged.pinned() ? 1 : 0);
                st.setInt(7, merged.archived() ? 1 : 0);
                st.setLong(8, merged.lastActivityAt());
                st.setString(9, id);
                st.setString(10, ownerUserId);
                st.executeUpdate();
            }
            return Optional.of(merged);
        } catch (SQLException e) {
            throw new IllegalStateException("会话更新失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(String id, String ownerUserId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM conversations WHERE id = ? AND owner_user_id = ?")) {
            st.setString(1, id);
            st.setString(2, ownerUserId);
            return st.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("会话删除失败：" + e.getMessage(), e);
        }
    }

    private Conversation merge(Conversation current, ConversationPatch patch) {
        String title = patch.title() == null || patch.title().isBlank()
                ? current.title() : patch.title().trim();
        String env = patch.env() == null || patch.env().isBlank() ? current.env() : patch.env();
        String worktree = patch.worktree() == null
                ? current.worktree()
                : (patch.worktree().isBlank() ? null : patch.worktree().trim());
        ConversationStatus status = patch.status() == null ? current.status() : patch.status();
        String summary = patch.summary() == null ? current.summary() : patch.summary();
        boolean pinned = patch.pinned() == null ? current.pinned() : patch.pinned();
        boolean archived = patch.archived() == null ? current.archived() : patch.archived();
        return new Conversation(current.id(), current.projectId(), current.ownerUserId(), title,
                current.kind(), current.role(), env, worktree, status, summary, pinned, archived,
                current.parentConversationId(), System.currentTimeMillis(), current.createdAt());
    }

    private Optional<Conversation> find(Connection conn, String id) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT " + CONVERSATION_COLUMNS + " FROM conversations WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(mapConversation(rs)) : Optional.empty();
            }
        }
    }

    private Conversation mapConversation(ResultSet rs) throws SQLException {
        return new Conversation(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("owner_user_id"),
                rs.getString("title"),
                ConversationKind.valueOf(rs.getString("kind")),
                rs.getString("role"),
                rs.getString("env"),
                rs.getString("worktree"),
                ConversationStatus.valueOf(rs.getString("status")),
                rs.getString("summary"),
                rs.getInt("pinned") == 1,
                rs.getInt("archived") == 1,
                rs.getString("parent_conversation_id"),
                rs.getLong("last_activity_at"),
                rs.getLong("created_at"));
    }

    // ------------------------------------------------------------------ 运行

    @Override
    public ConversationRun saveRun(ConversationRun run) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO conversation_runs
                         (id, project_id, conversation_id, owner_user_id, goal, goal_key,
                          status, result, error, created_at, started_at, ended_at)
                     VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
            st.setString(1, run.id());
            st.setString(2, run.projectId());
            st.setString(3, run.conversationId());
            st.setString(4, run.ownerUserId());
            st.setString(5, run.goal());
            st.setString(6, run.goalKey());
            st.setString(7, run.status().name());
            st.setString(8, run.result());
            st.setString(9, run.error());
            st.setLong(10, run.createdAt());
            st.setLong(11, run.startedAt());
            st.setLong(12, run.endedAt());
            st.executeUpdate();
            return run;
        } catch (SQLException e) {
            throw new IllegalStateException("运行保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public ConversationRun updateRun(ConversationRun run) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE conversation_runs SET
                         status = ?, result = ?, error = ?, started_at = ?, ended_at = ?
                     WHERE id = ?
                     """)) {
            st.setString(1, run.status().name());
            st.setString(2, run.result());
            st.setString(3, run.error());
            st.setLong(4, run.startedAt());
            st.setLong(5, run.endedAt());
            st.setString(6, run.id());
            st.executeUpdate();
            return run;
        } catch (SQLException e) {
            throw new IllegalStateException("运行更新失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ConversationRun> findRun(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + RUN_COLUMNS + " FROM conversation_runs WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(mapRun(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("运行查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ConversationRun> findActiveByConversation(String conversationId) {
        String sql = "SELECT " + RUN_COLUMNS + " FROM conversation_runs "
                + "WHERE conversation_id = ? AND status IN (?, ?) "
                + "ORDER BY created_at DESC LIMIT 1";
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, conversationId);
            st.setString(2, RunStatus.QUEUED.name());
            st.setString(3, RunStatus.RUNNING.name());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(mapRun(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("活动运行查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ConversationRun> findActiveByGoal(String projectId, String goalKey) {
        String sql = "SELECT " + RUN_COLUMNS + " FROM conversation_runs "
                + "WHERE project_id = ? AND goal_key = ? AND status IN (?, ?) "
                + "ORDER BY created_at DESC LIMIT 1";
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, projectId);
            st.setString(2, goalKey);
            st.setString(3, RunStatus.QUEUED.name());
            st.setString(4, RunStatus.RUNNING.name());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(mapRun(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("重复目标查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<ConversationRun> findRunsByProject(String projectId, int limit) {
        String sql = "SELECT " + RUN_COLUMNS + " FROM conversation_runs "
                + "WHERE project_id = ? ORDER BY created_at DESC LIMIT ?";
        return queryRuns(sql, projectId, Math.max(1, limit));
    }

    @Override
    public List<ConversationRun> findRunsByConversation(String conversationId, int limit) {
        String sql = "SELECT " + RUN_COLUMNS + " FROM conversation_runs "
                + "WHERE conversation_id = ? ORDER BY created_at DESC LIMIT ?";
        return queryRuns(sql, conversationId, Math.max(1, limit));
    }

    private List<ConversationRun> queryRuns(String sql, String key, int limit) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, key);
            st.setInt(2, limit);
            try (ResultSet rs = st.executeQuery()) {
                List<ConversationRun> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapRun(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("运行列表查询失败：" + e.getMessage(), e);
        }
    }

    private ConversationRun mapRun(ResultSet rs) throws SQLException {
        return new ConversationRun(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("conversation_id"),
                rs.getString("owner_user_id"),
                rs.getString("goal"),
                rs.getString("goal_key"),
                RunStatus.valueOf(rs.getString("status")),
                rs.getString("result"),
                rs.getString("error"),
                rs.getLong("created_at"),
                rs.getLong("started_at"),
                rs.getLong("ended_at"));
    }

    // ------------------------------------------------------------------ 任务

    @Override
    public WorkspaceTask saveTask(WorkspaceTask task) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO workspace_tasks
                         (id, project_id, conversation_id, goal, expected_output, status, priority,
                          created_at, updated_at)
                     VALUES (?,?,?,?,?,?,?,?,?)
                     """)) {
            st.setString(1, task.id());
            st.setString(2, task.projectId());
            st.setString(3, task.conversationId());
            st.setString(4, task.goal());
            st.setString(5, task.expectedOutput());
            st.setString(6, task.status().name());
            st.setInt(7, task.priority());
            st.setLong(8, task.createdAt());
            st.setLong(9, task.updatedAt());
            st.executeUpdate();
            return task;
        } catch (SQLException e) {
            throw new IllegalStateException("任务保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public WorkspaceTask updateTask(WorkspaceTask task) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE workspace_tasks SET
                         conversation_id = ?, goal = ?, expected_output = ?, status = ?,
                         priority = ?, updated_at = ?
                     WHERE id = ?
                     """)) {
            st.setString(1, task.conversationId());
            st.setString(2, task.goal());
            st.setString(3, task.expectedOutput());
            st.setString(4, task.status().name());
            st.setInt(5, task.priority());
            st.setLong(6, task.updatedAt());
            st.setString(7, task.id());
            st.executeUpdate();
            return task;
        } catch (SQLException e) {
            throw new IllegalStateException("任务更新失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<WorkspaceTask> findTask(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + TASK_COLUMNS + " FROM workspace_tasks WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(mapTask(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("任务查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<WorkspaceTask> findTasksByProject(String projectId) {
        String sql = "SELECT " + TASK_COLUMNS + " FROM workspace_tasks "
                + "WHERE project_id = ? ORDER BY "
                + "CASE status WHEN 'RUNNING' THEN 0 WHEN 'TODO' THEN 1 WHEN 'BLOCKED' THEN 2 "
                + "WHEN 'DONE' THEN 3 ELSE 4 END, priority, updated_at DESC";
        return queryTasks(sql, projectId);
    }

    @Override
    public List<WorkspaceTask> findTasksByConversation(String conversationId) {
        String sql = "SELECT " + TASK_COLUMNS + " FROM workspace_tasks "
                + "WHERE conversation_id = ? ORDER BY created_at DESC";
        return queryTasks(sql, conversationId);
    }

    private List<WorkspaceTask> queryTasks(String sql, String key) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, key);
            try (ResultSet rs = st.executeQuery()) {
                List<WorkspaceTask> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapTask(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("任务列表查询失败：" + e.getMessage(), e);
        }
    }

    private WorkspaceTask mapTask(ResultSet rs) throws SQLException {
        return new WorkspaceTask(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("conversation_id"),
                rs.getString("goal"),
                rs.getString("expected_output"),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getInt("priority"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
