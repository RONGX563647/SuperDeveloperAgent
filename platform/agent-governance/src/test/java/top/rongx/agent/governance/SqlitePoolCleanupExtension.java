package top.rongx.agent.governance;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * 自动注册的测试收尾扩展：每个用例结束后释放独立 EMF 与连接池。
 *
 * <p>{@link GovernanceDb#sqlite} 会把 EMF 与 Hikari 池按 JDBC URL 缓存到静态表，
 * 而 EMF 会一直持有 {@code .db/-shm/-wal} 文件句柄；不释放会让 JUnit 的
 * {@code @TempDir} 清理在 Windows 上整批报 {@code Failed to close extension context}。
 * 与 agent-infra / agent-gateway 同款，各模块 test classpath 独立，因此各自注册一份。
 */
public class SqlitePoolCleanupExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        GovernanceDb.closeAll();
    }
}
