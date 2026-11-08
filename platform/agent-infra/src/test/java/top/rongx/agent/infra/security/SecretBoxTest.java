package top.rongx.agent.infra.security;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 凭据加解密：往返、掩码、篡改检测。 */
class SecretBoxTest {

    @TempDir
    Path workspace;

    private SecretBox box() {
        // 空 master-key → 走本地密钥文件（首次生成）
        return new SecretBox(workspace.toString(), "");
    }

    @Test
    @DisplayName("加密后可解密，且同一明文两次密文不同（随机 IV）")
    void roundTrip() {
        SecretBox box = box();
        SecretBox.Sealed first = box.encrypt("sk-secret-123456");
        SecretBox.Sealed second = box.encrypt("sk-secret-123456");
        assertEquals("sk-secret-123456", box.decrypt(first.cipherB64(), first.ivB64()));
        assertNotEquals(first.cipherB64(), second.cipherB64());
    }

    @Test
    @DisplayName("掩码只保留末 4 位")
    void mask() {
        SecretBox box = box();
        assertEquals("****3456", box.mask("sk-secret-123456"));
        assertEquals("****", box.mask("abc"));
        assertEquals("", box.mask(null));
    }

    @Test
    @DisplayName("密文被篡改 → 解密失败（GCM 认证）")
    void tamperedCipherFails() {
        SecretBox box = box();
        SecretBox.Sealed sealed = box.encrypt("top-secret");
        String tampered = sealed.cipherB64().substring(0, 4)
                + (sealed.cipherB64().charAt(4) == 'A' ? 'B' : 'A')
                + sealed.cipherB64().substring(5);
        assertThrows(IllegalStateException.class,
                () -> box.decrypt(tampered, sealed.ivB64()));
    }

    @Test
    @DisplayName("主密钥文件持久化：换实例仍能解密")
    void masterKeyPersists() {
        SecretBox first = box();
        SecretBox.Sealed sealed = first.encrypt("persist-me");
        SecretBox second = box();
        assertEquals("persist-me", second.decrypt(sealed.cipherB64(), sealed.ivB64()));
        assertTrue(java.nio.file.Files.isRegularFile(
                workspace.resolve("_platform/platform.key")));
    }
}
