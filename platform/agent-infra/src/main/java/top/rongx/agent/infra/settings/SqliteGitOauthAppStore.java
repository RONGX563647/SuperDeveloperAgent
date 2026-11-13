package top.rongx.agent.infra.settings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.settings.GitOauthApp;
import top.rongx.agent.domain.settings.GitOauthAppRepository;
import top.rongx.agent.infra.security.SecretBox;

/**
 * Git 平台 OAuth 应用配置（SQLite）。
 *
 * <p>一张表按 provider 存平台级应用凭据：client-secret AES-GCM 加密，
 * 接口只回掩码；保存时 secret 留空表示沿用旧值。
 */
@Repository
public class SqliteGitOauthAppStore implements GitOauthAppRepository {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS git_oauth_apps (
                provider     TEXT PRIMARY KEY,
                client_id    TEXT NOT NULL,
                secret_enc   TEXT NOT NULL,
                secret_iv    TEXT NOT NULL,
                redirect_uri TEXT NOT NULL,
                scopes       TEXT NOT NULL,
                enabled      INTEGER NOT NULL,
                updated_at   INTEGER NOT NULL
            )
            """;

    private static final String COLUMNS =
            "provider, client_id, secret_enc, secret_iv, redirect_uri, scopes, enabled, updated_at";

    private final Path dbFile;
    private final SecretBox secretBox;

    public SqliteGitOauthAppStore(
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
            throw new IllegalStateException("git_oauth_apps 表初始化失败：" + e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    @Override
    public List<GitOauthApp> findAll() {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM git_oauth_apps ORDER BY provider")) {
            try (ResultSet rs = st.executeQuery()) {
                List<GitOauthApp> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth 应用配置查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<GitOauthApp> find(String provider) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT " + COLUMNS + " FROM git_oauth_apps WHERE provider = ?")) {
            st.setString(1, normalizeProvider(provider));
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth 应用配置查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public GitOauthApp save(GitOauthAppView view, String clientSecret) {
        String provider = normalizeProvider(view.provider());
        String clientId = view.clientId() == null ? "" : view.clientId().trim();
        if (clientId.isBlank()) {
            throw new IllegalArgumentException("Client ID 不能为空");
        }
        String redirectUri = view.redirectUri() == null ? "" : view.redirectUri().trim();
        if (redirectUri.isBlank()) {
            throw new IllegalArgumentException("回调地址不能为空");
        }
        String scopes = view.scopes() == null || view.scopes().isBlank()
                ? defaultScopes(provider) : view.scopes().trim();
        long now = System.currentTimeMillis();
        try (Connection conn = open()) {
            SecretBox.Sealed existing = existingSecret(conn, provider);
            SecretBox.Sealed sealed = clientSecret != null && !clientSecret.isBlank()
                    ? secretBox.encrypt(clientSecret.trim()) : existing;
            if (sealed == null) {
                throw new IllegalArgumentException("首次配置必须填写 Client Secret");
            }
            if (existing == null) {
                try (PreparedStatement st = conn.prepareStatement("""
                        INSERT INTO git_oauth_apps
                            (provider, client_id, secret_enc, secret_iv, redirect_uri, scopes, enabled, updated_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        """)) {
                    st.setString(1, provider);
                    st.setString(2, clientId);
                    st.setString(3, sealed.cipherB64());
                    st.setString(4, sealed.ivB64());
                    st.setString(5, redirectUri);
                    st.setString(6, scopes);
                    st.setBoolean(7, view.enabled());
                    st.setLong(8, now);
                    st.executeUpdate();
                }
            } else {
                try (PreparedStatement st = conn.prepareStatement("""
                        UPDATE git_oauth_apps
                        SET client_id=?, secret_enc=?, secret_iv=?, redirect_uri=?, scopes=?, enabled=?, updated_at=?
                        WHERE provider=?
                        """)) {
                    st.setString(1, clientId);
                    st.setString(2, sealed.cipherB64());
                    st.setString(3, sealed.ivB64());
                    st.setString(4, redirectUri);
                    st.setString(5, scopes);
                    st.setBoolean(6, view.enabled());
                    st.setLong(7, now);
                    st.setString(8, provider);
                    st.executeUpdate();
                }
            }
            return find(provider).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth 应用配置保存失败：" + e.getMessage(), e);
        }
    }

    private SecretBox.Sealed existingSecret(Connection conn, String provider) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT secret_enc, secret_iv FROM git_oauth_apps WHERE provider = ?")) {
            st.setString(1, provider);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new SecretBox.Sealed(rs.getString("secret_enc"), rs.getString("secret_iv"));
            }
        }
    }

    @Override
    public Optional<String> secretOf(String provider) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT secret_enc, secret_iv FROM git_oauth_apps WHERE provider = ?")) {
            st.setString(1, normalizeProvider(provider));
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(secretBox.decrypt(
                        rs.getString("secret_enc"), rs.getString("secret_iv")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Client Secret 读取失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String provider) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM git_oauth_apps WHERE provider = ?")) {
            st.setString(1, normalizeProvider(provider));
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("OAuth 应用配置删除失败：" + e.getMessage(), e);
        }
    }

    private GitOauthApp map(ResultSet rs) throws SQLException {
        String plain = secretBox.decrypt(rs.getString("secret_enc"), rs.getString("secret_iv"));
        String masked = plain.length() <= 4 ? "***" : plain.substring(0, 4) + "***";
        return new GitOauthApp(
                rs.getString("provider"),
                rs.getString("client_id"),
                rs.getString("redirect_uri"),
                rs.getString("scopes"),
                rs.getBoolean("enabled"),
                !plain.isBlank(),
                masked,
                rs.getLong("updated_at"));
    }

    private static String normalizeProvider(String provider) {
        String p = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        if (!p.equals("GITEE") && !p.equals("GITHUB") && !p.equals("GITLAB")) {
            throw new IllegalArgumentException("provider 只能是 GITEE / GITHUB / GITLAB");
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
