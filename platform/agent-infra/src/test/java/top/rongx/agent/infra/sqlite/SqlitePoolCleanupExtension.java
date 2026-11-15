package top.rongx.agent.infra.sqlite;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 自动注册的测试收尾扩展：每个用例结束后释放全部 SQLite 连接池。
 *
 * <p>池会一直持有 {@code .db/-shm/-wal} 文件句柄，Windows 上只要有残留，
 * JUnit 的 {@code @TempDir} 清理就会整批报 {@code Failed to close extension context}，
 * 把真正通过的测试伪装成错误。通过 JUnit 5 扩展自动检测注册（见
 * {@code src/test/resources/junit-platform.properties} 与 META-INF/services），
 * 无需二十多个 Store 测试类各自加 {@code @AfterEach}。
 */
public class SqlitePoolCleanupExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        SqliteConnections.closeAllPools();
    }
}
