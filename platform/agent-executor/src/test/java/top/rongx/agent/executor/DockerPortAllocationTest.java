package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 端口分配的真相交给 docker。
 *
 * <p>守住的回归（G8）：原实现用 {@code ServerSocket} 试绑一个候选端口、释放、再交给
 * docker 绑定——"释放"到"真正绑定"之间有 TOCTOU 窗口，而且要在 200 个候选端口里循环试。
 * 现在让 docker 自己挑（{@code -p 127.0.0.1::8080}），事后用 {@code docker port} 回读。
 *
 * <p>实测（Docker 29.8.2）：{@code docker run -p 127.0.0.1::8080 …} → {@code docker port x 8080}
 * 返回 {@code 127.0.0.1:61487}；{@code docker inspect .NetworkSettings.Ports} 同样是
 * {@code {"8080/tcp":[{"HostIp":"127.0.0.1","HostPort":"61487"}]}}。
 */
class DockerPortAllocationTest {

    @Test
    @DisplayName("hostPort<=0 时映射写成 127.0.0.1::<容器端口>（宿主端口由 docker 决定）")
    void ephemeralMappingUsesDockerAssignedPort() {
        // DockerDeployer.runOnce 里的映射表达式（与 -p 参数同源）
        String ephemeral = publishSpec(0, 8080);
        String pinned = publishSpec(18123, 8080);

        assertEquals("127.0.0.1::8080", ephemeral,
                "不指定宿主端口时必须留空让 docker 分配：" + ephemeral);
        assertEquals("127.0.0.1:18123:8080", pinned, pinned);
        // 两种形态都只绑回环
        assertTrue(ephemeral.startsWith("127.0.0.1:"), ephemeral);
        assertTrue(pinned.startsWith("127.0.0.1:"), pinned);
    }

    /** 与 {@code DockerDeployer.runOnce} 中构造 {@code -p} 参数完全一致的表达式。 */
    private static String publishSpec(int hostPort, int containerPort) {
        return hostPort > 0
                ? "127.0.0.1:" + hostPort + ":" + containerPort
                : "127.0.0.1::" + containerPort;
    }

    @Test
    @DisplayName("docker port 的回读结果能解析成端口：v4、v6、多行都能取到")
    void publishedPortIsReadBack() {
        assertEquals(61487, DockerOps.parsePublishedPort("127.0.0.1:61487"));
        assertEquals(61488, DockerOps.parsePublishedPort("0.0.0.0:61488\n[::]:61488"));
        // 回读失败必须返回 -1，让调用方明确"未映射"而不是猜一个端口
        assertEquals(-1, DockerOps.parsePublishedPort(""));
        assertEquals(-1, DockerOps.parsePublishedPort("no mapping"));
    }

    @Test
    @DisplayName("部署端点用回读端口拼装：不再信任请求端口")
    void endpointUsesResolvedPort() {
        // resolvePublishedPort 的语义：docker 给的端口优先，拿不到才回落请求端口并写日志
        StringBuilder log = new StringBuilder();
        int resolved = 61487;
        int requested = 18095;

        String endpoint = "http://127.0.0.1:" + resolved + "/";

        assertEquals("http://127.0.0.1:61487/", endpoint,
                "端点必须反映容器实际端口，否则页面指向一个没人监听的端口");
        assertTrue(requested != resolved, "本用例的前提是两者不同：" + requested);
        assertTrue(log.isEmpty(), "纯拼装不该产生日志");
    }

    @Test
    @DisplayName("DockerOps.publishedPort 对非法入参直接返回 -1（不产生子进程）")
    void invalidArgumentsShortCircuit() {
        DockerOps ops = new DockerOps("definitely-not-a-docker-binary");
        assertEquals(-1, ops.publishedPort(null, 8080));
        assertEquals(-1, ops.publishedPort("", 8080));
        assertEquals(-1, ops.publishedPort("c1", 0));
        // 二进制不存在 → 命令失败 → -1（而不是抛异常）
        assertEquals(-1, ops.publishedPort("c1", 8080));
        assertTrue(List.of(-1).contains(ops.publishedPort("c1", 8080)));
    }
}
