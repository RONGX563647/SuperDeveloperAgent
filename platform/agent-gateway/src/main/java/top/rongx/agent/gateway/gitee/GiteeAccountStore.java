package top.rongx.agent.gateway.gitee;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.IntakeException;
import top.rongx.agent.infra.persistence.PlatformDb;
import top.rongx.agent.infra.security.SecretBox;

/**
 * Gitee 账号绑定存储：token 以 AES-256-GCM 加密落统一库（按平台主密钥加密）。
 *
 * <p>安全设计：
 * <ul>
 *   <li>token <b>永不落明文</b>：库中只有密文；加解密统一复用 {@link SecretBox}，
 *       主密钥来源与其它凭据一致（环境变量优先，否则工作区外本地密钥文件）；</li>
 *   <li>token 只在两个时刻解密：用户查看绑定状态（脱敏显示）、后端代调 Gitee API；</li>
 *   <li>每个用户一条绑定记录（一对一），更新即覆盖；解绑即删除。</li>
 * </ul>
 */
@Service
public class GiteeAccountStore {

    private final PlatformDb db;
    private final SecretBox secretBox;

    @Autowired
    public GiteeAccountStore(PlatformDb db, SecretBox secretBox) {
        this.db = db;
        this.secretBox = secretBox;
    }

    // ---------------------------------------------------------------- 绑定操作

    /** 绑定/更新（一对一：覆盖旧绑定）。 */
    public void bind(String userId, String giteeLogin, String token, String scopes) {
        SecretBox.Sealed enc = secretBox.encrypt(token);
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

    /** 绑定信息（不含明文 token；tokenMasked 由平台统一脱敏规则生成）。 */
    public Optional<Binding> find(String userId) {
        try {
            return db.read(em -> PlatformDb.row(em,
                            "SELECT user_id, gitee_login, token_enc, token_iv, scopes, bound_at "
                                    + "FROM gitee_accounts WHERE user_id = ?", userId)
                    .map(row -> new Binding((String) row[0], (String) row[1],
                            secretBox.mask(secretBox.decrypt((String) row[2], (String) row[3])),
                            (String) row[4], ((Number) row[5]).longValue())));
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
                    .map(row -> secretBox.decrypt((String) row[0], (String) row[1])));
        } catch (RuntimeException e) {
            throw new IntakeException(ErrorCode.INTAKE_INVALID_STATE, "token 读取失败：" + e.getMessage(), e);
        }
    }

    /** 绑定视图（脱敏）。 */
    public record Binding(String userId, String giteeLogin, String tokenMasked,
                          String scopes, long boundAt) {
    }
}
