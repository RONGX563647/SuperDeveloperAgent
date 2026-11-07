package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 容器命令构造（纯函数，不需要 Docker 守护进程）。 */
class DockerSandboxTest {

    private final DockerSandbox sandbox = new DockerSandbox(
            "docker", "maven:3.9-eclipse-temurin-21", "none", "2g", "2", 512, "1000:1000");

    @Test
    @DisplayName("命令包含全部隔离参数，且 argv 原样接在镜像后面")
    void wrapContainsIsolationFlags() {
        List<String> cmd = sandbox.wrap(Path.of("E:/work/RY"), List.of("mvn", "-v"));

        assertEquals("docker", cmd.get(0));
        assertEquals("run", cmd.get(1));
        assertTrue(cmd.contains("--rm"));
        assertTrue(cmd.contains("--entrypoint="), "必须清空 ENTRYPOINT，否则 argv 会被镜像入口吞掉");
        assertTrue(cmd.contains("--network") && cmd.contains("none"));
        assertTrue(cmd.contains("--user") && cmd.contains("1000:1000"));
        assertTrue(cmd.contains("--cap-drop") && cmd.contains("ALL"));
        assertTrue(cmd.contains("no-new-privileges"));
        assertTrue(cmd.contains("--memory") && cmd.contains("2g"));
        assertTrue(cmd.contains("--cpus") && cmd.contains("2"));
        assertTrue(cmd.contains("--pids-limit") && cmd.contains("512"));

        int imageIndex = cmd.indexOf("maven:3.9-eclipse-temurin-21");
        assertTrue(imageIndex > 0);
        assertEquals(List.of("mvn", "-v"), cmd.subList(imageIndex + 1, cmd.size()),
                "镜像之后的参数必须是原始 argv，一字不改");
    }

    @Test
    @DisplayName("工作区挂到 /ws 并作为工作目录")
    void mountAndWorkdir() {
        List<String> cmd = sandbox.wrap(Path.of("E:/work/RY"), List.of("true"));
        String volume = cmd.get(cmd.indexOf("-v") + 1);
        assertTrue(volume.endsWith(":/ws"), "挂载目标必须是 /ws，实际：" + volume);
        assertEquals("/ws", cmd.get(cmd.indexOf("--workdir") + 1));
    }

    @Test
    @DisplayName("Windows 反斜杠路径转成 Docker 可识别的形式")
    void windowsPathNormalized() {
        String path = DockerSandbox.hostPath(Path.of("E:\\CODE\\Agent\\x"));
        assertEquals("E:/CODE/Agent/x", path);
    }

    @Test
    @DisplayName("空/非法配置有安全默认值")
    void defaultsAreSafe() {
        DockerSandbox d = new DockerSandbox(null, "img", null, null, null, 0, null);
        List<String> cmd = d.wrap(Path.of("E:/w"), List.of("x"));
        assertTrue(cmd.contains("bridge"), "默认能拉依赖（build-net/none 按需显式指定）");
        assertTrue(cmd.contains("1000:1000"), "默认非 root");
        assertTrue(cmd.contains("512"), "默认 pids 限制");
    }

    @Test
    @DisplayName("网络档位可显式收紧到 none")
    void networkCanBeLockedDown() {
        DockerSandbox locked = new DockerSandbox("docker", "img", "none", "2g", "2", 512, "1000:1000");
        List<String> cmd = locked.wrap(Path.of("E:/w"), List.of("x"));
        assertEquals("none", cmd.get(cmd.indexOf("--network") + 1));
    }
}
