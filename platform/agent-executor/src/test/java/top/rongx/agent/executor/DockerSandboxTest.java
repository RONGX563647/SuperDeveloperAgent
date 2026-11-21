package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.error.RecipeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        // 该断言只描述 Windows 宿主行为；Linux 容器构建里 Path 语义不同，跳过而不是误报失败。
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "仅在 Windows 宿主校验盘符路径归一化");
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
    @DisplayName("corepack 用可写缓存目录，避免非 root 下写 /usr/local/bin")
    void corepackUsesWritableHome() {
        List<String> cmd = sandbox.wrap(Path.of("E:/work/RY"), List.of("corepack", "pnpm", "-v"));
        assertTrue(cmd.contains("COREPACK_HOME=/tmp/corepack"), cmd.toString());
        assertTrue(cmd.contains("COREPACK_ENABLE_DOWNLOAD_PROMPT=0"), cmd.toString());
    }

    @Test
    @DisplayName("启用缓存卷：pnpm store 与 npm 镜像源通过 env 注入容器")
    void npmCacheAndRegistryEnv() {
        DockerSandbox cached = new DockerSandbox("docker", "node:20-bookworm", "bridge",
                "2g", "2", 512, "1000:1000", "agent-cache")
                .setNpmRegistry("https://registry.npmmirror.com");
        List<String> cmd = cached.wrap(Path.of("E:/work/RY"), List.of("corepack", "pnpm", "install"));
        assertTrue(cmd.contains("npm_config_store_dir=/cache/pnpm"), cmd.toString());
        assertTrue(cmd.contains("npm_config_registry=https://registry.npmmirror.com"), cmd.toString());
        // 缓存卷按项目隔离：名字里既可读（便于 docker volume ls 排障）又带路径哈希（唯一性来源）
        assertTrue(cmd.stream().anyMatch(a -> a.matches("agent-cache-ry-[0-9a-f]{16}:/cache")),
                "缓存卷按项目隔离且带路径哈希：" + cmd);
        // 未配置镜像源时不应注入，避免覆盖用户 .npmrc
        List<String> plain = sandbox.wrap(Path.of("E:/work/RY"), List.of("true"));
        assertTrue(plain.stream().noneMatch(arg -> arg.startsWith("npm_config_registry=")),
                plain.toString());
    }

    @Test
    @DisplayName("缓存卷键：不同绝对路径必然不同卷，同一路径跨运行稳定")
    void cacheKeyIsPathScoped() {
        // 同名目录、不同父目录 → 不同卷（原实现只看目录名，两者会撞在一起）
        String a = DockerSandbox.cacheKey(Path.of("E:/work/alpha/RY"));
        String b = DockerSandbox.cacheKey(Path.of("E:/work/beta/RY"));
        assertNotEquals(a, b, "同名工程在不同父目录下不能共用缓存卷：" + a + " vs " + b);
        assertEquals(a, DockerSandbox.cacheKey(Path.of("E:/work/alpha/RY")), "同一路径必须稳定命中");
        // 可读前缀保留，唯一性由末尾 16 位哈希保证
        assertTrue(a.startsWith("ry-"), a);
        assertTrue(a.matches("ry-[0-9a-f]{16}"), "哈希尾巴不能丢熵：" + a);
        // worktree：键取所属项目根，不同 worktree 命中同一个卷
        String wt1 = DockerSandbox.cacheKey(Path.of("E:/work/alpha/worktrees/wt-run-1"));
        String wt2 = DockerSandbox.cacheKey(Path.of("E:/work/alpha/worktrees/wt-run-2"));
        assertEquals(wt1, wt2, "同一项目的不同 worktree 必须共用缓存卷");
        assertEquals(DockerSandbox.cacheKey(Path.of("E:/work/alpha")), wt1,
                "worktree 的键必须等于项目根的键");
    }

    @Test
    @DisplayName("隔离不变量：任何配置组合都不能放宽边界")
    void wrapNeverRelaxesIsolation() {
        Path ws = Path.of("E:/work/inv");
        for (DockerSandbox candidate : List.of(
                sandbox,
                new DockerSandbox(null, "img", null, null, null, 0, null, "agent-cache"),
                new DockerSandbox("docker", "img", "none", "1g", "1", 64, "1000:1000", "agent-cache")
                        .setRuntime("runsc"))) {
            List<String> cmd = candidate.wrap(ws, List.of("mvn", "-v"));
            String joined = String.join(" ", cmd);

            assertTrue(joined.contains("--cap-drop ALL"), joined);
            assertTrue(joined.contains("no-new-privileges"), joined);
            assertTrue(joined.contains("--read-only"), joined);
            assertFalse(joined.contains("--privileged"), joined);
            assertFalse(joined.contains("--cap-add"), joined);
            assertFalse(joined.contains("docker.sock"), "宿主 socket 永不挂载：" + joined);
            assertFalse(joined.contains("--pid=host"), joined);
            assertFalse(joined.contains("--network=host"), joined);
            assertFalse(joined.contains("--userns=host"), joined);
            // 只允许工作区 +（可选）缓存卷两类挂载。只数镜像之前的 "-v"：
            // 业务 argv 里也可能有 -v（如 `mvn -v`），那是参数不是挂载。
            int imageAt = cmd.indexOf(candidate.image());
            long mounts = cmd.subList(0, imageAt).stream().filter("-v"::equals).count();
            assertEquals(candidate.allowedMounts(ws).size(), mounts,
                    "挂载数量必须等于 allowedMounts：" + joined);
        }
    }

    @Test
    @DisplayName("容器有 init（僵尸回收）与停止宽限，避免 pids 上限被短命子进程撞满")
    void containerUsesInitAndStopGrace() {
        List<String> cmd = sandbox.wrap(Path.of("E:/w"), List.of("x"));
        assertTrue(cmd.contains("--init"), "pid 1 必须是会回收僵尸的 init：" + cmd);
        assertEquals("10", cmd.get(cmd.indexOf("--stop-timeout") + 1), cmd.toString());
        assertEquals("core=0", cmd.get(cmd.indexOf("--ulimit") + 1), cmd.toString());
    }

    @Test
    @DisplayName("网络档位可显式收紧到 none")
    void networkCanBeLockedDown() {
        DockerSandbox locked = new DockerSandbox("docker", "img", "none", "2g", "2", 512, "1000:1000");
        List<String> cmd = locked.wrap(Path.of("E:/w"), List.of("x"));
        assertEquals("none", cmd.get(cmd.indexOf("--network") + 1));
    }

    @Test
    @DisplayName("配置 gVisor 运行时后，docker run 带上 --runtime，默认回落 runc")
    void runtimeFlagOptIn() {
        List<String> plain = sandbox.wrap(Path.of("E:/w"), List.of("x"));
        assertTrue(plain.stream().noneMatch("--runtime"::equals), "默认不加 --runtime，走 runc：" + plain);

        DockerSandbox gvisor = new DockerSandbox(
                "docker", "img", "bridge", "2g", "2", 512, "1000:1000").setRuntime("runsc");
        List<String> cmd = gvisor.wrap(Path.of("E:/w"), List.of("x"));
        assertEquals("runsc", cmd.get(cmd.indexOf("--runtime") + 1), cmd.toString());
    }

    @Test
    @DisplayName("根文件系统只读，/tmp 用可写且可执行的 tmpfs")
    void rootfsIsReadOnly() {
        List<String> cmd = sandbox.wrap(Path.of("E:/w"), List.of("x"));
        assertTrue(cmd.contains("--read-only"), "镜像层必须只读：" + cmd);
        assertEquals("/tmp:rw,exec,mode=1777", cmd.get(cmd.indexOf("--tmpfs") + 1),
                "tmpfs 必须带 exec：noexec 下 JVM 无法 dlopen jansi 原生库，Maven 直接崩：" + cmd);
        assertTrue(cmd.contains("HOME=/tmp"),
                "只读根下 /home 不可写，必须把 HOME 指到 tmpfs：" + cmd);
    }

    @Test
    @DisplayName("容器带生命周期标签，便于启动时清扫遗留容器")
    void containerIsLabelled() {
        List<String> cmd = sandbox.wrap(Path.of("E:/w"), List.of("x"));
        assertTrue(cmd.contains("agent.sandbox.managed=true"), cmd.toString());
    }

    @Test
    @DisplayName("镜像名是 docker run 的位置参数，注入型取值必须在构造/替换处被拒")
    void rejectsInjectedImageRef() {
        // image: --privileged 会被 docker 当成自己的选项，直接把隔离拆掉
        assertThrows(RecipeException.class,
                () -> new DockerSandbox("docker", "--privileged", "none", "2g", "2", 512, "1000:1000"));
        assertThrows(RecipeException.class,
                () -> new DockerSandbox("docker", "--volume=/:/host", "none", "2g", "2", 512, "1000:1000"));
        // stage.image 来自仓库里的流水线定义，同一条路径也要挡
        assertThrows(RecipeException.class, () -> sandbox.withImage("-v /:/host"));
        assertThrows(RecipeException.class, () -> sandbox.withImage("maven:3.9 mvn -v"));
        assertThrows(RecipeException.class, () -> sandbox.withImage(""));
        // 合法引用照旧
        assertEquals("maven:3.9-eclipse-temurin-21", sandbox.withImage("maven:3.9-eclipse-temurin-21").image());
    }

    @Test
    @DisplayName("遗留容器清扫：只删本机且原进程已退出的，别人的容器不碰")
    void staleCandidatesAreScoped() {
        String ps = String.join("\n",
                "agent-act-deadbeef0001|host-a:999999",   // 本机、进程已退出 → 删
                "agent-act-deadbeef0002|host-a:1",        // 本机、进程还在 → 保留
                "agent-act-deadbeef0003|host-b:999999",   // 别的主机 → 保留
                "agent-act-deadbeef0004|",               // 旧版本没归属标签 → 保留
                "agent-act-deadbeef0005|host-a:not-a-pid", // 归属信息损坏 → 保留
                "other-container|host-a:999999",          // 不是平台容器 → 保留
                "");

        List<String> stale = DockerSandbox.staleCandidates(ps, "host-a", pid -> pid == 1L);

        assertEquals(List.of("agent-act-deadbeef0001"), stale);
        assertTrue(DockerSandbox.staleCandidates("", "host-a", pid -> false).isEmpty());
        assertTrue(DockerSandbox.staleCandidates(null, "host-a", pid -> false).isEmpty());
    }
}
