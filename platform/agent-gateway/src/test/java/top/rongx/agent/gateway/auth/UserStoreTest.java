package top.rongx.agent.gateway.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.error.PlatformException;
import top.rongx.agent.infra.persistence.PlatformDb;

/** 种子密码固定化 + 设置页改密 + 旧版随机密码迁移。 */
class UserStoreTest {

    @TempDir
    Path tempDir;

    /** Windows 上连接池持有 SQLite 句柄会让 @TempDir 删除失败，收尾时显式释放。 */
    @AfterEach
    void releaseConnections() {
        PlatformDb.closeAll();
    }

    @Test
    @DisplayName("首次启动：三个账号共用固定初始密码，不再生成随机密码文件")
    void allSeedAccountsShareFixedInitialPassword() {
        UserStore store = new UserStore(tempDir.toString(), "");

        assertEquals(3, store.all().size());
        for (String userId : new String[]{"admin", "devops", "viewer"}) {
            assertTrue(store.checkPassword(userId, UserStore.DEFAULT_INITIAL_PASSWORD),
                    userId + " 应能用统一初始密码登录");
            assertTrue(store.isUsingInitialPassword(userId), userId + " 应被标记为仍未改密");
        }
        assertFalse(Files.exists(tempDir.resolve("_platform/seed-passwords.txt")),
                "新版本不该再落明文密码文件");
    }

    @Test
    @DisplayName("AGENT_SEED_PASSWORDS 仍可覆盖，未指定的账号保持统一默认值")
    void configuredSeedPasswordsStillOverride() {
        UserStore store = new UserStore(tempDir.toString(), "admin=custom-admin-pass");

        assertTrue(store.checkPassword("admin", "custom-admin-pass"));
        assertFalse(store.checkPassword("admin", UserStore.DEFAULT_INITIAL_PASSWORD));
        assertTrue(store.checkPassword("devops", UserStore.DEFAULT_INITIAL_PASSWORD));
        assertTrue(store.isUsingInitialPassword("admin"), "自定义初始密码同样算「未改密」");
    }

    @Test
    @DisplayName("改密后旧密码失效、初始密码标记清除，重启依然生效")
    void changePasswordPersistsAndClearsInitialFlag() {
        UserStore store = new UserStore(tempDir.toString(), "");
        store.changePassword("admin", UserStore.DEFAULT_INITIAL_PASSWORD, "MyNewPass-2026");

        assertFalse(store.checkPassword("admin", UserStore.DEFAULT_INITIAL_PASSWORD));
        assertTrue(store.checkPassword("admin", "MyNewPass-2026"));
        assertFalse(store.isUsingInitialPassword("admin"));
        assertTrue(store.isUsingInitialPassword("viewer"), "不影响其他账号");

        UserStore restarted = new UserStore(tempDir.toString(), "");
        assertTrue(restarted.checkPassword("admin", "MyNewPass-2026"), "重启不得把用户密码重置回去");
        assertFalse(restarted.isUsingInitialPassword("admin"));
    }

    @Test
    @DisplayName("改密拒绝错误原密码 / 弱新密码 / 设回初始密码或当前密码")
    void changePasswordRejectsBadInput() {
        UserStore store = new UserStore(tempDir.toString(), "");

        assertThrows(PlatformException.class,
                () -> store.changePassword("admin", "wrong-current", "GoodNewPass-1"));
        assertThrows(PlatformException.class,
                () -> store.changePassword("admin", UserStore.DEFAULT_INITIAL_PASSWORD, "short"));
        assertThrows(PlatformException.class,
                () -> store.changePassword("admin", UserStore.DEFAULT_INITIAL_PASSWORD,
                        UserStore.DEFAULT_INITIAL_PASSWORD));
        // 失败的改密不能影响原密码
        assertTrue(store.checkPassword("admin", UserStore.DEFAULT_INITIAL_PASSWORD));

        store.changePassword("admin", UserStore.DEFAULT_INITIAL_PASSWORD, "GoodNewPass-1");
        assertThrows(PlatformException.class,
                () -> store.changePassword("admin", "GoodNewPass-1", "GoodNewPass-1"));
    }

    @Test
    @DisplayName("旧版随机密码文件：未改密的账号自动重置为统一初始密码，文件被清理")
    void migratesLegacyRandomSeedFile() throws IOException {
        String legacySpec = "admin=legacy-admin-1,devops=legacy-devops-2,viewer=legacy-viewer-3";
        new UserStore(tempDir.toString(), legacySpec);
        Path seedFile = tempDir.resolve("_platform/seed-passwords.txt");
        Files.writeString(seedFile, "# 首次启动生成的账号密码\n"
                + "admin=legacy-admin-1\ndevops=legacy-devops-2\nviewer=legacy-viewer-3\n");

        UserStore migrated = new UserStore(tempDir.toString(), "");

        for (String userId : new String[]{"admin", "devops", "viewer"}) {
            assertTrue(migrated.checkPassword(userId, UserStore.DEFAULT_INITIAL_PASSWORD),
                    userId + " 迁移后应能用统一初始密码登录");
            assertTrue(migrated.isUsingInitialPassword(userId));
        }
        assertFalse(Files.exists(seedFile), "明文凭据文件应被删除");
    }

    @Test
    @DisplayName("迁移不覆盖用户已自行修改的密码")
    void migrationKeepsUserChangedPassword() throws IOException {
        UserStore old = new UserStore(tempDir.toString(), "admin=legacy-admin-1");
        old.changePassword("admin", "legacy-admin-1", "MyOwnPass-2026");
        Path seedFile = tempDir.resolve("_platform/seed-passwords.txt");
        Files.writeString(seedFile, "admin=legacy-admin-1\n");

        UserStore migrated = new UserStore(tempDir.toString(), "");

        assertTrue(migrated.checkPassword("admin", "MyOwnPass-2026"), "用户改过的密码不能被重置");
        assertFalse(migrated.isUsingInitialPassword("admin"));
        assertTrue(migrated.checkPassword("devops", UserStore.DEFAULT_INITIAL_PASSWORD));
        assertFalse(Files.exists(seedFile));
    }
}
