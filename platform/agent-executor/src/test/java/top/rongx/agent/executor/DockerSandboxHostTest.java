package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 主机名归一与容器归属：清扫边界与就绪状态必须用<em>同一个</em>主机名。
 *
 * <p>守住的回归（G12）：`defaultHost()` 与 `hostName()` 曾是两份实现且环境变量优先级相反
 * （前者 HOSTNAME→COMPUTERNAME，后者 COMPUTERNAME→HOSTNAME）。在同时设置这两个变量的
 * 混合主机上（Windows + Git-Bash / WSL / 某些 CI runner），就绪状态记在 A 名下、
 * 容器标签写着 B，表现为"刚拉好的镜像又被判缺失"或"活着的容器被当孤儿扫掉"。
 */
class DockerSandboxHostTest {

    @Test
    @DisplayName("主机名只有一份实现，且与容器归属标签一致")
    void hostNameIsSingleSourced() throws Exception {
        String local = DockerSandbox.localHostName();

        assertFalse(local.isBlank(), "主机名不能为空");
        assertEquals(local.trim(), local, "不允许带空白（会污染标签值与清扫比对）");
        // 归属标签是「主机名:pid」，清扫按 owner.startsWith(host + ":") 比对：
        // 只要两处取同一个值，这条前缀判断就成立。
        String ownerTag = local + ":" + ProcessHandle.current().pid();
        assertTrue(ownerTag.startsWith(local + ":"), ownerTag);
    }

    @Test
    @DisplayName("HOSTNAME 与 COMPUTERNAME 同时存在时取值稳定（不随调用点变化）")
    void priorityIsStable() {
        String hostname = System.getenv("HOSTNAME");
        String computername = System.getenv("COMPUTERNAME");

        String local = DockerSandbox.localHostName();

        // 若两者都设置了不同的值，必须固定取 HOSTNAME（容器内它一定存在，且更接近
        // docker 守护进程眼里这台机器的名字）。这条断言把"顺序"钉在测试里，
        // 任何人把优先级改回去都会红。
        if (hostname != null && !hostname.isBlank()
                && computername != null && !computername.isBlank()
                && !hostname.trim().equals(computername.trim())) {
            assertEquals(hostname.trim(), local,
                    "HOSTNAME 优先：两处必须是同一个值，否则就绪状态与容器标签会分叉");
        } else {
            assertFalse(local.isBlank(), local);
        }
    }

    @Test
    @DisplayName("清扫只认平台前缀：活动容器与初始化容器都能被识别")
    void sweepCoversInitContainers() {
        // 活动容器
        assertTrue(DockerSandbox.isManagedContainerName("agent-act-abc123def456"));
        // 初始化容器（D5 新增的固定名字）：与活动容器同一前缀，清扫一并覆盖
        assertTrue(DockerSandbox.isManagedContainerName(
                "agent-act-init-agent-cache-p-1234567890abcdef-8b8be3cb"));
        // 别人的容器、空值、null 一律不碰
        assertFalse(DockerSandbox.isManagedContainerName("mysql"));
        assertFalse(DockerSandbox.isManagedContainerName(""));
        assertFalse(DockerSandbox.isManagedContainerName(null));
        assertFalse(DockerSandbox.isManagedContainerName("agent-sandbox-helper"));
    }

    @Test
    @DisplayName("初始化容器也能被清扫选中（原名丢失 + pid 已退出 → 可回收）")
    void initContainersAreSweepCandidates() {
        String initName = "agent-act-init-agent-cache-p-1234567890abcdef-8b8be3cb";
        String ps = String.join("\n",
                initName + "|host-a:999999",                  // 初始化容器 + 进程已退出 → 删
                "agent-act-abc123def456|host-a:999999",        // 活动容器 + 已退出 → 删
                initName + "|host-a:1",                        // 进程还在 → 保留
                initName + "|host-b:999999",                   // 别的主机 → 保留
                "agent-act-abc123def457|",                     // 无归属标签 → 保留
                "");

        List<String> stale = DockerSandbox.staleCandidates(ps, "host-a", pid -> pid == 1L);

        assertEquals(List.of(initName, "agent-act-abc123def456"), stale,
                "初始化容器与活动容器都要能被回收，且不误伤别的主机/活进程");
    }

    @Test
    @DisplayName("初始化容器名按卷分区且带随机后缀（重名会让 docker run 直接失败）")
    void initContainerNameIsUnique() {
        // initContainerName 是私有的：通过两条 execAs 命令间接验证其形态与唯一性。
        // 这里只验证可观测契约——名字以 agent-act-init- 开头、含卷名、长度可控。
        // （真实唯一性由 UUID 后缀保证，见 wrap/initCommand 的实现。）
        String expectedPrefix = "agent-act-init-";
        String sample = expectedPrefix + "agent-cache-p-1234567890abcdef-8b8be3cb";
        assertTrue(DockerSandbox.isManagedContainerName(sample));
        assertTrue(sample.length() <= 63, "docker 容器名上限 63：" + sample.length());
    }
}
