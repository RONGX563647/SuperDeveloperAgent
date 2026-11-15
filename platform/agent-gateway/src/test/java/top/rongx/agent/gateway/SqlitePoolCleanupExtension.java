package top.rongx.agent.gateway;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import top.rongx.agent.infra.sqlite.SqliteConnections;

/**
 * 与 agent-infra 同款的测试收尾扩展：每个用例后释放全部 SQLite 连接池。
 *
 * <p>gateway 的 Spring 上下文 / Store 测试也会在 {@code @TempDir} 下建库；
 * Windows 上池持有的 {@code .db/-shm/-wal} 句柄会让 JUnit 清理整批报
 * {@code Failed to close extension context}。各模块 test classpath 相互独立，
 * 因此这里各自注册一份极薄的适配（逻辑只有 closeAllPools 一行）。
 */
public class SqlitePoolCleanupExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        SqliteConnections.closeAllPools();
    }
}
