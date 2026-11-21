package top.rongx.agent.infra.settings;

import top.rongx.agent.domain.id.Ids;

import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.settings.ModelConnection;
import top.rongx.agent.domain.settings.ModelConnectionRepository;
import top.rongx.agent.infra.security.SecretBox;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 模型连接配置：API Key AES-GCM 加密落库，接口只回掩码。
 */
@Repository
public class SqliteModelConnectionStore implements ModelConnectionRepository {

    /** 显式列（规约 R08：禁止通配列查询）。 */
    private static final String COLUMNS = "id, name, provider, base_url, api_key_enc, api_key_iv, "
            + "model_name, temperature, max_iters, enabled, is_default, last_test_at, last_test_ok, "
            + "last_test_msg, last_test_latency_ms, updated_by, updated_at";

    private final PlatformDb db;
    private final SecretBox secretBox;

    @Autowired
    public SqliteModelConnectionStore(PlatformDb db, SecretBox secretBox) {
        this.db = db;
        this.secretBox = secretBox;
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public SqliteModelConnectionStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
            SecretBox secretBox) {
        this(PlatformDb.at(Path.of(workspaceRoot)), secretBox);
    }

    @Override
    public List<ModelConnection> findAll() {
        try {
            return db.read(em -> PlatformDb.rows(em,
                            "SELECT " + COLUMNS + " FROM model_connections "
                                    + "ORDER BY is_default DESC, updated_at DESC").stream()
                    .map(SqliteModelConnectionStore::map).toList());
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型列表查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ModelConnection> find(String id) {
        try {
            return db.read(em -> PlatformDb.row(em,
                            "SELECT " + COLUMNS + " FROM model_connections WHERE id = ?", id)
                    .map(SqliteModelConnectionStore::map));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ModelConnection> findDefault() {
        try {
            return db.read(em -> PlatformDb.row(em,
                            "SELECT " + COLUMNS + " FROM model_connections WHERE enabled = 1 "
                                    + "ORDER BY is_default DESC, updated_at DESC LIMIT 1")
                    .map(SqliteModelConnectionStore::map));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "默认模型查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public ModelConnection save(ModelConnectionView v, String apiKey, String updatedBy) {
        String id = v.id() == null || v.id().isBlank() ? Ids.newId("mdl") : v.id();
        long now = System.currentTimeMillis();
        try {
            db.inTx(em -> {
                boolean exists = PlatformDb.first(em,
                        "SELECT 1 FROM model_connections WHERE id = ?", id).isPresent();
                if (v.isDefault()) {
                    clearDefault(em);
                }
                SecretBox.Sealed sealed = apiKey == null || apiKey.isBlank()
                        ? null : secretBox.encrypt(apiKey);
                if (exists) {
                    update(em, v, id, sealed, updatedBy, now);
                } else {
                    insert(em, v, id, sealed, updatedBy, now);
                }
                return null;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型保存失败：" + e.getMessage(), e);
        }
        return find(id).orElseThrow();
    }

    private void insert(EntityManager em, ModelConnectionView v, String id,
                        SecretBox.Sealed sealed, String updatedBy, long now) {
        PlatformDb.update(em, """
                INSERT INTO model_connections (id, name, provider, base_url, api_key_enc, api_key_iv,
                    model_name, temperature, max_iters, enabled, is_default, updated_by, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id, v.name(), v.provider(), v.baseUrl(),
                sealed == null ? null : sealed.cipherB64(),
                sealed == null ? null : sealed.ivB64(),
                v.modelName(), v.temperature(), v.maxIters(), v.enabled() ? 1 : 0,
                v.isDefault() ? 1 : 0, updatedBy, now);
    }

    private void update(EntityManager em, ModelConnectionView v, String id,
                        SecretBox.Sealed sealed, String updatedBy, long now) {
        if (sealed == null) {
            PlatformDb.update(em, """
                    UPDATE model_connections SET name=?, provider=?, base_url=?, model_name=?,
                        temperature=?, max_iters=?, enabled=?, is_default=?, updated_by=?, updated_at=?
                    WHERE id=?
                    """,
                    v.name(), v.provider(), v.baseUrl(), v.modelName(), v.temperature(),
                    v.maxIters(), v.enabled() ? 1 : 0, v.isDefault() ? 1 : 0, updatedBy, now, id);
        } else {
            PlatformDb.update(em, """
                    UPDATE model_connections SET name=?, provider=?, base_url=?, api_key_enc=?,
                        api_key_iv=?, model_name=?, temperature=?, max_iters=?, enabled=?,
                        is_default=?, updated_by=?, updated_at=? WHERE id=?
                    """,
                    v.name(), v.provider(), v.baseUrl(), sealed.cipherB64(), sealed.ivB64(),
                    v.modelName(), v.temperature(), v.maxIters(), v.enabled() ? 1 : 0,
                    v.isDefault() ? 1 : 0, updatedBy, now, id);
        }
    }

    private void clearDefault(EntityManager em) {
        PlatformDb.update(em, "UPDATE model_connections SET is_default = 0 WHERE is_default = 1");
    }

    @Override
    public void delete(String id) {
        try {
            db.run(em -> PlatformDb.update(em, "DELETE FROM model_connections WHERE id = ?", id));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "模型删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void markDefault(String id) {
        try {
            db.inTx(em -> {
                clearDefault(em);
                PlatformDb.update(em,
                        "UPDATE model_connections SET is_default = 1, updated_at = ? WHERE id = ?",
                        System.currentTimeMillis(), id);
                return null;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "设置默认模型失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void recordTest(String id, boolean ok, String message, Long latencyMs) {
        try {
            db.run(em -> PlatformDb.update(em, """
                    UPDATE model_connections SET last_test_at=?, last_test_ok=?, last_test_msg=?,
                        last_test_latency_ms=? WHERE id=?
                    """, System.currentTimeMillis(), ok ? 1 : 0, message, latencyMs, id));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "测试结果写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> apiKeyOf(String id) {
        try {
            return db.read(em -> PlatformDb.row(em,
                            "SELECT api_key_enc, api_key_iv FROM model_connections WHERE id = ?", id)
                    .flatMap(row -> {
                        String enc = (String) row[0];
                        String iv = (String) row[1];
                        if (enc == null || iv == null) {
                            return Optional.empty();
                        }
                        return Optional.of(secretBox.decrypt(enc, iv));
                    }));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "API Key 读取失败：" + e.getMessage(), e);
        }
    }

    private static ModelConnection map(Object[] row) {
        boolean hasKey = row[4] != null;
        return new ModelConnection(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[6],
                ((Number) row[7]).doubleValue(),
                ((Number) row[8]).intValue(),
                ((Number) row[9]).intValue() == 1,
                ((Number) row[10]).intValue() == 1,
                hasKey,
                hasKey ? "****" : null,
                nullableLong(row[11]),
                row[12] == null ? null : ((Number) row[12]).intValue() == 1,
                (String) row[13],
                nullableLong(row[14]),
                (String) row[15],
                ((Number) row[16]).longValue());
    }

    /** SQLite 的 INTEGER 可能映射为 Integer/Long，统一按 Number 读取。 */
    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

}
