package top.rongx.agent.gateway.gitee;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.IntakeException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * Gitee 账号绑定存储：token 以 AES-256-GCM 加密落统一库（按平台主密钥加密）。
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
public class GiteeAccountStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GiteeAccountStore.class);

    private final PlatformDb db;
    private final SecretKey masterKey;

    @Autowired
    public GiteeAccountStore(PlatformDb db,
                             @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
                             @Value("${agent.security.master-key:}") String masterKeyB64) {
        this.db = db;
        Path keyFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.key");
        this.masterKey = resolveMasterKey(masterKeyB64, keyFile);
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public GiteeAccountStore(String workspaceRoot, String masterKeyB64) {
        this(PlatformDb.sqlite(platformDbFile(workspaceRoot)), workspaceRoot, masterKeyB64);
    }

    /** 独立运行时要保证库文件所在目录存在；schema 仍由 Hibernate 生成。 */
    private static Path platformDbFile(String workspaceRoot) {
        Path dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.db");
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "Gitee 存储目录创建失败：" + e.getMessage(), e);
        }
        return dbFile;
    }

    /** 主密钥：环境变量优先（Base64 AES key）；否则用本地密钥文件（首次生成）。 */
    private SecretKey resolveMasterKey(String masterKeyB64, Path keyFile) {
        try {
            if (masterKeyB64 != null && !masterKeyB64.isBlank()) {
                byte[] raw = Base64.getDecoder().decode(masterKeyB64.trim());
                return new SecretKeySpec(raw, "AES");
            }
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
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "主密钥初始化失败：" + e.getMessage(), e);
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
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "token 加密失败：" + e.getMessage(), e);
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
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "token 解密失败（主密钥不匹配？）：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 绑定操作

    /** 绑定/更新（一对一：覆盖旧绑定）。 */
    public void bind(String userId, String giteeLogin, String token, String scopes) {
        Encrypted enc = encrypt(token);
        try {
            db.run(em -> PlatformDb.update(em, """
                    INSERT INTO gitee_accounts (user_id, gitee_login, token_enc, token_iv, scopes, bound_at)
                    VALUES (?,?,?,?,?,?)
                    ON CONFLICT(user_id) DO UPDATE SET
                        gitee_login=excluded.gitee_login, token_enc=excluded.token_enc,
                        token_iv=excluded.token_iv, scopes=excluded.scopes, bound_at=excluded.bound_at
                    """,
                    userId,
                    giteeLogin,
                    enc.cipherB64(),
                    enc.ivB64(),
                    scopes == null ? "" : scopes,
                    System.currentTimeMillis()));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "Gitee 绑定失败：" + e.getMessage(), e);
        }
    }

    /** 解绑。 */
    public boolean unbind(String userId) {
        try {
            return db.inTx(em -> PlatformDb.update(em,
                    "DELETE FROM gitee_accounts WHERE user_id = ?", userId) > 0);
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "Gitee 解绑失败：" + e.getMessage(), e);
        }
    }

    /** 绑定信息（不含明文 token；tokenMasked 是前 6 位 + ***）。 */
    public Optional<Binding> find(String userId) {
        try {
            return db.read(em -> PlatformDb.row(em,
                            "SELECT user_id, gitee_login, token_enc, token_iv, scopes, bound_at "
                                    + "FROM gitee_accounts WHERE user_id = ?", userId)
                    .map(row -> {
                        String plain = decrypt((String) row[2], (String) row[3]);
                        String masked = plain.length() <= 6
                                ? "***" : plain.substring(0, 6) + "***";
                        return new Binding((String) row[0], (String) row[1], masked,
                                (String) row[4], ((Number) row[5]).longValue());
                    }));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "Gitee 绑定查询失败：" + e.getMessage(), e);
        }
    }

    /** 是否已绑定。 */
    public boolean isBound(String userId) {
        return find(userId).isPresent();
    }

    /** 解密取 token（仅供后端代调 Gitee API；不返回给前端）。 */
    public Optional<String> tokenOf(String userId) {
        try {
            return db.read(em -> PlatformDb.row(em,
                            "SELECT token_enc, token_iv FROM gitee_accounts WHERE user_id = ?", userId)
                    .map(row -> decrypt((String) row[0], (String) row[1])));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "token 读取失败：" + e.getMessage(), e);
        }
    }

    /** 绑定视图（脱敏）。 */
    public record Binding(String userId, String giteeLogin, String tokenMasked,
                          String scopes, long boundAt) {
    }

    @Override
    public void close() {
        // PlatformDb 由容器/缓存统一持有，这里不关闭共享资源
    }
}
