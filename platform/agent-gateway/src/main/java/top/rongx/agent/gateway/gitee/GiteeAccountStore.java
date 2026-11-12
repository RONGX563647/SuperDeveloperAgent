package top.rongx.agent.gateway.gitee;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.rongx.agent.infra.persistence.SqliteStore;

/**
 * Gitee 账号绑定存储：token 以 AES-256-GCM 加密落 SQLite（按平台主密钥加密）。
 *
 * <p>安全设计：
 * <ul>
 *   <li>token <b>永不落明文</b>：库中只有密文；主密钥来自环境变量（缺省时首次启动自动生成
 *       并写到工作区外的本地密钥文件，权限提醒用户保管）；</li>
 *   <li>token 只在两个时刻解密：用户查看绑定状态（脱敏显示）、后端代调 Gitee API；</li>
 *   <li>每个用户一条绑定记录（一对一），更新即覆盖；解绑即删除。</li>
 * </ul>
 */
@Service
public class GiteeAccountStore extends SqliteStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GiteeAccountStore.class);
    private static final String TABLE = """
            CREATE TABLE IF NOT EXISTS gitee_accounts (
                user_id     TEXT PRIMARY KEY,
                gitee_login TEXT NOT NULL,
                token_enc   TEXT NOT NULL,
                token_iv    TEXT NOT NULL,
                scopes      TEXT,
                bound_at    INTEGER NOT NULL
            )
            """;

    private final SecretKey masterKey;

    public GiteeAccountStore(@Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
                             @Value("${agent.security.master-key:}") String masterKeyB64) {
        super(workspaceRoot, TABLE, "gitee_accounts");
        this.masterKey = resolveMasterKey(masterKeyB64);
    }

    /** 主密钥：环境变量优先（Base64 AES key）；否则用本地密钥文件（首次生成）。 */
    private SecretKey resolveMasterKey(String masterKeyB64) {
        try {
            if (masterKeyB64 != null && !masterKeyB64.isBlank()) {
                byte[] raw = Base64.getDecoder().decode(masterKeyB64.trim());
                return new SecretKeySpec(raw, "AES");
            }
            // 本地密钥文件：与 db 同目录（data/ 已 gitignore）
            Path keyFile = dbFile().getParent().resolve("platform.key");
            if (java.nio.file.Files.isRegularFile(keyFile)) {
                byte[] raw = Base64.getDecoder().decode(
                        java.nio.file.Files.readString(keyFile).trim());
                return new SecretKeySpec(raw, "AES");
            }
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256);
            byte[] raw = gen.generateKey().getEncoded();
            java.nio.file.Files.createDirectories(keyFile.getParent());
            java.nio.file.Files.writeString(keyFile, Base64.getEncoder().encodeToString(raw));
            log.info("已生成平台主密钥 {}（请妥善备份；丢失后已加密的凭据将无法解密）", keyFile);
            return new SecretKeySpec(raw, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("主密钥初始化失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 加解密

    private record Encrypted(String cipherB64, String ivB64) {
    }

    private Encrypted encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new Encrypted(Base64.getEncoder().encodeToString(ct),
                    Base64.getEncoder().encodeToString(iv));
        } catch (Exception e) {
            throw new IllegalStateException("token 加密失败：" + e.getMessage(), e);
        }
    }

    private String decrypt(String cipherB64, String ivB64) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey,
                    new GCMParameterSpec(128, Base64.getDecoder().decode(ivB64)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(cipherB64)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("token 解密失败（主密钥不匹配？）：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 绑定操作

    /** 绑定/更新（一对一：覆盖旧绑定）。 */
    public void bind(String userId, String giteeLogin, String token, String scopes) {
        Encrypted enc = encrypt(token);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO gitee_accounts (user_id, gitee_login, token_enc, token_iv, scopes, bound_at)
                     VALUES (?,?,?,?,?,?)
                     ON CONFLICT(user_id) DO UPDATE SET
                         gitee_login=excluded.gitee_login, token_enc=excluded.token_enc,
                         token_iv=excluded.token_iv, scopes=excluded.scopes, bound_at=excluded.bound_at
                     """)) {
            st.setString(1, userId);
            st.setString(2, giteeLogin);
            st.setString(3, enc.cipherB64());
            st.setString(4, enc.ivB64());
            st.setString(5, scopes == null ? "" : scopes);
            st.setLong(6, System.currentTimeMillis());
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Gitee 绑定失败：" + e.getMessage(), e);
        }
    }

    /** 解绑。 */
    public boolean unbind(String userId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("DELETE FROM gitee_accounts WHERE user_id = ?")) {
            st.setString(1, userId);
            return st.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Gitee 解绑失败：" + e.getMessage(), e);
        }
    }

    /** 绑定信息（不含明文 token；tokenMasked 是前 6 位 + ***）。 */
    public Optional<Binding> find(String userId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT user_id, gitee_login, token_enc, token_iv, scopes, bound_at "
                             + "FROM gitee_accounts WHERE user_id = ?")) {
            st.setString(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String plain = decrypt(rs.getString("token_enc"), rs.getString("token_iv"));
                String masked = plain.length() <= 6
                        ? "***" : plain.substring(0, 6) + "***";
                return Optional.of(new Binding(userId, rs.getString("gitee_login"), masked,
                        rs.getString("scopes"), rs.getLong("bound_at")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Gitee 绑定查询失败：" + e.getMessage(), e);
        }
    }

    /** 是否已绑定。 */
    public boolean isBound(String userId) {
        return find(userId).isPresent();
    }

    /** 解密取 token（仅供后端代调 Gitee API；不返回给前端）。 */
    public Optional<String> tokenOf(String userId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT token_enc, token_iv FROM gitee_accounts WHERE user_id = ?")) {
            st.setString(1, userId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(decrypt(rs.getString("token_enc"), rs.getString("token_iv")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("token 读取失败：" + e.getMessage(), e);
        }
    }

    /** 绑定视图（脱敏）。 */
    public record Binding(String userId, String giteeLogin, String tokenMasked,
                          String scopes, long boundAt) {
    }

    @Override
    public void close() {
        // 短连接模式：无长持有资源
    }
}
