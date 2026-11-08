package top.rongx.agent.infra.settings;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;
import top.rongx.agent.infra.security.SecretBox;

/**
 * 部署服务器配置（SQLite）：密码/口令 AES-GCM 加密落库，接口只回掩码。
 *
 * <p>默认唯一性在写入事务内保证：设默认时先把其它行清零。
 */
@Repository
public class SqliteDeployServerStore implements DeployServerRepository {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS deploy_servers (
                id           TEXT PRIMARY KEY,
                name         TEXT NOT NULL,
                host         TEXT NOT NULL,
                port         INTEGER NOT NULL DEFAULT 22,
                ssh_user     TEXT NOT NULL,
                auth_type    TEXT NOT NULL,
                key_path     TEXT,
                secret_enc   TEXT,
                secret_iv    TEXT,
                remote_dir   TEXT NOT NULL DEFAULT '/opt/agent/deploy',
                enabled      INTEGER NOT NULL DEFAULT 1,
                is_default   INTEGER NOT NULL DEFAULT 0,
                last_test_at INTEGER,
                last_test_ok INTEGER,
                last_test_msg TEXT,
                updated_by   TEXT,
                updated_at   INTEGER NOT NULL
            )
            """;

    /** 显式列（规约 R08：禁止通配列查询）。 */
    private static final String COLUMNS = "id, name, host, port, ssh_user, auth_type, key_path, "
            + "secret_enc, secret_iv, remote_dir, enabled, is_default, last_test_at, last_test_ok, "
            + "last_test_msg, updated_by, updated_at";

    private final Path dbFile;
    private final SecretBox secretBox;

    public SqliteDeployServerStore(
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
            throw new IllegalStateException("deploy_servers 表初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    @Override
    public List<DeployServer> findAll() {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM deploy_servers "
                             + "ORDER BY is_default DESC, updated_at DESC")) {
            try (ResultSet rs = st.executeQuery()) {
                List<DeployServer> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("服务器列表查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<DeployServer> find(String id) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM deploy_servers WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("服务器查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<DeployServer> findDefault() {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM deploy_servers WHERE enabled = 1 "
                             + "ORDER BY is_default DESC, updated_at DESC LIMIT 1")) {
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("默认服务器查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public DeployServer save(DeployServerView view, String secret, String updatedBy) {
        String id = view.id() == null || view.id().isBlank() ? newId() : view.id();
        long now = System.currentTimeMillis();
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                boolean exists = exists(conn, id);
                if (view.isDefault()) {
                    clearDefault(conn);
                }
                SecretBox.Sealed sealed = secret == null || secret.isBlank()
                        ? null : secretBox.encrypt(secret);
                if (exists) {
                    update(conn, view, id, sealed, updatedBy, now);
                } else {
                    insert(conn, view, id, sealed, updatedBy, now);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("服务器保存失败：" + e.getMessage(), e);
        }
        return find(id).orElseThrow();
    }

    private void insert(Connection conn, DeployServerView v, String id,
                        SecretBox.Sealed sealed, String updatedBy, long now) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("""
                INSERT INTO deploy_servers (id, name, host, port, ssh_user, auth_type, key_path,
                    secret_enc, secret_iv, remote_dir, enabled, is_default, updated_by, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            st.setString(1, id);
            st.setString(2, v.name());
            st.setString(3, v.host());
            st.setInt(4, v.port());
            st.setString(5, v.sshUser());
            st.setString(6, v.authType());
            st.setString(7, v.keyPath());
            st.setString(8, sealed == null ? null : sealed.cipherB64());
            st.setString(9, sealed == null ? null : sealed.ivB64());
            st.setString(10, v.remoteDir());
            st.setInt(11, v.enabled() ? 1 : 0);
            st.setInt(12, v.isDefault() ? 1 : 0);
            st.setString(13, updatedBy);
            st.setLong(14, now);
            st.executeUpdate();
        }
    }

    private void update(Connection conn, DeployServerView v, String id,
                        SecretBox.Sealed sealed, String updatedBy, long now) throws SQLException {
        String sql = sealed == null
                ? """
                UPDATE deploy_servers SET name=?, host=?, port=?, ssh_user=?, auth_type=?, key_path=?,
                    remote_dir=?, enabled=?, is_default=?, updated_by=?, updated_at=? WHERE id=?
                """
                : """
                UPDATE deploy_servers SET name=?, host=?, port=?, ssh_user=?, auth_type=?, key_path=?,
                    secret_enc=?, secret_iv=?, remote_dir=?, enabled=?, is_default=?,
                    updated_by=?, updated_at=? WHERE id=?
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            int i = 1;
            st.setString(i++, v.name());
            st.setString(i++, v.host());
            st.setInt(i++, v.port());
            st.setString(i++, v.sshUser());
            st.setString(i++, v.authType());
            st.setString(i++, v.keyPath());
            if (sealed != null) {
                st.setString(i++, sealed.cipherB64());
                st.setString(i++, sealed.ivB64());
            }
            st.setString(i++, v.remoteDir());
            st.setInt(i++, v.enabled() ? 1 : 0);
            st.setInt(i++, v.isDefault() ? 1 : 0);
            st.setString(i++, updatedBy);
            st.setLong(i++, now);
            st.setString(i, id);
            st.executeUpdate();
        }
    }

    private boolean exists(Connection conn, String id) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT 1 FROM deploy_servers WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void clearDefault(Connection conn) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "UPDATE deploy_servers SET is_default = 0 WHERE is_default = 1")) {
            st.executeUpdate();
        }
    }

    @Override
    public void delete(String id) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("DELETE FROM deploy_servers WHERE id = ?")) {
            st.setString(1, id);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("服务器删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void markDefault(String id) {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                clearDefault(conn);
                try (PreparedStatement st = conn.prepareStatement(
                        "UPDATE deploy_servers SET is_default = 1, updated_at = ? WHERE id = ?")) {
                    st.setLong(1, System.currentTimeMillis());
                    st.setString(2, id);
                    st.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("设置默认服务器失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void recordTest(String id, boolean ok, String message, Long latencyMs) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE deploy_servers SET last_test_at=?, last_test_ok=?, last_test_msg=?
                     WHERE id=?
                     """)) {
            st.setLong(1, System.currentTimeMillis());
            st.setInt(2, ok ? 1 : 0);
            st.setString(3, message);
            st.setString(4, id);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("测试结果写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> secretOf(String id) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT secret_enc, secret_iv FROM deploy_servers WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String enc = rs.getString("secret_enc");
                String iv = rs.getString("secret_iv");
                if (enc == null || iv == null) {
                    return Optional.empty();
                }
                return Optional.of(secretBox.decrypt(enc, iv));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("口令读取失败：" + e.getMessage(), e);
        }
    }

    private DeployServer map(ResultSet rs) throws SQLException {
        String enc = rs.getString("secret_enc");
        String masked = enc == null ? null : "****";
        return new DeployServer(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("host"),
                rs.getInt("port"),
                rs.getString("ssh_user"),
                rs.getString("auth_type"),
                rs.getString("key_path"),
                rs.getString("remote_dir"),
                rs.getInt("enabled") == 1,
                rs.getInt("is_default") == 1,
                enc != null,
                masked,
                (Long) rs.getObject("last_test_at"),
                rs.getObject("last_test_ok") == null ? null : rs.getInt("last_test_ok") == 1,
                rs.getString("last_test_msg"),
                rs.getString("updated_by"),
                rs.getLong("updated_at"));
    }

    private String newId() {
        byte[] raw = new byte[4];
        new SecureRandom().nextBytes(raw);
        return "srv-" + HexFormat.of().formatHex(raw);
    }
}
