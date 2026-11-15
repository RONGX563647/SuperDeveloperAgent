package top.rongx.agent.gateway;

import java.util.List;
import top.rongx.agent.domain.process.BoundedCommand;

/**
 * 测试用的外部命令探测。
 *
 * <p>构建工具链镜像可通过 {@code agent.sandbox.image} 覆盖，不绑定某个固定标签；
 * 容器里通常没有 node，也没有 docker CLI/socket。依赖这些二进制的用例应在
 * {@code @BeforeAll} 里 assume 后跳过，而不是把"环境缺可执行文件"误报成流水线失败。
 */
final class TestEnv {

    private TestEnv() {
    }

    static boolean dockerAvailable() {
        return commandAvailable("docker", "info", "--format", "{{.ServerVersion}}");
    }

    static boolean nodeAvailable() {
        return commandAvailable("node", "--version");
    }

    /** 有界探测：并行排空输出 + 硬超时 + 杀进程树，避免 CLI 不退出时探测本身挂死。 */
    static boolean commandAvailable(String... argv) {
        return BoundedCommand.run(List.of(argv), 5_000L).ok();
    }
}
