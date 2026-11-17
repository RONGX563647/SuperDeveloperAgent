package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Docker CLI 二进制来源：空值回落 docker，自定义二进制进入命令首元素。 */
class DockerCliTest {

    @Test
    void blankBinaryFallsBackToDocker() {
        assertEquals("docker", new DockerCli(null).binary());
        assertEquals("docker", new DockerCli("   ").binary());
        assertEquals("docker", DockerCli.defaults().binary());
    }

    @Test
    void cmdPrependsConfiguredBinary() {
        List<String> argv = new DockerCli(" podman ").cmd("run", "-d");
        assertEquals("podman", argv.get(0));
        assertEquals(3, argv.size());
    }

    @Test
    void cmdIsImmutable() {
        List<String> argv = DockerCli.defaults().cmd(List.of("ps"));
        assertThrows(UnsupportedOperationException.class, () -> argv.add("-a"));
    }
}
