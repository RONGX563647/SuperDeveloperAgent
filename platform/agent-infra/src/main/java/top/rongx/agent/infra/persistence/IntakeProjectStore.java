package top.rongx.agent.infra.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import top.rongx.agent.domain.project.ProjectEnv;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.IntakeException;

/**
 * 接入项目的存储（v2 P1：接入的项目重启不丢）。
 *
 * <p>与 {@link InMemoryProjectRepository}（yml 预置，只读）合并使用（见 {@link CompositeProjectRepository}）。
 * 表结构由 {@code ProjectEntity} / {@code ProjectEnvEntity} 生成，连接与事务交给 {@link PlatformDb}。
 */
public class IntakeProjectStore implements AutoCloseable {

    private static final String PROJECT_COLUMNS = """
            id, name, repo_url, repo_type, type, port_start, port_end, source, local_path,
            storage_mode
            """;

    private static final String PROJECT_UPSERT_SQL = """
            INSERT INTO projects (id, name, repo_url, repo_type, type,
                                  port_start, port_end, source, local_path, storage_mode, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                name=excluded.name, repo_url=excluded.repo_url, repo_type=excluded.repo_type,
                type=excluded.type, port_start=excluded.port_start, port_end=excluded.port_end,
                source=excluded.source, local_path=excluded.local_path, storage_mode=excluded.storage_mode
            """;

    private final PlatformDb db;

    @Autowired
    public IntakeProjectStore(PlatformDb db) {
        this.db = db;
    }

    /** 测试/独立进程：按库文件定位统一 PlatformDb。 */
    public IntakeProjectStore(Path dbFile) {
        this(platformDb(dbFile));
    }

    private static PlatformDb platformDb(Path dbFile) {
        Path file = dbFile.toAbsolutePath().normalize();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "无法创建项目存储目录：" + e.getMessage(), e);
        }
        return PlatformDb.sqlite(file);
    }

    // ---------------------------------------------------------------- 写

    /** 登记一个接入完成的项目（含环境）。已存在则覆盖（重接入语义）。 */
    public void upsert(IntakeProject p, List<ProjectEnv> envs) {
        // BUG-45：composeDir 目前只登记、不参与部署落点，但同一项目内填成同一个目录
        // 会让人误以为"环境已隔离"。这里直接拒绝，避免存量脏数据变成隐形事故。
        requireDistinctComposeDir(envs);
        try {
            db.run(em -> {
                PlatformDb.update(em, PROJECT_UPSERT_SQL,
                        p.id(),
                        p.name(),
                        p.repoUrl(),
                        p.repoType(),
                        p.type(),
                        p.portRangeStart(),
                        p.portRangeEnd(),
                        p.source(),
                        p.localPath(),
                        p.storageMode(),
                        System.currentTimeMillis());
                PlatformDb.update(em, "DELETE FROM project_envs WHERE project_id = ?", p.id());
                for (ProjectEnv e : envs) {
                    PlatformDb.update(em, """
                            INSERT INTO project_envs (project_id, env, compose_dir)
                            VALUES (?,?,?)
                            """, p.id(), e.env(), e.composeDir());
                }
            });
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "项目登记失败：" + e.getMessage(), e);
        }
    }

    /**
     * 补齐一个环境登记（幂等）。
     *
     * <p>接入时已经会写 dev/test/prod；这里用于「一键准备」把历史项目缺的环境补上，
     * 避免用户为了一个空环境再走一遍接入流程。
     */
    public boolean ensureEnv(String projectId, String env) {
        String sql = """
                INSERT INTO project_envs (project_id, env, compose_dir)
                VALUES (?, ?, '')
                ON CONFLICT(project_id, env) DO NOTHING
                """;
        try {
            return db.inTx(em -> PlatformDb.update(em, sql, projectId, env) > 0);
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "环境登记失败：" + e.getMessage(), e);
        }
    }

    /**
     * 校验同一项目内的环境登记目录不重复。
     *
     * <p>BUG-45：{@code composeDir} 只登记、不参与部署落点（部署落点由流水线 deploy 段与
     * 容器命名决定）。同一个值被多个环境共用时，用户会误以为环境已经隔离，因此这里拒绝保存。
     * 空值表示"未登记"，不参与比较。
     */
    private static void requireDistinctComposeDir(List<ProjectEnv> envs) {
        if (envs == null) {
            return;
        }
        java.util.Map<String, String> seen = new java.util.LinkedHashMap<>();
        for (ProjectEnv e : envs) {
            String dir = e.composeDir() == null ? "" : e.composeDir().trim();
            if (dir.isEmpty()) {
                continue;
            }
            String prev = seen.putIfAbsent(dir, e.env());
            if (prev != null) {
                throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT,
                        "composeDir 重复：环境 " + prev + " 与 " + e.env() + " 都登记为 " + dir
                                + "。该字段只做登记、不改变部署落点，请改用不同目录或留空。");
            }
        }
    }

    /** 删除项目（解除托管）。 */
    public boolean delete(String projectId) {
        try {
            return db.inTx(em -> {
                int removed = PlatformDb.update(em, "DELETE FROM projects WHERE id = ?", projectId);
                PlatformDb.update(em, "DELETE FROM project_envs WHERE project_id = ?", projectId);
                return removed > 0;
            });
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "项目删除失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 读

    public Optional<IntakeProject> find(String projectId) {
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + PROJECT_COLUMNS + " FROM projects WHERE id = ?", projectId)
                    .map(IntakeProjectStore::map));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "项目查询失败：" + e.getMessage(), e);
        }
    }

    public List<IntakeProject> findAll() {
        try {
            return db.read(em -> PlatformDb.rows(em,
                    "SELECT " + PROJECT_COLUMNS + " FROM projects ORDER BY id").stream()
                    .map(IntakeProjectStore::map).toList());
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "项目查询失败：" + e.getMessage(), e);
        }
    }

    public List<ProjectEnv> findEnvs(String projectId) {
        try {
            return db.read(em -> PlatformDb.rows(em,
                    "SELECT project_id, env, compose_dir FROM project_envs"
                            + " WHERE project_id = ? ORDER BY env", projectId).stream()
                    .map(row -> new ProjectEnv((String) row[0], (String) row[1],
                            (String) row[2]))
                    .toList());
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "环境查询失败：" + e.getMessage(), e);
        }
    }

    private static IntakeProject map(Object[] row) {
        return new IntakeProject(
                (String) row[0], (String) row[1], (String) row[2],
                (String) row[3], (String) row[4],
                row[5] == null ? 0 : ((Number) row[5]).intValue(),
                row[6] == null ? 0 : ((Number) row[6]).intValue(),
                (String) row[7], (String) row[8], (String) row[9]);
    }

    /** 共享库由 PlatformDb 持有，Store 不再负责关闭；保留 AutoCloseable 兼容调用方。 */
    @Override
    public void close() {
        // no-op
    }

    /** 接入项目记录（source: LOCAL / REMOTE）。 */
    public record IntakeProject(String id, String name, String repoUrl, String repoType,
                                String type, int portRangeStart, int portRangeEnd,
                                String source, String localPath, String storageMode) {
        public boolean isLocal() {
            return "LOCAL".equalsIgnoreCase(source);
        }

        /** 存储模式：DIRECT（用户目录就地）/ MANAGED（平台 bare）。缺省 MANAGED 兼容旧数据。 */
        public String mode() {
            return storageMode == null || storageMode.isBlank() ? "MANAGED" : storageMode;
        }

        /** 转 domain 视图。 */
        public top.rongx.agent.domain.project.Project toProject() {
            return new top.rongx.agent.domain.project.Project(id, name, repoUrl, repoType,
                    type, portRangeStart, portRangeEnd);
        }
    }
}
