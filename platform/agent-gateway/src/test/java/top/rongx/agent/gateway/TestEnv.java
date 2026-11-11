package top.rongx.agent.gateway;

import java.util.concurrent.TimeUnit;

/**
 * 测试用的外部命令探测。
 *
 * <p>平台自己的构建跑在 {@code maven:3.9-eclipse-temurin-21} 容器里：
 * 该镜像没有 node，也没有 docker CLI/socket。依赖这些二进制的用例应在
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

    static boolean commandAvailable(String... argv) {
        try {
            Process process = new ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
