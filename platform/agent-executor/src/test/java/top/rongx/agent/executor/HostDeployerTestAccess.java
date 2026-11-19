package top.rongx.agent.executor;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 测试专用：把 {@link HostDeployer} 的私有文件工具暴露给同包测试。
 *
 * <p>为什么不直接把方法改成 public：复制逻辑是部署器的内部实现细节，
 * 为了测试放宽生产代码的可见性会误导调用方。这个壳只活在测试源码集里，
 * 同包可见，不改动任何生产类。
 */
final class HostDeployerTestAccess {

    private HostDeployerTestAccess() {
    }

    static void copyReplacing(Path source, Path target) throws IOException {
        HostDeployer.copyReplacingForTest(source, target);
    }
}
