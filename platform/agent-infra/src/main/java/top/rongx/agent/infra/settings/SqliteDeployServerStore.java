package top.rongx.agent.infra.settings;

import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;
import top.rongx.agent.infra.security.SecretBox;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 部署服务器配置（JPA）：密码/口令 AES-GCM 加密落库，接口只回掩码。
 *
 * <p>默认唯一性在写入事务内保证：设默认时先把其它行清零。
 */
@Repository
public class SqliteDeployServerStore implements DeployServerRepository {

    /** 显式列（规约 R08：禁止通配列查询）。 */
    private static final String COLUMNS = "id, name, host, port, ssh_user, auth_type, key_path, "
            + "secret_enc, secret_iv, remote_dir, enabled, is_default, last_test_at, last_test_ok, "
            + "last_test_msg, updated_by, updated_at";

    private static final String INSERT_SQL = """
            INSERT INTO deploy_servers (id, name, host, port, ssh_user, auth_type, key_path,
                secret_enc, secret_iv, remote_dir, enabled, is_default, updated_by, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private static final String UPDATE_WITH_SECRET_SQL = """
            UPDATE deploy_servers SET name=?, host=?, port=?, ssh_user=?, auth_type=?, key_path=?,
                secret_enc=?, secret_iv=?, remote_dir=?, enabled=?, is_default=?,
                updated_by=?, updated_at=? WHERE id=?
            """;

    private static final String UPDATE_KEEP_SECRET_SQL = """
            UPDATE deploy_servers SET name=?, host=?, port=?, ssh_user=?, auth_type=?, key_path=?,
                remote_dir=?, enabled=?, is_default=?, updated_by=?, updated_at=? WHERE id=?
            """;

    private final PlatformDb db;
    private final SecretBox secretBox;

    @Autowired
    public SqliteDeployServerStore(PlatformDb db, SecretBox secretBox) {
        this.db = db;
        this.secretBox = secretBox;
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public SqliteDeployServerStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
            SecretBox secretBox) {
        this(PlatformDb.sqlite(Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.db")), secretBox);
    }

    @Override
    public List<DeployServer> findAll() {
        try {
            return db.read(em -> PlatformDb.rows(em,
                    "SELECT " + COLUMNS + " FROM deploy_servers "
                            + "ORDER BY is_default DESC, updated_at DESC").stream()
                    .map(SqliteDeployServerStore::map).toList());
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "服务器列表查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<DeployServer> find(String id) {
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + COLUMNS + " FROM deploy_servers WHERE id = ?", id)
                    .map(SqliteDeployServerStore::map));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "服务器查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<DeployServer> findDefault() {
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + COLUMNS + " FROM deploy_servers WHERE enabled = 1 "
                            + "ORDER BY is_default DESC, updated_at DESC LIMIT 1")
                    .map(SqliteDeployServerStore::map));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "默认服务器查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public DeployServer save(DeployServerView view, String secret, String updatedBy) {
        String id = view.id() == null || view.id().isBlank() ? newId() : view.id();
        long now = System.currentTimeMillis();
        SecretBox.Sealed sealed = secret == null || secret.isBlank()
                ? null : secretBox.encrypt(secret);
        try {
            db.inTx(em -> {
                boolean exists = PlatformDb.first(em,
                        "SELECT 1 FROM deploy_servers WHERE id = ?", id).isPresent();
                if (view.isDefault()) {
                    PlatformDb.update(em, "UPDATE deploy_servers SET is_default = 0 WHERE is_default = 1");
                }
                if (exists) {
                    update(em, view, id, sealed, updatedBy, now);
                } else {
                    insert(em, view, id, sealed, updatedBy, now);
                }
                return null;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "服务器保存失败：" + e.getMessage(), e);
        }
        return find(id).orElseThrow();
    }

    private static void insert(EntityManager em, DeployServerView v, String id,
                               SecretBox.Sealed sealed, String updatedBy, long now) {
        PlatformDb.update(em, INSERT_SQL,
                id,
                v.name(),
                v.host(),
                v.port(),
                v.sshUser(),
                v.authType(),
                v.keyPath(),
                sealed == null ? null : sealed.cipherB64(),
                sealed == null ? null : sealed.ivB64(),
                v.remoteDir(),
                v.enabled() ? 1L : 0L,
                v.isDefault() ? 1L : 0L,
                updatedBy,
                now);
    }

    private static void update(EntityManager em, DeployServerView v, String id,
                               SecretBox.Sealed sealed, String updatedBy, long now) {
        if (sealed == null) {
            PlatformDb.update(em, UPDATE_KEEP_SECRET_SQL,
                    v.name(),
                    v.host(),
                    v.port(),
                    v.sshUser(),
                    v.authType(),
                    v.keyPath(),
                    v.remoteDir(),
                    v.enabled() ? 1L : 0L,
                    v.isDefault() ? 1L : 0L,
                    updatedBy,
                    now,
                    id);
            return;
        }
        PlatformDb.update(em, UPDATE_WITH_SECRET_SQL,
                v.name(),
                v.host(),
                v.port(),
                v.sshUser(),
                v.authType(),
                v.keyPath(),
                sealed.cipherB64(),
                sealed.ivB64(),
                v.remoteDir(),
                v.enabled() ? 1L : 0L,
                v.isDefault() ? 1L : 0L,
                updatedBy,
                now,
                id);
    }

    @Override
    public void delete(String id) {
        try {
            db.run(em -> PlatformDb.update(em, "DELETE FROM deploy_servers WHERE id = ?", id));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "服务器删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void markDefault(String id) {
        try {
            db.run(em -> {
                PlatformDb.update(em, "UPDATE deploy_servers SET is_default = 0 WHERE is_default = 1");
                PlatformDb.update(em,
                        "UPDATE deploy_servers SET is_default = 1, updated_at = ? WHERE id = ?",
                        System.currentTimeMillis(), id);
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "设置默认服务器失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void recordTest(String id, boolean ok, String message, Long latencyMs) {
        try {
            db.run(em -> PlatformDb.update(em, """
                    UPDATE deploy_servers SET last_test_at=?, last_test_ok=?, last_test_msg=?
                    WHERE id=?
                    """, System.currentTimeMillis(), ok ? 1L : 0L, message, id));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "测试结果写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> secretOf(String id) {
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT secret_enc, secret_iv FROM deploy_servers WHERE id = ?", id)
                    .flatMap(row -> {
                        String enc = (String) row[0];
                        String iv = (String) row[1];
                        return enc == null || iv == null
                                ? Optional.empty()
                                : Optional.of(secretBox.decrypt(enc, iv));
                    }));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "口令读取失败：" + e.getMessage(), e);
        }
    }

    private static DeployServer map(Object[] row) {
        String enc = (String) row[7];
        String masked = enc == null ? null : "****";
        return new DeployServer(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                ((Number) row[3]).intValue(),
                (String) row[4],
                (String) row[5],
                (String) row[6],
                (String) row[9],
                ((Number) row[10]).intValue() == 1,
                ((Number) row[11]).intValue() == 1,
                enc != null,
                masked,
                nullableLong(row[12]),
                row[13] == null ? null : ((Number) row[13]).intValue() == 1,
                (String) row[14],
                (String) row[15],
                ((Number) row[16]).longValue());
    }

    /** SQLite 的 INTEGER 可能映射为 Integer/Long，统一按 Number 读取。 */
    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private String newId() {
        byte[] raw = new byte[4];
        new SecureRandom().nextBytes(raw);
        return "srv-" + HexFormat.of().formatHex(raw);
    }
}
