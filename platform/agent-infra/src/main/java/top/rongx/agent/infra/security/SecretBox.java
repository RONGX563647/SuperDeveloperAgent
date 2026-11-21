package top.rongx.agent.infra.security;

import top.rongx.agent.domain.workspace.WorkspacePaths;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;

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
        Path keyFile = WorkspacePaths.key(Path.of(workspaceRoot));
        this.masterKey = resolveMasterKey(masterKeyB64, keyFile);
    }

    private SecretKey resolveMasterKey(String masterKeyB64, Path keyFile) {
        try {
            if (masterKeyB64 != null && !masterKeyB64.isBlank()) {
                return new SecretKeySpec(Base64.getDecoder().decode(masterKeyB64.trim()), "AES");
            }
            if (Files.isRegularFile(keyFile)) {
                // 已存在的密钥文件也收紧权限：历史版本可能是 644（同机其它用户可读）
                restrictToOwner(keyFile);
                return new SecretKeySpec(
                        Base64.getDecoder().decode(Files.readString(keyFile).trim()), "AES");
            }
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256);
            byte[] raw = gen.generateKey().getEncoded();
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(raw));
            restrictToOwner(keyFile);
            log.info("已生成平台主密钥 {}（请妥善备份；丢失后已加密的凭据将无法解密）", keyFile);
            return new SecretKeySpec(raw, "AES");
        } catch (Exception e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "主密钥初始化失败：" + e.getMessage(), e);
        }
    }

    /**
     * 主密钥只允许属主读写：POSIX 用 600，Windows 用 ACL，其它文件系统尽力而为。
     *
     * <p>之前直接调用 {@code setPosixFilePermissions}，在 Windows 上必然抛
     * {@link UnsupportedOperationException}（message 为 null），于是每次启动都打一条
     * “无法收紧…：null”的误导性 WARN。这里先判断文件系统能力，再按平台选择实现。
     */
    private static void restrictToOwner(Path keyFile) {
        try {
            if (keyFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(keyFile,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                return;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(
                    keyFile, AclFileAttributeView.class);
            if (acl == null) {
                log.debug("当前文件系统不支持权限收紧，跳过主密钥文件权限设置：{}", keyFile);
                return;
            }
            UserPrincipal owner = acl.getOwner();
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            // 只保留属主 ACE，移除继承来的其它用户权限
            acl.setAcl(List.of(ownerOnly));
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            log.warn("无法收紧主密钥文件权限（{}）：{}", keyFile, describe(e));
        }
    }

    /** 异常 message 可能为 null（如 UnsupportedOperationException），退化为类名。 */
    private static String describe(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
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
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "加密失败：" + e.getMessage(), e);
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
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "解密失败（主密钥不匹配？）：" + e.getMessage(), e);
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
