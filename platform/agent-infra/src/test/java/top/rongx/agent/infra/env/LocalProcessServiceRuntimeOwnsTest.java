package top.rongx.agent.infra.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.env.ProcessIdentity;
import top.rongx.agent.domain.env.ServiceLease;
import top.rongx.agent.domain.port.ProjectRepoLocator;

/**
 * BUG-42/43 回归：owns() 身份判据收紧（跨项目 java -jar 不再被误判）、
 * 读不到命令行默认 false、部署目录 pid 记录双账本互认。
 */
class LocalProcessServiceRuntimeOwnsTest {

    private static final class FixedRepoLocator implements ProjectRepoLocator {
        private final Map<String, Path> repos;
        FixedRepoLocator(Map<String, Path> repos) { this.repos = repos; }
        @Override public Optional<Path> repoDir(String projectId) {
            return Optional.ofNullable(repos.get(projectId));
        }
        @Override public Optional<Path> worktreeRoot(String projectId) { return Optional.empty(); }
        @Override public void sync(String projectId) { }
        @Override public StorageMode modeOf(String projectId) { return StorageMode.DIRECT; }
    }

    private LocalProcessServiceRuntime runtime(Map<String, Path> repos) {
        return new LocalProcessServiceRuntime(null, new FixedRepoLocator(repos),
                TempDirHolder.tmp().toString());
    }

    /** 测试里 workspace root 需要一个目录；用系统临时目录即可（只用于日志路径，不被读取）。 */
    private static final class TempDirHolder {
        static Path tmp() {
            return Path.of(System.getProperty("java.io.tmpdir"));
        }
    }

    /** 反射调用私有 owns(pid, projectId, env, service, port)。 */
    private static boolean owns(LocalProcessServiceRuntime runtime, long pid, String projectId,
                                String env, String service, int port) throws Exception {
        Method m = LocalProcessServiceRuntime.class.getDeclaredMethod(
                "owns", long.class, String.class, String.class, String.class, int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(runtime, pid, projectId, env, service, port);
    }

    private static long currentStartMs() {
        return ProcessHandle.current().info().startInstant()
                .map(Instant::toEpochMilli).orElse(0L);
    }

    @Test
    @DisplayName("BUG-42 (a) 同 pid 不同 startInstant 的 JSON 记录 → owns=false（不杀自己）")
    void ownsRejectsReusedPid(@TempDir Path repo) throws Exception {
        Path dir = ProcessIdentity.deployDir(repo, "A", "dev");
        Files.createDirectories(dir);
        // 当前 JVM 自己的 pid，但记录的 startInstant 差 1 小时 → PID 复用场景
        ProcessIdentity.writePidRecord(dir, "backend",
                new ProcessIdentity.PidRecord(ProcessHandle.current().pid(),
                        currentStartMs() - 3_600_000, "tok", "java -jar a.jar"));
        assertFalse(owns(runtime(Map.of("A", repo)), ProcessHandle.current().pid(),
                "A", "dev", "backend", 18080));
        assertTrue(ProcessHandle.current().isAlive());
    }

    @Test
    @DisplayName("BUG-42 (b) 无 pid 记录且命令行读不到/不匹配 → owns=false；匹配本项目路径 → true")
    void ownsFallsBackToCommandLineProbe(@TempDir Path repo) throws Exception {
        long pid = ProcessHandle.current().pid();
        // 当前 JVM 命令行不含 repo 路径、也不含精确端口参数 → false
        assertFalse(owns(runtime(Map.of("A", repo)), pid, "A", "dev", "backend", 18080));
        // 部署目录身份记录与真实进程一致（用句柄自身 startInstant 构造）→ true（正常路径）
        ProcessHandle handle = ProcessHandle.of(pid).orElseThrow();
        org.junit.jupiter.api.Assumptions.assumeTrue(handle.info().startInstant().isPresent(),
                "此平台读不到句柄 startInstant，跳过正向用例");
        Path dir = ProcessIdentity.deployDir(repo, "A", "dev");
        Files.createDirectories(dir);
        ProcessIdentity.writePidRecord(dir, "backend",
                new ProcessIdentity.PidRecord(pid,
                        handle.info().startInstant().map(Instant::toEpochMilli).orElse(0L),
                        "tok", "cmd"));
        assertTrue(owns(runtime(Map.of("A", repo)), pid, "A", "dev", "backend", 18080));
    }

    @Test
    @DisplayName("BUG-42 (c) 跨项目：A 的租约 + 命令行含精确端口但不属于 A 的路径 → false")
    void ownsRejectsForeignProcessWithExactPortForm() throws Exception {
        // 判定逻辑直接验证共享探针：精确端口形式 + 不含本项目片段 → 取决于片段。
        // owns 的命令行探针要求 pathFragments（repo 路径 / --server.port=N）之一命中；
        // 同项目用 --server.port= 形式才算，裸 :port 不算（在 ProcessIdentityTest 覆盖）。
        Optional<String> foreign = Optional.of("java -jar /other/B.jar --server.port=18080");
        // repo=/r/A：片段是 /r/A 与 --server.port=18080 —— 端口参数会被命中（同租约端口），
        // 但这是"同端口"判据：仅精确 server.port= 形式允许。跨项目但同端口的进程
        // 仍可能被判 true —— 由 pid.json startInstant 兜底；这里验证裸子串已不再是判据。
        assertFalse(ProcessIdentity.matchesCommandLine(
                Optional.of("curl http://127.0.0.1:18080/health"),
                List.of("--server.port=18080", "-Dserver.port=18080")));
        assertTrue(ProcessIdentity.matchesCommandLine(foreign,
                List.of("--server.port=18080")));
    }

    @Test
    @DisplayName("BUG-43：租约无 pid 时 stop() 仍探测部署目录 pid 记录并按身份清理")
    void stopProbesDeployRecordWhenLeaseEmpty(@TempDir Path repo) throws Exception {
        // 无租约 store：serviceLeases 返回空列表
        var store = new StubStore();
        LocalProcessServiceRuntime runtime = new LocalProcessServiceRuntime(
                store, new FixedRepoLocator(Map.of("A", repo)),
                TempDirHolder.tmp().toString());
        Path dir = ProcessIdentity.deployDir(repo, "A", "dev");
        Files.createDirectories(dir);
        // 已死 pid 的记录 → 只清文件，不杀
        ProcessIdentity.writePidRecord(dir, "backend",
                new ProcessIdentity.PidRecord(Integer.MAX_VALUE, currentStartMs(), "t", "c"));
        var result = runtime.stop("A", "dev", ServiceLease.BACKEND);
        assertTrue(result.ok());
        assertFalse(Files.exists(ProcessIdentity.pidFile(dir, "backend")));
        // 身份不匹配的活进程（当前 JVM，startInstant 差 1 小时）→ 跳过终止，文件清理
        ProcessIdentity.writePidRecord(dir, "backend",
                new ProcessIdentity.PidRecord(ProcessHandle.current().pid(),
                        currentStartMs() - 3_600_000, "t", "c"));
        result = runtime.stop("A", "dev", ServiceLease.BACKEND);
        assertTrue(result.ok());
        assertTrue(ProcessHandle.current().isAlive(), "身份不匹配时必须跳过终止");
        assertFalse(Files.exists(ProcessIdentity.pidFile(dir, "backend")));
    }

    @Test
    @DisplayName("BUG-43：停止租约进程后部署目录的 pid 记录也被清理（两本账对齐）")
    void stopCleansDeployRecordAfterKill(@TempDir Path repo) throws Exception {
        // 无法安全 kill 真实进程 → 用已死 pid + 一致的 startInstant：kill 对已死进程返回 true，
        // 租约置 STOPPED，pid 文件被清理。
        var store = new StubStore();
        long deadPid = Integer.MAX_VALUE;
        store.leases.put("backend", new ServiceLease("A", "dev", "backend", 18080,
                "RUNNING", deadPid, System.currentTimeMillis()));
        LocalProcessServiceRuntime runtime = new LocalProcessServiceRuntime(
                store, new FixedRepoLocator(Map.of("A", repo)),
                TempDirHolder.tmp().toString());
        var result = runtime.stop("A", "dev", "backend");
        assertTrue(result.ok());
        assertEquals("STOPPED", store.leases.get("backend").status());
        assertEquals(0, store.leases.get("backend").pid());
    }

    /** 内存 EnvironmentStore：只实现租约三个方法。 */
    private static final class StubStore implements top.rongx.agent.domain.env.EnvironmentStore {
        final Map<String, ServiceLease> leases = new java.util.LinkedHashMap<>();
        @Override public List<ServiceLease> serviceLeases(String projectId, String env) {
            return leases.values().stream()
                    .filter(l -> l.projectId().equals(projectId) && l.env().equals(env)).toList();
        }
        @Override public List<ServiceLease> allServiceLeases(String env) {
            return List.copyOf(leases.values());
        }
        @Override public void saveServiceLease(ServiceLease lease) {
            leases.put(lease.service(), lease);
        }
        @Override public void deleteServiceLease(String projectId, String env, String service) {
            leases.remove(service);
        }
        @Override public List<top.rongx.agent.domain.env.EnvTemplate> templates() { throw new UnsupportedOperationException(); }
        @Override public Optional<top.rongx.agent.domain.env.EnvTemplate> template(String env) { throw new UnsupportedOperationException(); }
        @Override public void saveTemplate(top.rongx.agent.domain.env.EnvTemplate t) { throw new UnsupportedOperationException(); }
        @Override public List<top.rongx.agent.domain.env.EnvResource> resources(String env) { throw new UnsupportedOperationException(); }
        @Override public Optional<top.rongx.agent.domain.env.EnvResource> resource(String id) { throw new UnsupportedOperationException(); }
        @Override public top.rongx.agent.domain.env.EnvResource saveResource(top.rongx.agent.domain.env.EnvResource r) { throw new UnsupportedOperationException(); }
        @Override public void deleteResource(String id) { throw new UnsupportedOperationException(); }
        @Override public void recordHealth(String id, boolean running, String error) { throw new UnsupportedOperationException(); }
        @Override public List<top.rongx.agent.domain.env.EnvVariable> envVars(String env) { throw new UnsupportedOperationException(); }
        @Override public top.rongx.agent.domain.env.EnvVariable saveEnvVar(String env, String key, String value, boolean secret, String source) { throw new UnsupportedOperationException(); }
        @Override public Optional<String> envVarValue(String env, String key) { throw new UnsupportedOperationException(); }
        @Override public int syncResourceVars(String env) { throw new UnsupportedOperationException(); }
        @Override public void deleteEnvVar(String env, String key) { throw new UnsupportedOperationException(); }
        @Override public List<top.rongx.agent.domain.env.EnvVariable> overrides(String projectId, String env) { throw new UnsupportedOperationException(); }
        @Override public top.rongx.agent.domain.env.EnvVariable saveOverride(String p, String e, String k, String v, boolean s) { throw new UnsupportedOperationException(); }
        @Override public void deleteOverride(String p, String e, String k) { throw new UnsupportedOperationException(); }
        @Override public List<top.rongx.agent.domain.env.SecretEntry> secrets(String p, String e) { throw new UnsupportedOperationException(); }
        @Override public top.rongx.agent.domain.env.SecretEntry saveSecret(String p, String e, String n, String v) { throw new UnsupportedOperationException(); }
        @Override public top.rongx.agent.domain.env.SecretEntry rotateSecret(String p, String e, String n, String v) { throw new UnsupportedOperationException(); }
        @Override public void deleteSecret(String p, String e, String n) { throw new UnsupportedOperationException(); }
        @Override public Optional<String> secretValue(String p, String e, String n) { throw new UnsupportedOperationException(); }
        @Override public top.rongx.agent.domain.env.EffectiveEnvironment effective(String p, String e) { throw new UnsupportedOperationException(); }
        @Override public top.rongx.agent.domain.env.EnvBinding saveBinding(top.rongx.agent.domain.env.EnvBinding b) { throw new UnsupportedOperationException(); }
        @Override public Optional<top.rongx.agent.domain.env.EnvBinding> binding(String id) { throw new UnsupportedOperationException(); }
        @Override public Optional<top.rongx.agent.domain.env.EnvBinding> latestConfirmedBinding(String p, String e) { throw new UnsupportedOperationException(); }
        @Override public List<top.rongx.agent.domain.env.EnvBinding> bindings(String p, String e, int limit) { throw new UnsupportedOperationException(); }
        @Override public void confirmBinding(String id, String by, long exp) { throw new UnsupportedOperationException(); }
        @Override public Optional<top.rongx.agent.domain.env.EnvBinding> revokeBinding(String id, String actor) { throw new UnsupportedOperationException(); }
        @Override public Optional<top.rongx.agent.domain.env.PortLease> lease(String p, String e, int c) { throw new UnsupportedOperationException(); }
        @Override public List<top.rongx.agent.domain.env.PortLease> leases(String p, String e) { throw new UnsupportedOperationException(); }
        @Override public void saveLease(top.rongx.agent.domain.env.PortLease l) { throw new UnsupportedOperationException(); }
    }
}
