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
import top.rongx.agent.domain.settings.ModelConnection;
import top.rongx.agent.domain.settings.ModelConnectionRepository;
import top.rongx.agent.infra.security.SecretBox;

/**
 * 模型连接配置（SQLite）：API Key AES-GCM 加密落库，接口只回掩码。
 */
@Repository
public class SqliteModelConnectionStore implements ModelConnectionRepository {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS model_connections (
                id            TEXT PRIMARY KEY,
                name          TEXT NOT NULL,
                provider      TEXT NOT NULL,
                base_url      TEXT NOT NULL,
                api_key_enc   TEXT,
                api_key_iv    TEXT,
                model_name    TEXT NOT NULL,
                temperature   REAL NOT NULL DEFAULT 0.2,
                max_iters     INTEGER NOT NULL DEFAULT 8,
                enabled       INTEGER NOT NULL DEFAULT 1,
                is_default    INTEGER NOT NULL DEFAULT 0,
                last_test_at  INTEGER,
                last_test_ok  INTEGER,
                last_test_msg TEXT,
                last_test_latency_ms INTEGER,
                updated_by    TEXT,
                updated_at    INTEGER NOT NULL
            )
            """;

    /** 显式列（规约 R08：禁止通配列查询）。 */
    private static final String COLUMNS = "id, name, provider, base_url, api_key_enc, api_key_iv, "
            + "model_name, temperature, max_iters, enabled, is_default, last_test_at, last_test_ok, "
            + "last_test_msg, last_test_latency_ms, updated_by, updated_at";

    private final Path dbFile;
    private final SecretBox secretBox;

    public SqliteModelConnectionStore(
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
            throw new IllegalStateException("model_connections 表初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    @Override
    public List<ModelConnection> findAll() {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM model_connections "
                             + "ORDER BY is_default DESC, updated_at DESC")) {
            try (ResultSet rs = st.executeQuery()) {
                List<ModelConnection> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("模型列表查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ModelConnection> find(String id) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM model_connections WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("模型查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ModelConnection> findDefault() {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM model_connections WHERE enabled = 1 "
                             + "ORDER BY is_default DESC, updated_at DESC LIMIT 1")) {
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("默认模型查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public ModelConnection save(ModelConnectionView v, String apiKey, String updatedBy) {
        String id = v.id() == null || v.id().isBlank() ? newId() : v.id();
        long now = System.currentTimeMillis();
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                boolean exists = exists(conn, id);
                if (v.isDefault()) {
                    clearDefault(conn);
                }
                SecretBox.Sealed sealed = apiKey == null || apiKey.isBlank()
                        ? null : secretBox.encrypt(apiKey);
                if (exists) {
                    update(conn, v, id, sealed, updatedBy, now);
                } else {
                    insert(conn, v, id, sealed, updatedBy, now);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("模型保存失败：" + e.getMessage(), e);
        }
        return find(id).orElseThrow();
    }

    private void insert(Connection conn, ModelConnectionView v, String id,
                        SecretBox.Sealed sealed, String updatedBy, long now) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("""
                INSERT INTO model_connections (id, name, provider, base_url, api_key_enc, api_key_iv,
                    model_name, temperature, max_iters, enabled, is_default, updated_by, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            st.setString(1, id);
            st.setString(2, v.name());
            st.setString(3, v.provider());
            st.setString(4, v.baseUrl());
            st.setString(5, sealed == null ? null : sealed.cipherB64());
            st.setString(6, sealed == null ? null : sealed.ivB64());
            st.setString(7, v.modelName());
            st.setDouble(8, v.temperature());
            st.setInt(9, v.maxIters());
            st.setInt(10, v.enabled() ? 1 : 0);
            st.setInt(11, v.isDefault() ? 1 : 0);
            st.setString(12, updatedBy);
            st.setLong(13, now);
            st.executeUpdate();
        }
    }

    private void update(Connection conn, ModelConnectionView v, String id,
                        SecretBox.Sealed sealed, String updatedBy, long now) throws SQLException {
        String sql = sealed == null
                ? """
                UPDATE model_connections SET name=?, provider=?, base_url=?, model_name=?,
                    temperature=?, max_iters=?, enabled=?, is_default=?, updated_by=?, updated_at=?
                WHERE id=?
                """
                : """
                UPDATE model_connections SET name=?, provider=?, base_url=?, api_key_enc=?,
                    api_key_iv=?, model_name=?, temperature=?, max_iters=?, enabled=?,
                    is_default=?, updated_by=?, updated_at=? WHERE id=?
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            int i = 1;
            st.setString(i++, v.name());
            st.setString(i++, v.provider());
            st.setString(i++, v.baseUrl());
            if (sealed != null) {
                st.setString(i++, sealed.cipherB64());
                st.setString(i++, sealed.ivB64());
            }
            st.setString(i++, v.modelName());
            st.setDouble(i++, v.temperature());
            st.setInt(i++, v.maxIters());
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
                "SELECT 1 FROM model_connections WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void clearDefault(Connection conn) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "UPDATE model_connections SET is_default = 0 WHERE is_default = 1")) {
            st.executeUpdate();
        }
    }

    @Override
    public void delete(String id) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM model_connections WHERE id = ?")) {
            st.setString(1, id);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("模型删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void markDefault(String id) {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                clearDefault(conn);
                try (PreparedStatement st = conn.prepareStatement(
                        "UPDATE model_connections SET is_default = 1, updated_at = ? WHERE id = ?")) {
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
            throw new IllegalStateException("设置默认模型失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void recordTest(String id, boolean ok, String message, Long latencyMs) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE model_connections SET last_test_at=?, last_test_ok=?, last_test_msg=?,
                         last_test_latency_ms=? WHERE id=?
                     """)) {
            st.setLong(1, System.currentTimeMillis());
            st.setInt(2, ok ? 1 : 0);
            st.setString(3, message);
            if (latencyMs == null) {
                st.setNull(4, java.sql.Types.INTEGER);
            } else {
                st.setLong(4, latencyMs);
            }
            st.setString(5, id);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("测试结果写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> apiKeyOf(String id) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT api_key_enc, api_key_iv FROM model_connections WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String enc = rs.getString("api_key_enc");
                String iv = rs.getString("api_key_iv");
                if (enc == null || iv == null) {
                    return Optional.empty();
                }
                return Optional.of(secretBox.decrypt(enc, iv));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("API Key 读取失败：" + e.getMessage(), e);
        }
    }

    private ModelConnection map(ResultSet rs) throws SQLException {
        boolean hasKey = rs.getString("api_key_enc") != null;
        return new ModelConnection(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("provider"),
                rs.getString("base_url"),
                rs.getString("model_name"),
                rs.getDouble("temperature"),
                rs.getInt("max_iters"),
                rs.getInt("enabled") == 1,
                rs.getInt("is_default") == 1,
                hasKey,
                hasKey ? "****" : null,
                nullableLong(rs, "last_test_at"),
                rs.getObject("last_test_ok") == null ? null : rs.getInt("last_test_ok") == 1,
                rs.getString("last_test_msg"),
                nullableLong(rs, "last_test_latency_ms"),
                rs.getString("updated_by"),
                rs.getLong("updated_at"));
    }

    /** SQLite 的 INTEGER 可能映射为 Integer/Long，统一按 Number 读取。 */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private String newId() {
        byte[] raw = new byte[4];
        new SecureRandom().nextBytes(raw);
        return "mdl-" + HexFormat.of().formatHex(raw);
    }
}
