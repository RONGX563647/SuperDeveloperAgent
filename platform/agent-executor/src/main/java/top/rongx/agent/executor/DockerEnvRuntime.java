package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvResource;
import top.rongx.agent.domain.env.EnvRuntime;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.StackAdapter;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;
import top.rongx.agent.domain.settings.SshAccess;

/**
 * docker 中间件运行时：本机直接执行 docker，远程走设置页的 SSH。
 *
 * <p>只启停 docker 来源的资源（LOCAL_DOCKER / REMOTE_DOCKER）；手工登记的资源
 * 明确拒绝，避免平台去操作不受管的外部实例。
 */
public class DockerEnvRuntime implements EnvRuntime {

    private static final Logger log = LoggerFactory.getLogger(DockerEnvRuntime.class);

    private static final long TIMEOUT_MS = 60_000;
    /** 启动后等待容器进入 running 的最长时间。 */
    private static final long START_WAIT_MS = 12_000;
    private static final long POLL_MS = 500;
    private static final Path CWD = Path.of(".");
    /** 容器名限制：远程命令要拼接，必须先做白名单校验。 */
    private static final String NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9_.-]{0,127}";

    private final EnvironmentStore store;
    private final DeployServerRepository servers;
    private final SshAccess ssh;
    private final ProcessExecutor executor = new ProcessExecutor();

    public DockerEnvRuntime(EnvironmentStore store, DeployServerRepository servers, SshAccess ssh) {
        this.store = store;
        this.servers = servers;
        this.ssh = ssh;
    }

    @Override
    public Result ensureRunning(String projectId, String env) {
        EffectiveEnvironment effective = store.effective(projectId, env);
        Map<String, String> values = effective.values();
        List<String> started = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (EnvResource r : effective.resources()) {
            if (!StackAdapter.isReferenced(r, values)) {
                continue;
            }
            List<String> missing = r.missingFields();
            if (!missing.isEmpty()) {
                failed.add(r.name() + "：缺少连接信息 " + String.join("、", missing));
                continue;
            }
            // 不信任缓存的 running：start() 会先用实时状态判断，已在运行则秒返回。
            Result one = start(r.id());
            if (one.ok()) {
                started.addAll(one.started());
            } else {
                failed.addAll(one.failed());
            }
        }
        if (!failed.isEmpty()) {
            return new Result(false, !started.isEmpty(), started, List.of(), failed,
                    "中间件启动失败：" + String.join("；", failed));
        }
        return new Result(true, !started.isEmpty(), started, List.of(), List.of(),
                started.isEmpty() ? "项目引用的中间件已在运行" : "已自动启动 " + String.join("、", started));
    }

    @Override
    public Result start(String resourceId) {
        EnvResource r = store.resource(resourceId).orElse(null);
        if (r == null) {
            return new Result(false, false, List.of(), List.of(), List.of("资源不存在：" + resourceId),
                    "资源不存在");
        }
        String name = containerName(r);
        String guard = dockerGuard(r, name);
        if (guard != null) {
            return fail(name, guard);
        }
        // 用实时状态而不是缓存的 running 字段：容器可能在检查之后被手动停掉。
        if (Boolean.TRUE.equals(liveRunning(r, name))) {
            // 容器活着就刷新登记：否则历史 STOPPED 会一直假死（env_summary / 设置页失真）
            store.recordHealth(r.id(), true, null);
            return Result.ok(List.of());
        }
        CmdResult res = docker(r, "docker start " + name, List.of("docker", "start", name));
        if (!res.ok()) {
            store.recordHealth(r.id(), false, "docker start 失败：" + res.output());
            return fail(name, "docker start 失败：" + res.output());
        }
        boolean running = waitRunning(r, name);
        store.recordHealth(r.id(), running, running ? null : "docker start 后容器仍未运行");
        if (!running) {
            return fail(name, "容器启动后未进入运行状态");
        }
        log.info("中间件已启动：env={} name={}", r.env(), name);
        return Result.ok(List.of(name));
    }

    @Override
    public Result stop(String resourceId) {
        EnvResource r = store.resource(resourceId).orElse(null);
        if (r == null) {
            return new Result(false, false, List.of(), List.of(), List.of("资源不存在：" + resourceId),
                    "资源不存在");
        }
        String name = containerName(r);
        String guard = dockerGuard(r, name);
        if (guard != null) {
            return fail(name, guard);
        }
        CmdResult res = docker(r, "docker stop " + name, List.of("docker", "stop", name));
        if (!res.ok()) {
            return fail(name, "docker stop 失败：" + res.output());
        }
        store.recordHealth(r.id(), false, null);
        log.info("中间件已停止：env={} name={}", r.env(), name);
        return new Result(true, true, List.of(), List.of(name), List.of(),
                "已停止 " + name);
    }

    // ---------------------------------------------------------------- 内部

    private String dockerGuard(EnvResource r, String name) {
        if (!"LOCAL_DOCKER".equals(r.source()) && !"REMOTE_DOCKER".equals(r.source())) {
            return "手工登记的资源不支持启停，请在对应服务器上操作";
        }
        if (!name.matches(NAME_PATTERN)) {
            return "容器名不合法：" + name;
        }
        if ("REMOTE_DOCKER".equals(r.source()) && serverOf(r) == null) {
            return "找不到远程服务器：" + r.serverId();
        }
        return null;
    }

    private CmdResult docker(EnvResource r, String remoteCommand, List<String> localArgv) {
        if (!"REMOTE_DOCKER".equals(r.source())) {
            StringBuilder sink = new StringBuilder();
            ProcessExecutor.StepOutcome outcome =
                    executor.exec(CWD, localArgv, TIMEOUT_MS, sink, ProcessExecutor.NEVER);
            return new CmdResult(outcome.exitCode() == 0, ProcessExecutor.tail(sink));
        }
        DeployServer server = serverOf(r);
        String secret = servers.secretOf(server.id()).orElse(null);
        SshAccess.Result result = ssh.run(server, secret, remoteCommand, TIMEOUT_MS);
        return new CmdResult(result.ok(), result.ok() ? result.output() : result.error());
    }

    private boolean waitRunning(EnvResource r, String name) {
        long deadline = System.currentTimeMillis() + START_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(liveRunning(r, name))) {
                return true;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return Boolean.TRUE.equals(liveRunning(r, name));
    }

    /** @return true/false 为容器运行状态；null 表示命令失败（容器不存在 / docker 不可用）。 */
    private Boolean liveRunning(EnvResource r, String name) {
        CmdResult res = docker(r, "docker inspect --format '{{.State.Running}}' " + name,
                List.of("docker", "inspect", "--format", "{{.State.Running}}", name));
        if (!res.ok()) {
            return null;
        }
        return "true".equalsIgnoreCase(res.output().trim());
    }

    private DeployServer serverOf(EnvResource r) {
        return (r.serverId() == null || r.serverId().isBlank()
                ? servers.findDefault() : servers.find(r.serverId())).orElse(null);
    }

    /** 发现服务把容器名放在 name；host 在映射端口时会变成 IP。 */
    private static String containerName(EnvResource r) {
        return r.name() == null || r.name().isBlank() ? r.host() : r.name();
    }

    private static Result fail(String name, String reason) {
        return new Result(false, false, List.of(), List.of(), List.of(name + "：" + reason),
                name + " 操作失败：" + reason);
    }

    private record CmdResult(boolean ok, String output) {
    }
}
