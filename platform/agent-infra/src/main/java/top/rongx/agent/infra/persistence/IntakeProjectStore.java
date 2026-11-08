package top.rongx.agent.infra.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.domain.project.ProjectEnv;

/**
 * 接入项目的 SQLite 存储（v2 P1：接入的项目重启不丢）。
 *
 * <p>与 {@link InMemoryProjectRepository}（yml 预置，只读）合并使用（见 {@link CompositeProjectRepository}）。
 * 数据库文件：{@code <workspaceRoot>/_platform/projects.db}（data/ 已 gitignore）。
 *
 * <p><b>短连接模式</b>：每次操作打开-用-关连接。SQLite 是单文件库，长连接会在 Windows 上
 * 锁住文件（测试 TempDir 清理失败、项目工作区删除失败都源于此）；短连接 + WAL 不必要——
 * 平台写入频率极低（只在接入/下线时写），性能无影响。
 */
public class IntakeProjectStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IntakeProjectStore.class);

    private final Path dbFile;

    public IntakeProjectStore(Path dbFile) {
        this.dbFile = dbFile.toAbsolutePath().normalize();
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法创建项目存储目录：" + e.getMessage(), e);
        }
        initSchema();
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    private void initSchema() {
        try (Connection conn = open()) {
            try (PreparedStatement st = conn.prepareStatement("""
                    CREATE TABLE IF NOT EXISTS projects (
                        id          TEXT PRIMARY KEY,
                        name        TEXT NOT NULL,
                        repo_url    TEXT,
                        repo_type   TEXT,
                        type        TEXT,
                        port_start  INTEGER,
                        port_end    INTEGER,
                        source      TEXT NOT NULL,
                        local_path  TEXT,
                        created_at  INTEGER NOT NULL
                    )
                    """)) {
                st.executeUpdate();
            }
            try (PreparedStatement st = conn.prepareStatement("""
                    CREATE TABLE IF NOT EXISTS project_envs (
                        project_id  TEXT NOT NULL,
                        env         TEXT NOT NULL,
                        app_url     TEXT,
                        compose_dir TEXT,
                        PRIMARY KEY (project_id, env)
                    )
                    """)) {
                st.executeUpdate();
            }
            // 旧库迁移：加 storage_mode 列（幂等；已存在则忽略）
            try (PreparedStatement st = conn.prepareStatement(
                    "ALTER TABLE projects ADD COLUMN storage_mode TEXT NOT NULL DEFAULT 'MANAGED'")) {
                st.executeUpdate();
            } catch (SQLException duplicate) {
                // 列已存在（旧库刚迁移过），忽略
            }
        } catch (SQLException e) {
            throw new IllegalStateException("无法初始化项目存储 " + dbFile + "：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 写

    /** 登记一个接入完成的项目（含环境）。已存在则覆盖（重接入语义）。 */
    public void upsert(IntakeProject p, List<ProjectEnv> envs) {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try (PreparedStatement st = conn.prepareStatement("""
                    INSERT INTO projects (id, name, repo_url, repo_type, type,
                                          port_start, port_end, source, local_path, storage_mode, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET
                        name=excluded.name, repo_url=excluded.repo_url, repo_type=excluded.repo_type,
                        type=excluded.type, port_start=excluded.port_start, port_end=excluded.port_end,
                        source=excluded.source, local_path=excluded.local_path, storage_mode=excluded.storage_mode
                    """)) {
                st.setString(1, p.id());
                st.setString(2, p.name());
                st.setString(3, p.repoUrl());
                st.setString(4, p.repoType());
                st.setString(5, p.type());
                st.setInt(6, p.portRangeStart());
                st.setInt(7, p.portRangeEnd());
                st.setString(8, p.source());
                st.setString(9, p.localPath());
                st.setString(10, p.storageMode());
                st.setLong(11, System.currentTimeMillis());
                st.executeUpdate();
            }
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM project_envs WHERE project_id = ?")) {
                del.setString(1, p.id());
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement("""
                    INSERT INTO project_envs (project_id, env, app_url, compose_dir) VALUES (?,?,?,?)
                    """)) {
                for (ProjectEnv e : envs) {
                    ins.setString(1, p.id());
                    ins.setString(2, e.env());
                    ins.setString(3, e.appUrl());
                    ins.setString(4, e.composeDir());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("项目登记失败：" + e.getMessage(), e);
        }
    }

    /** 删除项目（解除托管）。 */
    public boolean delete(String projectId) {
        try (Connection conn = open()) {
            boolean removed;
            try (PreparedStatement st = conn.prepareStatement("DELETE FROM projects WHERE id = ?")) {
                st.setString(1, projectId);
                removed = st.executeUpdate() > 0;
            }
            try (PreparedStatement de = conn.prepareStatement(
                    "DELETE FROM project_envs WHERE project_id = ?")) {
                de.setString(1, projectId);
                de.executeUpdate();
            }
            return removed;
        } catch (SQLException e) {
            throw new IllegalStateException("项目删除失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 读

    public Optional<IntakeProject> find(String projectId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("SELECT id, name, repo_url, repo_type, type, port_start, port_end, source, local_path, storage_mode FROM projects WHERE id = ?")) {
            st.setString(1, projectId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(map(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("项目查询失败：" + e.getMessage(), e);
        }
    }

    public List<IntakeProject> findAll() {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("SELECT id, name, repo_url, repo_type, type, port_start, port_end, source, local_path, storage_mode FROM projects ORDER BY id")) {
            try (ResultSet rs = st.executeQuery()) {
                List<IntakeProject> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("项目查询失败：" + e.getMessage(), e);
        }
    }

    public List<ProjectEnv> findEnvs(String projectId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT project_id, env, app_url, compose_dir FROM project_envs WHERE project_id = ? ORDER BY env")) {
            st.setString(1, projectId);
            try (ResultSet rs = st.executeQuery()) {
                List<ProjectEnv> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ProjectEnv(rs.getString("project_id"), rs.getString("env"),
                            rs.getString("app_url"), rs.getString("compose_dir")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("环境查询失败：" + e.getMessage(), e);
        }
    }

    private IntakeProject map(ResultSet rs) throws SQLException {
        return new IntakeProject(
                rs.getString("id"), rs.getString("name"), rs.getString("repo_url"),
                rs.getString("repo_type"), rs.getString("type"),
                rs.getInt("port_start"), rs.getInt("port_end"),
                rs.getString("source"), rs.getString("local_path"), rs.getString("storage_mode"));
    }

    /** 兼容 AutoCloseable（短连接模式下无长资源）；no-op。 */
    @Override
    public void close() {
        // 短连接模式：无长持有资源
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
