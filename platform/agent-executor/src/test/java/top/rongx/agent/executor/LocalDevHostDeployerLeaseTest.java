package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.env.EnvironmentStore;

/** 停止本机服务时必须释放 ServiceLease，否则 env_runtime_status 会一直显示 RUNNING。 */
class LocalDevHostDeployerLeaseTest {

    @Test
    void stopReleasesBothServiceLeases(@TempDir Path repo) {
        List<String> deleted = new ArrayList<>();
        EnvironmentStore store = (EnvironmentStore) Proxy.newProxyInstance(
                EnvironmentStore.class.getClassLoader(),
                new Class<?>[]{EnvironmentStore.class},
                (proxy, method, args) -> {
                    if ("deleteServiceLease".equals(method.getName()) && args != null) {
                        deleted.add(args[0] + "/" + args[1] + "/" + args[2]);
                    }
                    return null;
                });

        boolean stopped = new LocalDevHostDeployer(store, null).stop(repo, "RUOYIWNZ", "dev");

        assertFalse(stopped);
        assertEquals(Set.of("RUOYIWNZ/dev/backend", "RUOYIWNZ/dev/frontend"), Set.copyOf(deleted));
    }
}
