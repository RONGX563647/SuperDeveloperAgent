package top.rongx.agent.infra.sqlite;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 自动注册的测试收尾扩展：每个用例结束后释放独立 EMF 与连接池。
 *
 * <p>EMF 会一直持有 {@code .db/-shm/-wal} 文件句柄；不释放会让 JUnit 的
 * {@code @TempDir} 清理整批报 {@code Failed to close extension context}。
 * 通过 JUnit 5 扩展自动检测注册（见 {@code src/test/resources/junit-platform.properties}
 * 与 META-INF/services），无需各 Store 测试类各自清理。
 */
public class SqlitePoolCleanupExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        PlatformDb.closeAll();
    }
}
