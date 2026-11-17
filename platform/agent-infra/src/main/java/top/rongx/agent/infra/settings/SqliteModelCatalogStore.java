package top.rongx.agent.infra.settings;

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
import top.rongx.agent.domain.settings.ModelCatalogEntry;
import top.rongx.agent.domain.settings.ModelCatalogRepository;
import top.rongx.agent.infra.sqlite.SqliteConnections;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;

/**
 * 模型目录（SQLite）：与模型连接共用 {@code _platform/projects.db}。
 *
 * <p>自动发现的条目每次同步整批覆盖；手工填写的模型名保留，
 * 这样没有 /models 的本地网关也能正常使用。
 */
@Repository
public class SqliteModelCatalogStore implements ModelCatalogRepository {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS model_catalog (
                connection_id TEXT NOT NULL,
                model_id      TEXT NOT NULL,
                display_name  TEXT NOT NULL,
                source        TEXT NOT NULL,
                updated_at    INTEGER NOT NULL,
                PRIMARY KEY (connection_id, model_id)
            )
            """;

    private static final String COLUMNS =
            "connection_id, model_id, display_name, source, updated_at";

    private final Path dbFile;

    public SqliteModelCatalogStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("projects.db");
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = open(); PreparedStatement st = conn.prepareStatement(SCHEMA)) {
            st.executeUpdate();
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "model_catalog 表初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return SqliteConnections.get(dbFile);
    }

    @Override
    public List<ModelCatalogEntry> findByConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return List.of();
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("SELECT " + COLUMNS
                     + " FROM model_catalog WHERE connection_id = ? ORDER BY model_id")) {
            st.setString(1, connectionId);
            try (ResultSet rs = st.executeQuery()) {
                List<ModelCatalogEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型目录查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ModelCatalogEntry> find(String connectionId, String modelId) {
        if (connectionId == null || connectionId.isBlank()
                || modelId == null || modelId.isBlank()) {
            return Optional.empty();
        }
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("SELECT " + COLUMNS
                     + " FROM model_catalog WHERE connection_id = ? AND model_id = ?")) {
            st.setString(1, connectionId);
            st.setString(2, modelId.trim());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型目录查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void replaceDiscovered(String connectionId, List<ModelCatalogEntry> entries) {
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement st = conn.prepareStatement(
                        "DELETE FROM model_catalog WHERE connection_id = ? AND source = 'discover'")) {
                    st.setString(1, connectionId);
                    st.executeUpdate();
                }
                try (PreparedStatement st = conn.prepareStatement("""
                        INSERT OR REPLACE INTO model_catalog
                            (connection_id, model_id, display_name, source, updated_at)
                        VALUES (?,?,?,?,?)
                        """)) {
                    long now = System.currentTimeMillis();
                    for (ModelCatalogEntry entry : entries) {
                        st.setString(1, connectionId);
                        st.setString(2, entry.modelId());
                        st.setString(3, entry.displayName());
                        st.setString(4, "discover");
                        st.setLong(5, now);
                        st.addBatch();
                    }
                    st.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型目录同步失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void upsert(ModelCatalogEntry entry) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO model_catalog
                         (connection_id, model_id, display_name, source, updated_at)
                     VALUES (?,?,?,?,?)
                     ON CONFLICT(connection_id, model_id) DO UPDATE SET
                         display_name = excluded.display_name,
                         source = excluded.source,
                         updated_at = excluded.updated_at
                     """)) {
            st.setString(1, entry.connectionId());
            st.setString(2, entry.modelId());
            st.setString(3, entry.displayName());
            st.setString(4, entry.source());
            st.setLong(5, System.currentTimeMillis());
            st.executeUpdate();
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型目录写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteByConnection(String connectionId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM model_catalog WHERE connection_id = ?")) {
            st.setString(1, connectionId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型目录删除失败：" + e.getMessage(), e);
        }
    }

    private ModelCatalogEntry map(ResultSet rs) throws SQLException {
        return new ModelCatalogEntry(
                rs.getString("connection_id"),
                rs.getString("model_id"),
                rs.getString("display_name"),
                rs.getString("source"),
                rs.getLong("updated_at"));
    }
}
