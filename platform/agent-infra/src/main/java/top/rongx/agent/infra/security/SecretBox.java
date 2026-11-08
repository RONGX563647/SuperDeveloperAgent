package top.rongx.agent.infra.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 平台凭据加解密（AES-256-GCM）—— Gitee token / SSH 密码 / 模型 API Key 共用。
 *
 * <p>主密钥来源：
 * <ol>
 *   <li>环境变量 {@code AGENT_SECURITY_MASTER_KEY}（Base64，32 字节）；</li>
 *   <li>否则用本地密钥文件 {@code <workspace>/_platform/platform.key}（首次自动生成）。</li>
 * </ol>
 *
 * <p>落库只存密文 + IV；接口只返回 {@link #mask(String)} 结果，明文不出后端。
 */
@Component
public class SecretBox {

    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);

    private final SecretKey masterKey;

    public SecretBox(@Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
                     @Value("${agent.security.master-key:}") String masterKeyB64) {
        Path keyFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.key");
        this.masterKey = resolveMasterKey(masterKeyB64, keyFile);
    }

    private SecretKey resolveMasterKey(String masterKeyB64, Path keyFile) {
        try {
            if (masterKeyB64 != null && !masterKeyB64.isBlank()) {
                return new SecretKeySpec(Base64.getDecoder().decode(masterKeyB64.trim()), "AES");
            }
            if (Files.isRegularFile(keyFile)) {
                return new SecretKeySpec(
                        Base64.getDecoder().decode(Files.readString(keyFile).trim()), "AES");
            }
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256);
            byte[] raw = gen.generateKey().getEncoded();
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(raw));
            log.info("已生成平台主密钥 {}（请妥善备份；丢失后已加密的凭据将无法解密）", keyFile);
            return new SecretKeySpec(raw, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("主密钥初始化失败：" + e.getMessage(), e);
        }
    }

    /** 密文 + IV（均为 Base64）。 */
    public record Sealed(String cipherB64, String ivB64) {
    }

    public Sealed encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Sealed(Base64.getEncoder().encodeToString(ct),
                    Base64.getEncoder().encodeToString(iv));
        } catch (Exception e) {
            throw new IllegalStateException("加密失败：" + e.getMessage(), e);
        }
    }

    public String decrypt(String cipherB64, String ivB64) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey,
                    new GCMParameterSpec(128, Base64.getDecoder().decode(ivB64)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(cipherB64)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败（主密钥不匹配？）：" + e.getMessage(), e);
        }
    }

    /** 脱敏：保留末 4 位（长度足够时），其余用 * 代替。 */
    public String mask(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        if (plaintext.length() <= 4) {
            return "****";
        }
        int keep = Math.min(4, plaintext.length() - 1);
        String tail = plaintext.substring(plaintext.length() - keep);
        return "****" + tail;
    }
}
