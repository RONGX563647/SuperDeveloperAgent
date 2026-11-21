package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Docker 运维操作的唯一入口（纯函数部分）。
 *
 * <p>守住的回归：同一件事曾有 7 份实现（镜像探测）、5 份（运行态）、2 份（网络 ensure），
 * 超时从 5s 到 30s 不等。收口后这些解析逻辑只有一份，因此必须被测到。
 */
class DockerOpsTest {

    private final DockerOps ops = new DockerOps("docker");

    @Test
    @DisplayName("argv 拼装：二进制在前，参数原样跟随")
    void cmdAssemblesArgv() {
        assertEquals(List.of("docker", "ps", "-a"), ops.cmd("ps", "-a"));
        assertEquals(List.of("podman", "info"), new DockerOps("podman").cmd("info"));
        // 空值回落到 docker，不让 null 变成 argv 里的 "null"
        assertEquals("docker", new DockerOps(null).binary());
        assertEquals("docker", new DockerOps("  ").binary());
    }

    @Test
    @DisplayName("解析 docker ps 输出：名字/状态/细节，空行与空名字跳过")
    void parsePsRows() {
        List<DockerOps.PsRow> rows = DockerOps.parsePs(String.join("\n",
                "agent-dev-p-mysql|running|Up 2 hours",
                "agent-dev-p-redis|exited|Exited (0) 5 minutes ago",
                "|running|malformed-without-name",
                "",
                // 只有名字（旧版 --format 或列缺失）：行本身有效，状态为空
                "solo-name"));

        assertEquals(3, rows.size(), rows.toString());
        assertTrue(rows.get(0).running());
        assertFalse(rows.get(1).running());
        assertEquals("solo-name", rows.get(2).name());
        assertFalse(rows.get(2).running(), "没有状态信息时不能假定在运行");
    }

    @Test
    @DisplayName("旧版 docker 没有 State 列时按 Status 前缀判定运行态")
    void runningFallsBackToStatus() {
        assertTrue(new DockerOps.PsRow("c", "", "Up 3 minutes").running());
        assertFalse(new DockerOps.PsRow("c", "", "Exited (1)").running());
        assertFalse(new DockerOps.PsRow("c", "", "").running());
        assertFalse(new DockerOps.PsRow("c", null, null).running());
    }

    @Test
    @DisplayName("解析 docker port 输出：v4/v6 多行都能取到宿主端口")
    void parsePublishedPort() {
        assertEquals(61487, DockerOps.parsePublishedPort("127.0.0.1:61487"));
        assertEquals(8080, DockerOps.parsePublishedPort("0.0.0.0:8080"));
        assertEquals(8081, DockerOps.parsePublishedPort("0.0.0.0:8081\n[::]:8081"));
        // 无法解析时返回 -1（调用方据此报"未映射"，而不是猜一个端口）
        assertEquals(-1, DockerOps.parsePublishedPort(""));
        assertEquals(-1, DockerOps.parsePublishedPort(null));
        assertEquals(-1, DockerOps.parsePublishedPort("garbage"));
        assertEquals(-1, DockerOps.parsePublishedPort("127.0.0.1:"));
        assertEquals(-1, DockerOps.parsePublishedPort("127.0.0.1:70000"));
    }

    @Test
    @DisplayName("内置网络直接视为就绪：不会去 docker network create bridge")
    void builtinNetworksAreAlwaysReady() {
        // 生产实现的 ensureNetwork 对内置网络短路返回 null，不产生任何子进程。
        // 这里只验证"短路判断"的语义（用不存在的二进制也不应报错）。
        DockerOps dead = new DockerOps("definitely-not-a-docker-binary");
        assertEquals(null, dead.ensureNetwork("bridge"));
        assertEquals(null, dead.ensureNetwork("host"));
        assertEquals(null, dead.ensureNetwork("none"));
        assertEquals(null, dead.ensureNetwork(""));
        // 自定义网络会真的去问守护进程 → 二进制不存在时返回失败原因
        String problem = dead.ensureNetwork("agent-dev-net");
        assertTrue(problem != null && problem.contains("agent-dev-net"), String.valueOf(problem));
    }
}
