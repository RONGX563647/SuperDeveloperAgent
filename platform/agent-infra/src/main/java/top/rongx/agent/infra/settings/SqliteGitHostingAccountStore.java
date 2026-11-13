package top.rongx.agent.infra.settings;

import java.net.URI;
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
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.settings.GitHostingAccount;
import top.rongx.agent.domain.settings.GitHostingAccountRepository;
import top.rongx.agent.infra.security.SecretBox;

/**
 * 代码托管账号（SQLite）：token AES-GCM 加密，接口只回掩码。
 *
 * <p>表按「用户 + provider + host」唯一：同一台 GitHub / GitLab / 自建服务只保留一份凭据，
 * 重复保存即覆盖。clone/fetch 时按仓库 URL 的 host 自动匹配。
 */
@Repository
public class SqliteGitHostingAccountStore implements GitHostingAccountRepository {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS git_hosting_accounts (
                id          TEXT PRIMARY KEY,
                user_id     TEXT NOT NULL,
                provider    TEXT NOT NULL,
                host        TEXT NOT NULL,
                username    TEXT,
                token_enc   TEXT NOT NULL,
                token_iv    TEXT NOT NULL,
                updated_at  INTEGER NOT NULL,
                UNIQUE (user_id, provider, host)
            )
            """;

    private static final String COLUMNS = "id, provider, host, username, token_enc, token_iv, updated_at";

    private final Path dbFile;
    private final SecretBox secretBox;

    public SqliteGitHostingAccountStore(
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
            throw new IllegalStateException("git_hosting_accounts 表初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    @Override
    public List<GitHostingAccount> findAll(String userId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM git_hosting_accounts WHERE user_id = ? "
                             + "ORDER BY provider, updated_at DESC")) {
            st.setString(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                List<GitHostingAccount> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("代码托管账号查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<GitHostingAccount> find(String id, String userId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM git_hosting_accounts WHERE id = ? AND user_id = ?")) {
            st.setString(1, id);
            st.setString(2, userId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("代码托管账号查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public GitHostingAccount save(GitHostingAccountView view, String token, String userId) {
        String provider = normalizeProvider(view.provider());
        String host = normalizeHost(view.host());
        long now = System.currentTimeMillis();
        try (Connection conn = open()) {
            String existingId = existingId(conn, userId, provider, host, view.id());
            String id = existingId == null ? newId() : existingId;
            String username = view.username() == null || view.username().isBlank()
                    ? defaultUsername(provider) : view.username().trim();
            if (token != null && !token.isBlank()) {
                SecretBox.Sealed sealed = secretBox.encrypt(token.trim());
                if (existingId == null) {
                    try (PreparedStatement st = conn.prepareStatement("""
                            INSERT INTO git_hosting_accounts
                                (id, user_id, provider, host, username, token_enc, token_iv, updated_at)
                            VALUES (?,?,?,?,?,?,?,?)
                            """)) {
                        st.setString(1, id);
                        st.setString(2, userId);
                        st.setString(3, provider);
                        st.setString(4, host);
                        st.setString(5, username);
                        st.setString(6, sealed.cipherB64());
                        st.setString(7, sealed.ivB64());
                        st.setLong(8, now);
                        st.executeUpdate();
                    }
                } else {
                    update(conn, id, username, sealed, now);
                }
            } else if (existingId == null) {
                throw new IllegalArgumentException("首次接入必须填写 Token");
            } else {
                // token 留空 = 只改用户名等非敏感字段
                try (PreparedStatement st = conn.prepareStatement(
                        "UPDATE git_hosting_accounts SET username = ?, updated_at = ? WHERE id = ?")) {
                    st.setString(1, username);
                    st.setLong(2, now);
                    st.setString(3, id);
                    st.executeUpdate();
                }
            }
            return find(id, userId).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("代码托管账号保存失败：" + e.getMessage(), e);
        }
    }

    private void update(Connection conn, String id, String username, SecretBox.Sealed sealed,
                        long now) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("""
                UPDATE git_hosting_accounts SET username=?, token_enc=?, token_iv=?, updated_at=?
                WHERE id=?
                """)) {
            st.setString(1, username);
            st.setString(2, sealed.cipherB64());
            st.setString(3, sealed.ivB64());
            st.setLong(4, now);
            st.setString(5, id);
            st.executeUpdate();
        }
    }

    /** 同用户同 provider 同 host 已存在时复用其 id（保存即覆盖）。 */
    private String existingId(Connection conn, String userId, String provider, String host,
                              String requestedId) throws SQLException {
        if (requestedId != null && !requestedId.isBlank()) {
            return requestedId;
        }
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT id FROM git_hosting_accounts WHERE user_id=? AND provider=? AND host=?")) {
            st.setString(1, userId);
            st.setString(2, provider);
            st.setString(3, host);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString("id") : null;
            }
        }
    }

    @Override
    public void delete(String id, String userId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM git_hosting_accounts WHERE id = ? AND user_id = ?")) {
            st.setString(1, id);
            st.setString(2, userId);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("代码托管账号删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> tokenOf(String id, String userId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT token_enc, token_iv FROM git_hosting_accounts WHERE id=? AND user_id=?")) {
            st.setString(1, id);
            st.setString(2, userId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(secretBox.decrypt(rs.getString("token_enc"), rs.getString("token_iv")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Token 读取失败：" + e.getMessage(), e);
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
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeProvider(String provider) {
        String p = provider == null || provider.isBlank() ? "CUSTOM" : provider.trim().toUpperCase(Locale.ROOT);
        if (!p.equals("GITHUB") && !p.equals("GITLAB") && !p.equals("CUSTOM")) {
            throw new IllegalArgumentException("provider 只能是 GITHUB / GITLAB / CUSTOM");
        }
        return p;
    }

    private static String normalizeHost(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (h.isBlank()) {
            throw new IllegalArgumentException("host 必填（如 github.com / gitlab.com / git.example.com）");
        }
        if (h.startsWith("http://") || h.startsWith("https://")) {
            String parsed = hostOf(h);
            if (parsed == null) {
                throw new IllegalArgumentException("host 格式不正确：" + host);
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

    private GitHostingAccount map(ResultSet rs) throws SQLException {
        String plain = secretBox.decrypt(rs.getString("token_enc"), rs.getString("token_iv"));
        String masked = plain.length() <= 6 ? "***" : plain.substring(0, 6) + "***";
        return new GitHostingAccount(
                rs.getString("id"),
                rs.getString("provider"),
                rs.getString("host"),
                rs.getString("username"),
                true,
                masked,
                rs.getLong("updated_at"));
    }

    private String newId() {
        byte[] raw = new byte[4];
        new SecureRandom().nextBytes(raw);
        return "git-" + HexFormat.of().formatHex(raw);
    }
}
