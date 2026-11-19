package top.rongx.agent.gateway;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import top.rongx.agent.governance.GovernanceDb;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 与 agent-infra 同款的测试收尾扩展：每个用例后释放独立 EMF 与连接池。
 *
 * <p>EMF 持有的 {@code .db/-shm/-wal} 句柄会让 JUnit 在 Windows 上清理
 * {@code @TempDir} 时整批报错；各模块 test classpath 独立，因此这里各自注册一份。
 */
public class SqlitePoolCleanupExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        PlatformDb.closeAll();
        GovernanceDb.closeAll();
    }
}
