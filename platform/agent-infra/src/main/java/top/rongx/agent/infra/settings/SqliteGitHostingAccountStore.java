package top.rongx.agent.infra.settings;

import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.settings.GitHostingAccount;
import top.rongx.agent.domain.settings.GitHostingAccountRepository;
import top.rongx.agent.infra.security.SecretBox;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.IntakeException;
import top.rongx.agent.domain.error.PlatformException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 代码托管账号（JPA）：token AES-GCM 加密，接口只回掩码。
 *
 * <p>表按「用户 + provider + host」唯一：同一台 GitHub / GitLab / 自建服务只保留一份凭据，
 * 重复保存即覆盖。clone/fetch 时按仓库 URL 的 host 自动匹配。
 */
@Repository
public class SqliteGitHostingAccountStore implements GitHostingAccountRepository {

    private static final String COLUMNS = "id, provider, host, username, token_enc, token_iv, updated_at";

    private static final String INSERT_SQL = """
            INSERT INTO git_hosting_accounts
                (id, user_id, provider, host, username, token_enc, token_iv, updated_at)
            VALUES (?,?,?,?,?,?,?,?)
            """;

    private static final String UPDATE_WITH_TOKEN_SQL = """
            UPDATE git_hosting_accounts SET username=?, token_enc=?, token_iv=?, updated_at=?
            WHERE id=?
            """;

    private static final String UPDATE_KEEP_TOKEN_SQL =
            "UPDATE git_hosting_accounts SET username = ?, updated_at = ? WHERE id = ?";

    private final PlatformDb db;
    private final SecretBox secretBox;

    @Autowired
    public SqliteGitHostingAccountStore(PlatformDb db, SecretBox secretBox) {
        this.db = db;
        this.secretBox = secretBox;
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public SqliteGitHostingAccountStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
            SecretBox secretBox) {
        this(PlatformDb.sqlite(Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.db")), secretBox);
    }

    @Override
    public List<GitHostingAccount> findAll(String userId) {
        try {
            return db.read(em -> PlatformDb.rows(em,
                    "SELECT " + COLUMNS + " FROM git_hosting_accounts WHERE user_id = ? "
                            + "ORDER BY provider, updated_at DESC", userId).stream()
                    .map(this::map).toList());
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "代码托管账号查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<GitHostingAccount> find(String id, String userId) {
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + COLUMNS + " FROM git_hosting_accounts WHERE id = ? AND user_id = ?",
                    id, userId).map(this::map));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "代码托管账号查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public GitHostingAccount save(GitHostingAccountView view, String token, String userId) {
        String provider = normalizeProvider(view.provider());
        String host = normalizeHost(view.host());
        long now = System.currentTimeMillis();
        try {
            String existingId = existingId(userId, provider, host, view.id());
            String id = existingId == null ? newId() : existingId;
            String username = view.username() == null || view.username().isBlank()
                    ? defaultUsername(provider) : view.username().trim();
            if (token != null && !token.isBlank()) {
                SecretBox.Sealed sealed = secretBox.encrypt(token.trim());
                if (existingId == null) {
                    db.run(em -> PlatformDb.update(em, INSERT_SQL,
                            id, userId, provider, host, username,
                            sealed.cipherB64(), sealed.ivB64(), now));
                } else {
                    db.run(em -> PlatformDb.update(em, UPDATE_WITH_TOKEN_SQL,
                            username, sealed.cipherB64(), sealed.ivB64(), now, id));
                }
            } else if (existingId == null) {
                throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "首次接入必须填写 Token");
            } else {
                // token 留空 = 只改用户名等非敏感字段
                db.run(em -> PlatformDb.update(em, UPDATE_KEEP_TOKEN_SQL, username, now, id));
            }
            return find(id, userId).orElseThrow();
        } catch (PlatformException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "代码托管账号保存失败：" + e.getMessage(), e);
        }
    }

    /** 同用户同 provider 同 host 已存在时复用其 id（保存即覆盖）。 */
    private String existingId(String userId, String provider, String host, String requestedId) {
        if (requestedId != null && !requestedId.isBlank()) {
            return requestedId;
        }
        try {
            return db.read(em -> PlatformDb.<String>first(em,
                    "SELECT id FROM git_hosting_accounts WHERE user_id=? AND provider=? AND host=?",
                    userId, provider, host).orElse(null));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "代码托管账号查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String id, String userId) {
        try {
            db.run(em -> PlatformDb.update(em,
                    "DELETE FROM git_hosting_accounts WHERE id = ? AND user_id = ?", id, userId));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "代码托管账号删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> tokenOf(String id, String userId) {
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT token_enc, token_iv FROM git_hosting_accounts WHERE id=? AND user_id=?",
                    id, userId).map(row -> secretBox.decrypt((String) row[0], (String) row[1])));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "Token 读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Credential> credentialFor(String userId, String repoUrl) {
        String host = hostOf(repoUrl);
        if (host == null) {
            return Optional.empty();
        }
        for (GitHostingAccount account : findAll(userId)) {
            String accountHost = account.host().toLowerCase(Locale.ROOT);
            if (!matchHost(host, accountHost)) {
                continue;
            }
            return tokenOf(account.id(), userId)
                    .map(token -> new Credential(account.username(), token));
        }
        return Optional.empty();
    }

    private static boolean matchHost(String urlHost, String accountHost) {
        return urlHost.equals(accountHost)
                || urlHost.startsWith(accountHost + ":")
                || accountHost.startsWith(urlHost + ":");
    }

    /** 从仓库 URL 取 host（小写、不含端口）；不是 http(s) 时返回 null。 */
    static String hostOf(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(repoUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (PlatformException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeProvider(String provider) {
        String p = provider == null || provider.isBlank() ? "CUSTOM" : provider.trim().toUpperCase(Locale.ROOT);
        if (!p.equals("GITHUB") && !p.equals("GITLAB") && !p.equals("CUSTOM")) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "provider 只能是 GITHUB / GITLAB / CUSTOM");
        }
        return p;
    }

    private static String normalizeHost(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (h.isBlank()) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "host 必填（如 github.com / gitlab.com / git.example.com）");
        }
        if (h.startsWith("http://") || h.startsWith("https://")) {
            String parsed = hostOf(h);
            if (parsed == null) {
                throw new IntakeException(ErrorCode.INTAKE_INVALID_INPUT, "host 格式不正确：" + host);
            }
            return parsed;
        }
        // 去掉路径与末尾斜杠，允许用户直接粘贴域名或带端口的地址
        int slash = h.indexOf('/');
        if (slash >= 0) {
            h = h.substring(0, slash);
        }
        return h.endsWith("/") ? h.substring(0, h.length() - 1) : h;
    }

    private static String defaultUsername(String provider) {
        return switch (provider) {
            case "GITHUB" -> "x-access-token";
            case "GITLAB" -> "oauth2";
            default -> "git";
        };
    }

    private GitHostingAccount map(Object[] row) {
        String plain = secretBox.decrypt((String) row[4], (String) row[5]);
        String masked = plain.length() <= 6 ? "***" : plain.substring(0, 6) + "***";
        return new GitHostingAccount(
                (String) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                true,
                masked,
                ((Number) row[6]).longValue());
    }

    private String newId() {
        byte[] raw = new byte[4];
        new SecureRandom().nextBytes(raw);
        return "git-" + HexFormat.of().formatHex(raw);
    }
}
