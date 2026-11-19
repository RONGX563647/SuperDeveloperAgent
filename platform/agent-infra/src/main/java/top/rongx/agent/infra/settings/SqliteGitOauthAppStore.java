package top.rongx.agent.infra.settings;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.settings.GitOauthApp;
import top.rongx.agent.domain.settings.GitOauthAppRepository;
import top.rongx.agent.infra.security.SecretBox;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.IntakeException;
import top.rongx.agent.domain.error.PlatformException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * Git 平台 OAuth 应用配置（JPA）。
 *
 * <p>一张表按 provider 存平台级应用凭据：client-secret AES-GCM 加密，
 * 接口只回掩码；保存时 secret 留空表示沿用旧值。
 */
@Repository
public class SqliteGitOauthAppStore implements GitOauthAppRepository {

    private static final String COLUMNS =
            "provider, client_id, secret_enc, secret_iv, redirect_uri, scopes, enabled, updated_at";

    private static final String INSERT_SQL = """
            INSERT INTO git_oauth_apps
                (provider, client_id, secret_enc, secret_iv, redirect_uri, scopes, enabled, updated_at)
            VALUES (?,?,?,?,?,?,?,?)
            """;

    private static final String UPDATE_SQL = """
            UPDATE git_oauth_apps
            SET client_id=?, secret_enc=?, secret_iv=?, redirect_uri=?, scopes=?, enabled=?, updated_at=?
            WHERE provider=?
            """;

    private final PlatformDb db;
    private final SecretBox secretBox;

    @Autowired
    public SqliteGitOauthAppStore(PlatformDb db, SecretBox secretBox) {
        this.db = db;
        this.secretBox = secretBox;
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public SqliteGitOauthAppStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
            SecretBox secretBox) {
        this(PlatformDb.sqlite(Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.db")), secretBox);
    }

    @Override
    public List<GitOauthApp> findAll() {
        try {
            return db.read(em -> PlatformDb.rows(em,
                    "SELECT " + COLUMNS + " FROM git_oauth_apps ORDER BY provider").stream()
                    .map(this::map).toList());
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "OAuth 应用配置查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<GitOauthApp> find(String provider) {
        String normalized = normalizeProvider(provider);
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + COLUMNS + " FROM git_oauth_apps WHERE provider = ?",
                    normalized).map(this::map));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "OAuth 应用配置查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public GitOauthApp save(GitOauthAppView view, String clientSecret) {
        String provider = normalizeProvider(view.provider());
        String clientId = view.clientId() == null ? "" : view.clientId().trim();
        if (clientId.isBlank()) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "Client ID 不能为空");
        }
        String redirectUri = view.redirectUri() == null ? "" : view.redirectUri().trim();
        if (redirectUri.isBlank()) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "回调地址不能为空");
        }
        String scopes = view.scopes() == null || view.scopes().isBlank()
                ? defaultScopes(provider) : view.scopes().trim();
        long now = System.currentTimeMillis();
        try {
            SecretBox.Sealed existing = existingSecret(provider);
            SecretBox.Sealed sealed = clientSecret != null && !clientSecret.isBlank()
                    ? secretBox.encrypt(clientSecret.trim()) : existing;
            if (sealed == null) {
                throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "首次配置必须填写 Client Secret");
            }
            long enabled = view.enabled() ? 1L : 0L;
            if (existing == null) {
                db.run(em -> PlatformDb.update(em, INSERT_SQL,
                        provider, clientId, sealed.cipherB64(), sealed.ivB64(),
                        redirectUri, scopes, enabled, now));
            } else {
                db.run(em -> PlatformDb.update(em, UPDATE_SQL,
                        clientId, sealed.cipherB64(), sealed.ivB64(), redirectUri, scopes,
                        enabled, now, provider));
            }
            return find(provider).orElseThrow();
        } catch (PlatformException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "OAuth 应用配置保存失败：" + e.getMessage(), e);
        }
    }

    private SecretBox.Sealed existingSecret(String provider) {
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT secret_enc, secret_iv FROM git_oauth_apps WHERE provider = ?", provider)
                    .map(row -> new SecretBox.Sealed((String) row[0], (String) row[1]))
                    .orElse(null));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "OAuth 应用配置查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> secretOf(String provider) {
        String normalized = normalizeProvider(provider);
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT secret_enc, secret_iv FROM git_oauth_apps WHERE provider = ?",
                    normalized)
                    .map(row -> secretBox.decrypt((String) row[0], (String) row[1])));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "Client Secret 读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String provider) {
        String normalized = normalizeProvider(provider);
        try {
            db.run(em -> PlatformDb.update(em,
                    "DELETE FROM git_oauth_apps WHERE provider = ?", normalized));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "OAuth 应用配置删除失败：" + e.getMessage(), e);
        }
    }

    private GitOauthApp map(Object[] row) {
        String plain = secretBox.decrypt((String) row[2], (String) row[3]);
        String masked = plain.length() <= 4 ? "***" : plain.substring(0, 4) + "***";
        return new GitOauthApp(
                (String) row[0],
                (String) row[1],
                (String) row[4],
                (String) row[5],
                ((Number) row[6]).intValue() == 1,
                !plain.isBlank(),
                masked,
                ((Number) row[7]).longValue());
    }

    private static String normalizeProvider(String provider) {
        String p = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        if (!p.equals("GITEE") && !p.equals("GITHUB") && !p.equals("GITLAB")) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "provider 只能是 GITEE / GITHUB / GITLAB");
        }
        return p;
    }

    private static String defaultScopes(String provider) {
        return switch (provider) {
            case "GITEE" -> "user_info projects issues";
            case "GITHUB" -> "repo read:user";
            default -> "read_api read_repository read_user";
        };
    }
}
