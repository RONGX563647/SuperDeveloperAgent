package top.rongx.agent.infra.settings;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.settings.ModelCatalogEntry;
import top.rongx.agent.domain.settings.ModelCatalogRepository;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 模型目录：与模型连接共用统一库。
 *
 * <p>自动发现的条目每次同步整批覆盖；手工填写的模型名保留，
 * 这样没有 /models 的本地网关也能正常使用。
 */
@Repository
public class SqliteModelCatalogStore implements ModelCatalogRepository {

    private static final String COLUMNS =
            "connection_id, model_id, display_name, source, updated_at";

    private static final String UPSERT_SQL = """
            INSERT INTO model_catalog
                (connection_id, model_id, display_name, source, updated_at)
            VALUES (?,?,?,?,?)
            ON CONFLICT(connection_id, model_id) DO UPDATE SET
                display_name = excluded.display_name,
                source = excluded.source,
                updated_at = excluded.updated_at
            """;

    private final PlatformDb db;

    @Autowired
    public SqliteModelCatalogStore(PlatformDb db) {
        this.db = db;
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public SqliteModelCatalogStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this(PlatformDb.sqlite(Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.db")));
    }

    @Override
    public List<ModelCatalogEntry> findByConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return List.of();
        }
        try {
            return db.read(em -> PlatformDb.rows(em, "SELECT " + COLUMNS
                            + " FROM model_catalog WHERE connection_id = ? ORDER BY model_id",
                            connectionId).stream()
                    .map(SqliteModelCatalogStore::map).toList());
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型目录查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ModelCatalogEntry> find(String connectionId, String modelId) {
        if (connectionId == null || connectionId.isBlank()
                || modelId == null || modelId.isBlank()) {
            return Optional.empty();
        }
        try {
            return db.read(em -> PlatformDb.row(em, "SELECT " + COLUMNS
                            + " FROM model_catalog WHERE connection_id = ? AND model_id = ?",
                            connectionId, modelId.trim())
                    .map(SqliteModelCatalogStore::map));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型目录查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void replaceDiscovered(String connectionId, List<ModelCatalogEntry> entries) {
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        try {
            db.inTx(em -> {
                PlatformDb.update(em,
                        "DELETE FROM model_catalog WHERE connection_id = ? AND source = 'discover'",
                        connectionId);
                long now = System.currentTimeMillis();
                for (ModelCatalogEntry entry : entries) {
                    PlatformDb.update(em, UPSERT_SQL, connectionId, entry.modelId(),
                            entry.displayName(), "discover", now);
                }
                return null;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型目录同步失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void upsert(ModelCatalogEntry entry) {
        try {
            db.run(em -> PlatformDb.update(em, UPSERT_SQL,
                    entry.connectionId(), entry.modelId(), entry.displayName(),
                    entry.source(), System.currentTimeMillis()));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型目录写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteByConnection(String connectionId) {
        try {
            db.run(em -> PlatformDb.update(em,
                    "DELETE FROM model_catalog WHERE connection_id = ?", connectionId));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型目录删除失败：" + e.getMessage(), e);
        }
    }

    private static ModelCatalogEntry map(Object[] row) {
        return new ModelCatalogEntry(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                ((Number) row[4]).longValue());
    }
}
