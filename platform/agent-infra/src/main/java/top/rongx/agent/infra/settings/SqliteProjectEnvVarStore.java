package top.rongx.agent.infra.settings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.settings.ProjectEnvVar;
import top.rongx.agent.domain.settings.ProjectEnvVarRepository;
import top.rongx.agent.infra.security.SecretBox;

/**
 * 项目环境变量（SQLite）：值一律 AES-GCM 加密落库。
 *
 * <p>部署器通过 {@link #resolve} 取明文注入容器；设置页通过 {@link #findAll} 只看掩码
 * （非敏感项返回明文，方便核对 DB_HOST 这类值）。
 */
@Repository
public class SqliteProjectEnvVarStore implements ProjectEnvVarRepository {

    private static final Logger log = LoggerFactory.getLogger(SqliteProjectEnvVarStore.class);

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS project_env_vars (
                project_id TEXT NOT NULL,
                env        TEXT NOT NULL,
                var_key    TEXT NOT NULL,
                value_enc  TEXT NOT NULL,
                value_iv   TEXT NOT NULL,
                is_secret  INTEGER NOT NULL DEFAULT 1,
                updated_by TEXT,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (project_id, env, var_key)
            )
            """;

    private static final String COLUMNS =
            "project_id, env, var_key, value_enc, value_iv, is_secret, updated_by, updated_at";

    private final Path dbFile;
    private final SecretBox secretBox;

    public SqliteProjectEnvVarStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
            SecretBox secretBox) {
        this.dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("projects.db");
        this.secretBox = secretBox;
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = open(); PreparedStatement st = conn.prepareStatement(SCHEMA)) {
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("project_env_vars 表初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    @Override
    public List<ProjectEnvVar> findAll(String projectId, String env) {
        requireProject(projectId);
        requireEnv(env);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM project_env_vars "
                             + "WHERE project_id = ? AND env = ? ORDER BY var_key")) {
            st.setString(1, projectId);
            st.setString(2, env);
            try (ResultSet rs = st.executeQuery()) {
                List<ProjectEnvVar> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("环境变量查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, String> resolve(String projectId, String env) {
        requireProject(projectId);
        requireEnv(env);
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT var_key, value_enc, value_iv FROM project_env_vars "
                             + "WHERE project_id = ? AND env = ? ORDER BY var_key")) {
            st.setString(1, projectId);
            st.setString(2, env);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("var_key"),
                            secretBox.decrypt(rs.getString("value_enc"), rs.getString("value_iv")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("环境变量读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public ProjectEnvVar save(String projectId, String env, String key, String value,
                              boolean secret, String updatedBy) {
        requireProject(projectId);
        requireEnv(env);
        requireKey(key);
        long now = System.currentTimeMillis();
        try (Connection conn = open()) {
            boolean keep = value == null || value.isEmpty();
            if (keep) {
                if (findOne(conn, projectId, env, key).isEmpty()) {
                    throw new IllegalArgumentException("新变量必须提供值：" + key);
                }
                try (PreparedStatement st = conn.prepareStatement("""
                        UPDATE project_env_vars SET is_secret=?, updated_by=?, updated_at=?
                        WHERE project_id=? AND env=? AND var_key=?
                        """)) {
                    st.setInt(1, secret ? 1 : 0);
                    st.setString(2, updatedBy);
                    st.setLong(3, now);
                    st.setString(4, projectId);
                    st.setString(5, env);
                    st.setString(6, key);
                    st.executeUpdate();
                }
            } else {
                SecretBox.Sealed sealed = secretBox.encrypt(value);
                try (PreparedStatement st = conn.prepareStatement("""
                        INSERT INTO project_env_vars
                            (project_id, env, var_key, value_enc, value_iv, is_secret, updated_by, updated_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        ON CONFLICT(project_id, env, var_key) DO UPDATE SET
                            value_enc=excluded.value_enc,
                            value_iv=excluded.value_iv,
                            is_secret=excluded.is_secret,
                            updated_by=excluded.updated_by,
                            updated_at=excluded.updated_at
                        """)) {
                    st.setString(1, projectId);
                    st.setString(2, env);
                    st.setString(3, key);
                    st.setString(4, sealed.cipherB64());
                    st.setString(5, sealed.ivB64());
                    st.setInt(6, secret ? 1 : 0);
                    st.setString(7, updatedBy);
                    st.setLong(8, now);
                    st.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("环境变量保存失败：" + e.getMessage(), e);
        }
        return findOne(projectId, env, key).orElseThrow();
    }

    @Override
    public void delete(String projectId, String env, String key) {
        requireProject(projectId);
        requireEnv(env);
        requireKey(key);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM project_env_vars WHERE project_id=? AND env=? AND var_key=?")) {
            st.setString(1, projectId);
            st.setString(2, env);
            st.setString(3, key);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("环境变量删除失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 内部

    private Optional<ProjectEnvVar> findOne(String projectId, String env, String key) {
        try (Connection conn = open()) {
            return findOne(conn, projectId, env, key);
        } catch (SQLException e) {
            throw new IllegalStateException("环境变量查询失败：" + e.getMessage(), e);
        }
    }

    private Optional<ProjectEnvVar> findOne(Connection conn, String projectId, String env, String key)
            throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM project_env_vars "
                        + "WHERE project_id=? AND env=? AND var_key=?")) {
            st.setString(1, projectId);
            st.setString(2, env);
            st.setString(3, key);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private ProjectEnvVar map(ResultSet rs) throws SQLException {
        String projectId = rs.getString("project_id");
        String env = rs.getString("env");
        String key = rs.getString("var_key");
        boolean secret = rs.getInt("is_secret") == 1;
        String plain;
        try {
            plain = secretBox.decrypt(rs.getString("value_enc"), rs.getString("value_iv"));
        } catch (RuntimeException e) {
            log.warn("环境变量解密失败 {}/{}/{}：{}", projectId, env, key, e.getMessage());
            return new ProjectEnvVar(projectId, env, key, secret, "****",
                    rs.getString("updated_by"), rs.getLong("updated_at"));
        }
        String display = secret ? secretBox.mask(plain) : plain;
        return new ProjectEnvVar(projectId, env, key, secret, display,
                rs.getString("updated_by"), rs.getLong("updated_at"));
    }

    private static void requireProject(String projectId) {
        if (projectId == null || !projectId.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("非法项目 ID：" + projectId);
        }
    }

    private static void requireEnv(String env) {
        if (!List.of("dev", "test", "prod").contains(env)) {
            throw new IllegalArgumentException("环境只能是 dev / test / prod：" + env);
        }
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("变量名不合法（需 [A-Za-z_][A-Za-z0-9_]*）：" + key);
        }
    }
}
