package top.rongx.agent.gateway.settings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.ServiceLease;
import top.rongx.agent.domain.env.ServiceRuntime;

/**
 * dev 本地前后端服务的端口分配与生命周期管理。
 *
 * <p>端口段固定（避免与中间件/平台自己抢端口）：
 * <ul>
 *   <li>dev 后端 {@code 18080–18179}，dev 前端 {@code 15173–15272}；</li>
 *   <li>test 后端 {@code 28080–28179}，test 前端 {@code 25173–25272}（容器发布端口）。</li>
 * </ul>
 *
 * <p>候选端口 = 段起点 + hash(projectId) % 段长；冲突时向后线性探测，
 * 并排除其它项目租约、系统已监听端口、平台自身端口与常用保留端口。
 */
@Component
public class LocalServiceManager {

    private static final int SPAN = 100;
    /** 平台/常见组件占用，永不分给业务服务。 */
    private static final Set<Integer> RESERVED = Set.of(
            22, 80, 443, 3306, 5432, 6379, 5672, 9000, 9001, 27017, 2375, 2376, 8080, 8443, 5173);

    private final EnvironmentStore store;
    private final ServiceRuntime runtime;
    private final int platformPort;

    public LocalServiceManager(EnvironmentStore store, ServiceRuntime runtime,
                               @Value("${server.port:8090}") int platformPort) {
        this.store = store;
        this.runtime = runtime;
        this.platformPort = platformPort;
    }

    /** 服务清单（含实时状态、启动条件与最近日志位置；没有租约时给出"未分配"）。 */
    public List<Map<String, Object>> services(String projectId, String env) {
        requireLocalEnv(env);
        List<ServiceLease> leases = store.serviceLeases(projectId, env);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String service : List.of(ServiceLease.BACKEND, ServiceLease.FRONTEND)) {
            ServiceLease lease = leases.stream()
                    .filter(l -> l.service().equals(service)).findFirst().orElse(null);
            ServiceRuntime.Availability ready = runtime.availability(projectId, service);
            int port = lease == null ? 0 : lease.hostPort();
            String status = lease == null ? "UNKNOWN" : runtime.status(projectId, env, service);
            // 平台没在跑、但端口被别的进程占着：说清楚是"外部占用"，避免用户以为启动成功了
            if (!"RUNNING".equals(status) && port > 0 && !free(port)) {
                status = "EXTERNAL";
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", service);
            row.put("env", env);
            row.put("port", port);
            row.put("status", status);
            row.put("pid", lease == null ? 0 : lease.pid());
            row.put("updatedAt", lease == null ? 0 : lease.updatedAt());
            row.put("url", port <= 0 ? "" : "http://127.0.0.1:" + port);
            row.put("supported", ready.supported());
            row.put("hint", ready.hint());
            out.add(row);
        }
        return out;
    }

    /** 分配端口并启动（先停旧进程）。启动条件不满足时直接给出可操作的原因，不占用端口。 */
    public ServiceRuntime.Result start(String projectId, String env, String service) {
        requireLocalEnv(env);
        ServiceRuntime.Availability ready = runtime.availability(projectId, service);
        if (!ready.supported()) {
            return ServiceRuntime.Result.failed(ready.hint());
        }
        int port = allocate(projectId, env, service);
        return runtime.start(projectId, env, service, port);
    }

    /** 最近启动日志（定位"启动即退出"的原因）。 */
    public List<String> logs(String projectId, String env, String service, int lines) {
        requireLocalEnv(env);
        return runtime.logTail(projectId, service, lines);
    }

    public ServiceRuntime.Result stop(String projectId, String env, String service) {
        requireLocalEnv(env);
        return runtime.stop(projectId, env, service);
    }

    /** 重启 = 停止 + 启动（端口沿用）。 */
    public ServiceRuntime.Result restart(String projectId, String env, String service) {
        requireLocalEnv(env);
        runtime.stop(projectId, env, service);
        return start(projectId, env, service);
    }

    // ---------------------------------------------------------------- 端口

    public int allocate(String projectId, String env, String service) {
        int base = basePort(env, service);
        int size = SPAN;
        Optional<ServiceLease> existing = store.serviceLeases(projectId, env).stream()
                .filter(l -> l.service().equals(service)).findFirst();
        if (existing.isPresent()) {
            int port = existing.get().hostPort();
            if (port >= base && port < base + size && !taken(projectId, env, service, port)) {
                return port;
            }
        }
        Set<Integer> used = usedPorts(projectId, env, service);
        int preferred = Math.floorMod(projectId.hashCode(), size);
        for (int i = 0; i < size; i++) {
            int offset = (preferred + i) % size;
            int candidate = base + offset;
            if (used.contains(candidate) || !free(candidate)) {
                continue;
            }
            return candidate;
        }
        throw new IllegalStateException("端口分配失败：" + env + "/" + service
                + " 端口段 " + base + "–" + (base + size - 1) + " 已耗尽");
    }

    private Set<Integer> usedPorts(String projectId, String env, String service) {
        Set<Integer> used = new LinkedHashSet<>(RESERVED);
        used.add(platformPort);
        for (ServiceLease lease : store.allServiceLeases(env)) {
            boolean same = lease.projectId().equals(projectId) && lease.service().equals(service);
            if (!same) {
                used.add(lease.hostPort());
            }
        }
        return used;
    }

    private boolean taken(String projectId, String env, String service, int port) {
        if (RESERVED.contains(port) || port == platformPort) {
            return true;
        }
        boolean otherLease = store.allServiceLeases(env).stream()
                .anyMatch(l -> l.hostPort() == port
                        && !(l.projectId().equals(projectId) && l.service().equals(service)));
        if (otherLease) {
            return true;
        }
        // 自己的端口：只有平台进程确实在跑才算可用；否则可能是外部进程占着，应换端口
        boolean mine = store.serviceLeases(projectId, env).stream()
                .anyMatch(l -> l.service().equals(service) && l.hostPort() == port);
        if (free(port)) {
            return false;
        }
        return !(mine && "RUNNING".equals(runtime.status(projectId, env, service)));
    }

    private static boolean free(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int basePort(String env, String service) {
        boolean backend = ServiceLease.BACKEND.equals(service);
        return switch (env) {
            case "dev" -> backend ? 18080 : 15173;
            case "test" -> backend ? 28080 : 25173;
            default -> throw new IllegalArgumentException(
                    "只有 dev / test 使用本地端口段：" + env);
        };
    }

    private static void requireLocalEnv(String env) {
        if (!"dev".equals(env)) {
            throw new IllegalArgumentException(
                    "只有 dev 环境使用本机进程托管（test/prod 走容器部署）：" + env);
        }
    }
}
