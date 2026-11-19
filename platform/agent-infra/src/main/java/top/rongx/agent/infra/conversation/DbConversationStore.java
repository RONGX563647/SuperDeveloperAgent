package top.rongx.agent.infra.conversation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.conversation.Conversation;
import top.rongx.agent.domain.conversation.ConversationKind;
import top.rongx.agent.domain.conversation.ConversationPatch;
import top.rongx.agent.domain.conversation.ConversationRepository;
import top.rongx.agent.domain.conversation.ConversationRun;
import top.rongx.agent.domain.conversation.ConversationRunRepository;
import top.rongx.agent.domain.conversation.ConversationStatus;
import top.rongx.agent.domain.conversation.ConversationTimelineRepository;
import top.rongx.agent.domain.conversation.ConversationTranscriptRepository;
import top.rongx.agent.domain.conversation.RunStatus;
import top.rongx.agent.domain.conversation.TaskStatus;
import top.rongx.agent.domain.conversation.TimelineEntry;
import top.rongx.agent.domain.conversation.WorkspaceTask;
import top.rongx.agent.domain.conversation.WorkspaceTaskRepository;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 工作区会话 / 运行 / 任务的 JPA 实现。
 *
 * <p>表结构由 {@code *Entity} 生成；连接、事务与 SQLite/PostgreSQL 方言由 Hibernate 负责。
 * 列表与状态查询不触碰加密内容，也不需要解密。
 *
 * <p>owner 过滤在 SQL 层完成：模型传入的 conversationId 不可信，越权必须在存储层挡住。
 */
@Repository
public class DbConversationStore
        implements ConversationRepository, ConversationRunRepository, WorkspaceTaskRepository,
        ConversationTranscriptRepository, ConversationTimelineRepository {

    private static final String CONVERSATION_COLUMNS = """
            id, project_id, owner_user_id, title, kind, role, env, worktree, status, summary,
            pinned, archived, parent_conversation_id, last_activity_at, created_at,
            model_connection_id, model_name
            """;

    private static final String RUN_COLUMNS = """
            id, project_id, conversation_id, owner_user_id, goal, goal_key, status, result, error,
            created_at, started_at, ended_at
            """;

    private static final String TASK_COLUMNS = """
            id, project_id, conversation_id, goal, expected_output, status, priority,
            created_at, updated_at, source_ref, trigger_type
            """;

    private final PlatformDb db;

    @Autowired
    public DbConversationStore(PlatformDb db) {
        this.db = db;
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public DbConversationStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this(platformDb(workspaceRoot));
    }

    private static PlatformDb platformDb(String workspaceRoot) {
        Path dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.db");
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "会话数据库目录创建失败：" + e.getMessage(), e);
        }
        return PlatformDb.sqlite(dbFile);
    }

    // ------------------------------------------------------------------ 会话

    @Override
    public Conversation save(Conversation conversation) {
        String sql = """
                INSERT INTO conversations
                    (id, project_id, owner_user_id, title, kind, role, env, worktree, status,
                     summary, pinned, archived, parent_conversation_id,
                     last_activity_at, created_at, model_connection_id, model_name)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try {
            db.run(em -> PlatformDb.update(em, sql,
                    conversation.id(),
                    conversation.projectId(),
                    conversation.ownerUserId(),
                    conversation.title(),
                    conversation.kind().name(),
                    conversation.role(),
                    conversation.env(),
                    conversation.worktree(),
                    conversation.status().name(),
                    conversation.summary(),
                    conversation.pinned() ? 1 : 0,
                    conversation.archived() ? 1 : 0,
                    conversation.parentConversationId(),
                    conversation.lastActivityAt(),
                    conversation.createdAt(),
                    conversation.modelConnectionId(),
                    conversation.modelName()));
            return conversation;
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "会话保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Conversation> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + CONVERSATION_COLUMNS + " FROM conversations WHERE id = ?", id)
                    .map(DbConversationStore::mapConversation));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "会话查询失败：" + e.getMessage(), e);
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
        try {
            return db.read(em -> PlatformDb.rows(em, sql, projectId, ownerUserId).stream()
                    .map(DbConversationStore::mapConversation).toList());
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "会话列表查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Conversation> findSupervisor(String projectId, String ownerUserId) {
        String sql = "SELECT " + CONVERSATION_COLUMNS + " FROM conversations "
                + "WHERE project_id = ? AND owner_user_id = ? AND kind = ? "
                + "ORDER BY created_at LIMIT 1";
        try {
            return db.read(em -> PlatformDb.row(em, sql, projectId, ownerUserId,
                    ConversationKind.SUPERVISOR.name()).map(DbConversationStore::mapConversation));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "调度会话查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Conversation> update(String id, String ownerUserId, ConversationPatch patch) {
        if (id == null || id.isBlank() || patch == null) {
            return Optional.empty();
        }
        String sql = """
                UPDATE conversations SET
                    title = ?, env = ?, worktree = ?, status = ?, summary = ?,
                    pinned = ?, archived = ?, last_activity_at = ?,
                    model_connection_id = ?, model_name = ?
                WHERE id = ? AND owner_user_id = ?
                """;
        try {
            return db.inTx(em -> {
                Optional<Conversation> existing = PlatformDb.row(em,
                        "SELECT " + CONVERSATION_COLUMNS + " FROM conversations WHERE id = ?", id)
                        .map(DbConversationStore::mapConversation);
                if (existing.isEmpty() || !existing.get().ownerUserId().equals(ownerUserId)) {
                    return Optional.<Conversation>empty();
                }
                Conversation merged = merge(existing.get(), patch);
                PlatformDb.update(em, sql,
                        merged.title(),
                        merged.env(),
                        merged.worktree(),
                        merged.status().name(),
                        merged.summary(),
                        merged.pinned() ? 1 : 0,
                        merged.archived() ? 1 : 0,
                        merged.lastActivityAt(),
                        merged.modelConnectionId(),
                        merged.modelName(),
                        id,
                        ownerUserId);
                return Optional.of(merged);
            });
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "会话更新失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(String id, String ownerUserId) {
        try {
            return db.inTx(em -> {
                int removed = PlatformDb.update(em,
                        "DELETE FROM conversations WHERE id = ? AND owner_user_id = ?", id, ownerUserId);
                if (removed == 0) {
                    return false;
                }
                // 会话删了，历史运行与任务不能留孤儿行：跟着一起物理删除
                PlatformDb.update(em, "DELETE FROM conversation_runs WHERE conversation_id = ?", id);
                PlatformDb.update(em, "DELETE FROM workspace_tasks WHERE conversation_id = ?", id);
                PlatformDb.update(em, "DELETE FROM conversation_messages WHERE conversation_id = ?", id);
                return true;
            });
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "会话删除失败：" + e.getMessage(), e);
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
        String modelConnectionId = patch.modelConnectionId() == null
                ? current.modelConnectionId() : blankToNull(patch.modelConnectionId());
        String modelName = patch.modelName() == null
                ? current.modelName() : blankToNull(patch.modelName());
        return new Conversation(current.id(), current.projectId(), current.ownerUserId(), title,
                current.kind(), current.role(), env, worktree, status, summary, pinned, archived,
                current.parentConversationId(), System.currentTimeMillis(), current.createdAt(),
                modelConnectionId, modelName);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Conversation mapConversation(Object[] row) {
        return new Conversation(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                ConversationKind.valueOf((String) row[4]),
                (String) row[5],
                (String) row[6],
                (String) row[7],
                ConversationStatus.valueOf((String) row[8]),
                (String) row[9],
                ((Number) row[10]).intValue() == 1,
                ((Number) row[11]).intValue() == 1,
                (String) row[12],
                ((Number) row[13]).longValue(),
                ((Number) row[14]).longValue(),
                (String) row[15],
                (String) row[16]);
    }

    // ------------------------------------------------------------------ 会话时间线

    @Override
    public long append(String conversationId, String kind, String role, String text, String tool,
                       String toolCallId) {
        try {
            return db.inTx(em -> {
                var entity = new top.rongx.agent.infra.persistence.entity.ConversationTimelineEntity(
                        conversationId, kind, role, text, tool, toolCallId,
                        System.currentTimeMillis());
                em.persist(entity);
                em.flush();
                return entity.getSeq();
            });
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "时间线写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<TimelineEntry> entries(String conversationId, long afterSeq, int limit) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        try {
            return db.read(em -> PlatformDb.rows(em,
                            """
                            SELECT seq, conversation_id, kind, role, text, tool, tool_call_id, created_at
                            FROM conversation_timeline
                            WHERE conversation_id = ? AND seq > ?
                            ORDER BY seq
                            LIMIT ?
                            """,
                            conversationId, afterSeq, limit)
                    .stream()
                    .map(row -> new TimelineEntry(
                            ((Number) row[0]).longValue(),
                            (String) row[1],
                            (String) row[2],
                            (String) row[3],
                            (String) row[4],
                            (String) row[5],
                            (String) row[6],
                            ((Number) row[7]).longValue()))
                    .toList());
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "时间线查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteByConversation(String conversationId) {
        db.run(em -> PlatformDb.update(em,
                "DELETE FROM conversation_timeline WHERE conversation_id = ?", conversationId));
    }

    @Override
    public Optional<String> findTranscript(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        try {
            return db.read(em -> PlatformDb.<String>first(em,
                    "SELECT payload FROM conversation_messages WHERE conversation_id = ?",
                    conversationId));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "会话历史查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void saveTranscript(String conversationId, String payload) {
        String sql = """
                INSERT INTO conversation_messages (conversation_id, payload, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(conversation_id) DO UPDATE SET
                    payload = excluded.payload, updated_at = excluded.updated_at
                """;
        try {
            db.run(em -> PlatformDb.update(em, sql,
                    conversationId, payload, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "会话历史保存失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 运行

    @Override
    public ConversationRun saveRun(ConversationRun run) {
        String sql = """
                INSERT INTO conversation_runs
                    (id, project_id, conversation_id, owner_user_id, goal, goal_key,
                     status, result, error, created_at, started_at, ended_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try {
            db.run(em -> PlatformDb.update(em, sql,
                    run.id(),
                    run.projectId(),
                    run.conversationId(),
                    run.ownerUserId(),
                    run.goal(),
                    run.goalKey(),
                    run.status().name(),
                    run.result(),
                    run.error(),
                    run.createdAt(),
                    run.startedAt(),
                    run.endedAt()));
            return run;
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public ConversationRun updateRun(ConversationRun run) {
        String sql = """
                UPDATE conversation_runs SET
                    status = ?, result = ?, error = ?, started_at = ?, ended_at = ?
                WHERE id = ?
                """;
        try {
            db.run(em -> PlatformDb.update(em, sql,
                    run.status().name(),
                    run.result(),
                    run.error(),
                    run.startedAt(),
                    run.endedAt(),
                    run.id()));
            return run;
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行更新失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ConversationRun> findRun(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + RUN_COLUMNS + " FROM conversation_runs WHERE id = ?", id)
                    .map(DbConversationStore::mapRun));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ConversationRun> findActiveByConversation(String conversationId) {
        String sql = "SELECT " + RUN_COLUMNS + " FROM conversation_runs "
                + "WHERE conversation_id = ? AND status IN (?, ?) "
                + "ORDER BY created_at DESC LIMIT 1";
        try {
            return db.read(em -> PlatformDb.row(em, sql, conversationId,
                    RunStatus.QUEUED.name(), RunStatus.RUNNING.name())
                    .map(DbConversationStore::mapRun));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "活动运行查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ConversationRun> findActiveByGoal(String projectId, String goalKey) {
        String sql = "SELECT " + RUN_COLUMNS + " FROM conversation_runs "
                + "WHERE project_id = ? AND goal_key = ? AND status IN (?, ?) "
                + "ORDER BY created_at DESC LIMIT 1";
        try {
            return db.read(em -> PlatformDb.row(em, sql, projectId, goalKey,
                    RunStatus.QUEUED.name(), RunStatus.RUNNING.name())
                    .map(DbConversationStore::mapRun));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "重复目标查询失败：" + e.getMessage(), e);
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

    @Override
    public List<ConversationRun> findActiveRuns(int limit) {
        String sql = "SELECT " + RUN_COLUMNS + " FROM conversation_runs "
                + "WHERE status IN (?, ?) ORDER BY created_at ASC LIMIT ?";
        try {
            return db.read(em -> PlatformDb.rows(em, sql,
                            RunStatus.QUEUED.name(), RunStatus.RUNNING.name(), Math.max(1, limit))
                    .stream().map(DbConversationStore::mapRun).toList());
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "活动运行扫描失败：" + e.getMessage(), e);
        }
    }

    private List<ConversationRun> queryRuns(String sql, String key, int limit) {
        try {
            return db.read(em -> PlatformDb.rows(em, sql, key, limit).stream()
                    .map(DbConversationStore::mapRun).toList());
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "运行列表查询失败：" + e.getMessage(), e);
        }
    }

    private static ConversationRun mapRun(Object[] row) {
        return new ConversationRun(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[4],
                (String) row[5],
                RunStatus.valueOf((String) row[6]),
                (String) row[7],
                (String) row[8],
                ((Number) row[9]).longValue(),
                ((Number) row[10]).longValue(),
                ((Number) row[11]).longValue());
    }

    // ------------------------------------------------------------------ 任务

    @Override
    public WorkspaceTask saveTask(WorkspaceTask task) {
        String sql = """
                INSERT INTO workspace_tasks
                    (id, project_id, conversation_id, goal, expected_output, status, priority,
                     created_at, updated_at, source_ref, trigger_type)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """;
        try {
            db.run(em -> PlatformDb.update(em, sql,
                    task.id(),
                    task.projectId(),
                    task.conversationId(),
                    task.goal(),
                    task.expectedOutput(),
                    task.status().name(),
                    task.priority(),
                    task.createdAt(),
                    task.updatedAt(),
                    task.sourceRef(),
                    task.triggerType()));
            return task;
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "任务保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public WorkspaceTask updateTask(WorkspaceTask task) {
        String sql = """
                UPDATE workspace_tasks SET
                    conversation_id = ?, goal = ?, expected_output = ?, status = ?,
                    priority = ?, updated_at = ?
                WHERE id = ?
                """;
        try {
            db.run(em -> PlatformDb.update(em, sql,
                    task.conversationId(),
                    task.goal(),
                    task.expectedOutput(),
                    task.status().name(),
                    task.priority(),
                    task.updatedAt(),
                    task.id()));
            return task;
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "任务更新失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<WorkspaceTask> findTask(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + TASK_COLUMNS + " FROM workspace_tasks WHERE id = ?", id)
                    .map(DbConversationStore::mapTask));
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "任务查询失败：" + e.getMessage(), e);
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
        try {
            return db.read(em -> PlatformDb.rows(em, sql, key).stream()
                    .map(DbConversationStore::mapTask).toList());
        } catch (RuntimeException e) {
            throw new RunException(ErrorCode.RUN_INVALID_STATE, "任务列表查询失败：" + e.getMessage(), e);
        }
    }

    private static WorkspaceTask mapTask(Object[] row) {
        return new WorkspaceTask(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[4],
                TaskStatus.valueOf((String) row[5]),
                ((Number) row[6]).intValue(),
                ((Number) row[7]).longValue(),
                ((Number) row[8]).longValue(),
                (String) row[9],
                (String) row[10]);
    }
}
