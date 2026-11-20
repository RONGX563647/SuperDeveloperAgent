package top.rongx.agent.gateway.web;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.PortLease;
import top.rongx.agent.domain.process.JavaRuntime;
import top.rongx.agent.domain.runtime.RuntimeInspector;
import top.rongx.agent.domain.runtime.RuntimeInspector.ContainerStatus;
import top.rongx.agent.domain.settings.DeployServer;
import top.rongx.agent.domain.settings.DeployServerRepository;
import top.rongx.agent.domain.settings.SshAccess;
import top.rongx.agent.gateway.bugfix.BugFixRedactor;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.DeployException;
import top.rongx.agent.domain.error.PlatformException;

/**
 * 工作区「环境」视图：按 版本 × 环境 汇总部署产物（Docker 容器 / HOST 进程）并支持一键启停。
 *
 * <p>数据不新建表，全部来自部署事实：
 * <ul>
 *   <li>HOST 默认槽：{@code <workspace>/_platform/deploy/<project>-<env>/} 下的
 *       {@code releases/<sha>}、{@code current}、{@code app.pid}、{@code app.log}；</li>
 *   <li>HOST 打 tag 的版本：同目录下 {@code versions/<版本号>/}（各自 pid / current / port）；</li>
 *   <li>DOCKER：{@link RuntimeInspector} 按 {@code agent.project} / {@code agent.env} /
 *       {@code agent.version} 标签巡检。</li>
 * </ul>
 *
 * <p>只管理应用本身（前后端镜像/进程），中间件资源不在这里启停。
 */
@Service
public class DeploymentControlService {

    private static final List<String> ENVS = List.of("dev", "test", "prod");
    private static final Map<String, String> ENV_LABELS =
            Map.of("dev", "开发", "test", "测试", "prod", "生产");
    private static final Pattern HOST_PORT =
            Pattern.compile("\\[host] started pid=\\d+ release=\\S+ port=(\\d+)");
    /** 应用自身启动日志里的真实监听端口（比流水线租约更可信）。 */
    private static final Pattern TOMCAT_PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private static final Pattern DOCKER_HOST_PORT = Pattern.compile("→[^:]*:(\\d+)");
    private static final Pattern PROCESS_PORT = Pattern.compile("--server\\.port=(\\d+)");
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final long HOST_START_PROBE_MS = 800;
    private static final long DOCKER_TIMEOUT_MS = 30_000;
    /** 停止旧进程后等待端口释放的时间（Windows 上 docker-proxy / socket 异步释放）。 */
    private static final long PORT_RELEASE_WAIT_MS = 3_000;
    private static final int MAX_LOG_BYTES = 2 * 1024 * 1024;

    private final RuntimeInspector inspector;
    private final EnvironmentStore envStore;
    private final Path deployRoot;
    /** 远程部署服务器（SERVER 目标）：矩阵/日志/启停都要能看见远端容器。 */
    private final DeployServerRepository servers;
    private final SshAccess ssh;

    public DeploymentControlService(RuntimeInspector inspector, EnvironmentStore envStore,
                                    DeployServerRepository servers, SshAccess ssh,
                                    @Value("${agent.workspace.root:./data/workspaces}")
                                    String workspaceRoot) {
        this.inspector = inspector;
        this.envStore = envStore;
        this.servers = servers;
        this.ssh = ssh;
        this.deployRoot = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("deploy");
    }

    // ------------------------------------------------------------------ 查询

    /** 版本 × 环境 矩阵；只包含真实存在过部署产物的版本。 */
    public synchronized Matrix matrix(String projectId) {
        Map<String, CellAcc> cells = new LinkedHashMap<>();
        boolean dockerAvailable = true;
        for (String env : ENVS) {
            for (HostSlot slot : hostSlots(projectId, env)) {
                CellAcc acc = cells.computeIfAbsent(env + "|" + slot.version(),
                        k -> new CellAcc(env, slot.version()));
                acc.mergeHost(slot);
            }
            List<ContainerStatus> containers;
            try {
                containers = inspector.containers(projectId, env);
            } catch (PlatformException | IllegalStateException e) {
                dockerAvailable = false;
                continue;
            }
            Map<String, List<ContainerStatus>> byVersion = new LinkedHashMap<>();
            for (ContainerStatus status : containers) {
                byVersion.computeIfAbsent(versionOf(status), k -> new ArrayList<>()).add(status);
            }
            for (Map.Entry<String, List<ContainerStatus>> entry : byVersion.entrySet()) {
                CellAcc acc = cells.computeIfAbsent(env + "|" + entry.getKey(),
                        k -> new CellAcc(env, entry.getKey()));
                acc.mergeDocker(entry.getValue());
            }
        }

        // 远程 SERVER 部署：容器在远端主机上，本地 docker inspect 看不到，
        // 必须逐个已启用服务器查询（否则生产环境在矩阵里永远是空的）。
        // 多台服务器并行探测：矩阵是同步接口，串行 SSH 会被一台慢机器拖慢整个工作区。
        List<String> remoteNotes = java.util.Collections.synchronizedList(new ArrayList<>());
        List<DeployServer> activeServers = enabledServers();
        List<RemoteContainer> remoteFound = new ArrayList<>();
        if (!activeServers.isEmpty()) {
            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                List<java.util.concurrent.CompletableFuture<List<RemoteContainer>>> futures =
                        activeServers.stream()
                                .map(server -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                                    try {
                                        return remoteContainers(server, projectId);
                                    } catch (RuntimeException e) {
                                        remoteNotes.add(server.name() + "：" + e.getMessage());
                                        return List.<RemoteContainer>of();
                                    }
                                }, pool))
                                .toList();
                futures.forEach(future -> remoteFound.addAll(future.join()));
            }
        }
        for (RemoteContainer rc : remoteFound) {
            if (!ENVS.contains(rc.env())) {
                continue;
            }
            CellAcc acc = cells.computeIfAbsent(rc.env() + "|" + rc.version(),
                    k -> new CellAcc(rc.env(), rc.version()));
            acc.mergeRemote(rc);
        }

        // 「当前」= 该环境最近部署的版本（HOST / Docker 都适用，不依赖单一 current 指针）
        Map<String, CellAcc> newest = new LinkedHashMap<>();
        for (CellAcc acc : cells.values()) {
            CellAcc prev = newest.get(acc.env);
            if (prev == null || acc.deployedAt > prev.deployedAt) {
                newest.put(acc.env, acc);
            }
        }
        newest.values().forEach(acc -> acc.current = true);

        List<Cell> list = cells.values().stream()
                .map(CellAcc::toCell)
                .sorted(Comparator.comparingLong(Cell::deployedAt).reversed()
                        .thenComparing(Cell::env))
                .toList();
        List<String> versions = new ArrayList<>(list.stream().map(Cell::version)
                .distinct().toList());
        List<EnvColumn> columns = ENVS.stream()
                .map(env -> new EnvColumn(env, ENV_LABELS.getOrDefault(env, env)))
                .toList();
        String note = dockerAvailable ? ""
                : "本机 Docker 不可用：本机容器未展示（远程服务器照常展示）";
        if (!remoteNotes.isEmpty()) {
            note = (note.isEmpty() ? "" : note + "；") + "远程服务器未刷新："
                    + String.join("；", remoteNotes);
        }
        return new Matrix(columns, versions, list, dockerAvailable, note);
    }

    // ------------------------------------------------------------------ 启停

    /** 启动某环境指定版本（不传 version 时取默认槽的当前版本）。 */
    public synchronized ActionResult start(String projectId, String env, String version) {
        String targetEnv = requireEnv(env);
        String wanted = normalizeVersion(version);

        // 1) Docker：镜像/容器已经存在，直接 docker start，不重建
        List<ContainerStatus> dockerTargets = dockerContainers(projectId, targetEnv, wanted);
        if (!dockerTargets.isEmpty()) {
            List<String> started = new ArrayList<>();
            for (ContainerStatus status : dockerTargets) {
                if (!status.running()) {
                    docker("start", status.name());
                    started.add(status.name());
                }
            }
            return new ActionResult(true, started.isEmpty()
                    ? "容器已在运行" : "已启动容器：" + String.join("、", started),
                    stateOf(projectId, targetEnv, wanted));
        }

        // 1b) 远程 SERVER：容器在远端主机，本地 docker 看不到
        List<RemoteContainer> remoteTargets = remoteTargets(projectId, targetEnv, wanted);
        if (!remoteTargets.isEmpty()) {
            List<String> started = new ArrayList<>();
            for (RemoteContainer rc : remoteTargets) {
                if (!rc.running()) {
                    SshAccess.Result result = sshDocker(rc.server(), "start " + rc.name());
                    if (!result.ok()) {
                        throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT,
                                "远端启动失败：" + shortError(result.error()));
                    }
                    started.add(rc.server().name() + ":" + rc.name());
                }
            }
            return new ActionResult(true, started.isEmpty()
                    ? "容器已在运行" : "已启动容器：" + String.join("、", started),
                    stateOf(projectId, targetEnv, wanted));
        }

        // 2) HOST：用已落盘的 release jar 重新拉起
        List<HostSlot> slots = hostSlots(projectId, targetEnv);
        HostSlot host = wanted == null
                ? slots.stream().filter(slot -> !slot.tagged()).findFirst().orElse(null)
                : slots.stream().filter(slot -> wanted.equals(slot.version()))
                        .findFirst().orElse(null);
        String sha;
        Path stateDir;
        if (host != null) {
            sha = host.current();
            if (sha == null && !host.releases().isEmpty()) {
                sha = host.releases().get(0);
            }
            stateDir = host.dir();
        } else if (wanted != null) {
            // 兼容旧矩阵行：版本值其实是 releases 里的提交 sha
            HostSlot base = slots.stream().filter(slot -> !slot.tagged())
                    .findFirst().orElse(null);
            if (base == null || !base.releases().contains(wanted)) {
                throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "该环境没有版本 " + wanted + " 的部署产物");
            }
            sha = wanted;
            stateDir = base.dir();
        } else {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "该环境还没有部署产物，请先在流水线完成一次部署");
        }
        if (sha == null || sha.isBlank()) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "该环境还没有部署产物，请先在流水线完成一次部署");
        }
        Path jar = stateDir.resolve("releases").resolve(sha).resolve("app.jar");
        if (!Files.isRegularFile(jar)) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "版本 " + sha + " 没有可运行的 app.jar（Docker 部署无法用 HOST 启动）");
        }
        if (host != null && host.running()) {
            return new ActionResult(true, "该版本已在运行", "RUNNING");
        }
        stopHostProcess(stateDir);

        EffectiveEnvironment effective = envStore.effective(projectId, targetEnv);
        if (!effective.missingSecrets().isEmpty()) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "环境密钥缺失：" + String.join("、",
                    effective.missingSecrets()));
        }
        int preferredPort = preferredPort(projectId, targetEnv, host, effective);
        PortChoice portChoice = choosePort(preferredPort);
        int port = portChoice.port();
        Path pidFile = stateDir.resolve("app.pid");
        Path logFile = stateDir.resolve("app.log");
        List<String> argv = List.of(JavaRuntime.javaBinary(), "-jar", jar.toString(),
                "--server.port=" + port);
        ProcessBuilder pb = new ProcessBuilder(argv)
                .directory(stateDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        pb.environment().put("CI", "true");
        effective.values().forEach((key, value) ->
                pb.environment().put(key, value == null ? "" : value));
        Path webDist = jar.getParent().resolve("web");
        if (Files.isDirectory(webDist)) {
            pb.environment().put("AGENT_WEB_DIST", webDist.toAbsolutePath().toString());
        }
        try {
            Process process = pb.start();
            Files.writeString(pidFile, String.valueOf(process.pid()), StandardCharsets.UTF_8);
            boolean exited = process.waitFor(HOST_START_PROBE_MS, TimeUnit.MILLISECONDS);
            if (exited && process.exitValue() != 0) {
                Files.deleteIfExists(pidFile);
                throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "进程启动即退出（exit " + process.exitValue()
                        + "）：" + tail(logFile, 8));
            }
            writePortFile(stateDir, port);
            rotatePointers(stateDir, sha);
            String portNote = portChoice.fallback()
                    ? "（原端口 " + preferredPort + " 被占用，已改用 " + port + "）" : "";
            return new ActionResult(true,
                    "已启动 HOST 进程 pid=" + process.pid() + "，端口 " + port + portNote,
                    "RUNNING");
        } catch (IOException e) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "无法启动进程：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "启动被中断");
        }
    }

    /** 停止某环境指定版本（不传 version 时停止该环境所有运行中的应用容器/进程）。 */
    public synchronized ActionResult stop(String projectId, String env, String version) {
        String targetEnv = requireEnv(env);
        String wanted = normalizeVersion(version);

        List<ContainerStatus> dockerTargets = dockerContainers(projectId, targetEnv, wanted);
        List<String> stopped = new ArrayList<>();
        for (ContainerStatus status : dockerTargets) {
            if (status.running()) {
                docker("stop", status.name());
                stopped.add(status.name());
            }
        }
        List<String> remoteStopped = new ArrayList<>();
        for (RemoteContainer rc : remoteTargets(projectId, targetEnv, wanted)) {
            if (!rc.running()) {
                continue;
            }
            SshAccess.Result result = sshDocker(rc.server(), "stop " + rc.name());
            if (result.ok()) {
                remoteStopped.add(rc.server().name() + ":" + rc.name());
            }
        }
        boolean hostStopped = false;
        for (HostSlot slot : hostSlots(projectId, targetEnv)) {
            boolean match = wanted == null
                    || wanted.equals(slot.version())
                    || wanted.equals(slot.current())
                    || slot.releases().contains(wanted);
            if (match && slot.running()) {
                stopHostProcess(slot.dir());
                hostStopped = true;
            }
        }
        if (stopped.isEmpty() && remoteStopped.isEmpty() && !hostStopped) {
            return new ActionResult(true, "该环境没有正在运行的应用", "STOPPED");
        }
        List<String> parts = new ArrayList<>();
        if (!stopped.isEmpty()) {
            parts.add("容器 " + String.join("、", stopped));
        }
        if (!remoteStopped.isEmpty()) {
            parts.add("远程容器 " + String.join("、", remoteStopped));
        }
        if (hostStopped) {
            parts.add("HOST 进程");
        }
        return new ActionResult(true, "已停止：" + String.join("，", parts), "STOPPED");
    }

    /** 重启：先停后起，HOST 沿用原端口（只有被占用才漂移到下一个空闲端口）；容器端口映射不变。 */
    public synchronized ActionResult restart(String projectId, String env, String version) {
        String targetEnv = requireEnv(env);
        String wanted = normalizeVersion(version);
        stop(projectId, targetEnv, wanted);
        ActionResult started = start(projectId, targetEnv, wanted);
        String detail = started.message().startsWith("已启动")
                ? started.message().substring("已启动".length()) : started.message();
        return new ActionResult(true, "已重启：" + detail, started.state());
    }

    // ------------------------------------------------------------------ 运行日志

    /**
     * 读取某环境某版本的运行日志：
     * Docker 容器用 {@code docker logs}，HOST 进程读部署状态目录的 {@code app.log}。
     *
     * <p>只允许读取属于该 (项目, 环境) 的容器，避免借接口读任意容器日志。
     */
    public synchronized DeploymentLogs logs(String projectId, String env, String version,
                                            String container, int tail, int sinceMinutes) {
        String targetEnv = requireEnv(env);
        String wanted = normalizeVersion(version);
        int limit = Math.max(1, Math.min(tail <= 0 ? 300 : tail, 2_000));
        int since = Math.max(1, Math.min(sinceMinutes <= 0 ? 1_440 : sinceMinutes, 43_200));

        List<ContainerStatus> containers = List.of();
        try {
            containers = inspector.containers(projectId, targetEnv);
        } catch (PlatformException | IllegalStateException ignored) {
            // Docker 不可用：继续尝试 HOST 日志
        }
        List<ContainerStatus> matched = wanted == null ? containers : containers.stream()
                .filter(status -> wanted.equals(versionOf(status)))
                .toList();
        if (!matched.isEmpty()) {
            ContainerStatus target = pickContainer(matched, container);
            List<String> lines;
            try {
                lines = inspector.logs(target.name(), since);
            } catch (PlatformException | IllegalStateException e) {
                throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, e.getMessage());
            }
            return new DeploymentLogs("DOCKER", target.name(),
                    matched.stream().map(ContainerStatus::name).toList(),
                    redactTail(lines, limit),
                    "docker logs " + target.name() + "（最近 " + since + " 分钟，最多 " + limit + " 行）");
        }

        // 远程 SERVER：本地没有该容器时，改为 ssh 到远端 docker logs
        List<RemoteContainer> remoteMatched = remoteTargets(projectId, targetEnv, wanted);
        if (!remoteMatched.isEmpty()) {
            RemoteContainer target = pickRemote(remoteMatched, container);
            SshAccess.Result result = sshDocker(target.server(),
                    "logs --since " + since + "m --tail " + limit + " " + target.name());
            if (!result.ok()) {
                throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT,
                        "读取远端容器日志失败：" + shortError(result.error()));
            }
            List<String> lines = Stream.of(String.valueOf(result.output()).split("\\R", -1)).toList();
            return new DeploymentLogs("SERVER", target.name(),
                    remoteMatched.stream().map(RemoteContainer::name).toList(),
                    redactTail(lines, limit),
                    "ssh " + target.server().name() + " docker logs " + target.name()
                            + "（最近 " + since + " 分钟，最多 " + limit + " 行）");
        }

        HostSlot slot = findHostSlot(projectId, targetEnv, wanted);
        if (slot == null) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "该环境没有可读取日志的部署产物");
        }
        List<String> lines = readLogTail(slot.dir().resolve("app.log"), limit);
        return new DeploymentLogs("HOST", null, List.of(), redactTail(lines, limit),
                "HOST 进程日志 app.log（最多 " + limit + " 行）");
    }

    private static ContainerStatus pickContainer(List<ContainerStatus> matched, String container) {
        if (container != null && !container.isBlank()) {
            String name = container.trim();
            return matched.stream()
                    .filter(status -> name.equals(status.name()))
                    .findFirst()
                    .orElseThrow(() -> new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "容器 " + name + " 不属于该环境/版本"));
        }
        return matched.stream().filter(ContainerStatus::running).findFirst()
                .orElse(matched.get(0));
    }

    private HostSlot findHostSlot(String projectId, String env, String version) {
        List<HostSlot> slots = hostSlots(projectId, env);
        if (version == null) {
            return slots.stream().filter(slot -> !slot.tagged()).findFirst()
                    .orElse(slots.isEmpty() ? null : slots.get(0));
        }
        return slots.stream()
                .filter(slot -> version.equals(slot.version())
                        || version.equals(slot.current())
                        || slot.releases().contains(version))
                .findFirst().orElse(null);
    }

    /** 脱敏 + 截断，避免环境变量里的密钥随日志返回给前端。 */
    private static List<String> redactTail(List<String> lines, int limit) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, lines.size() - limit);
        String safe = BugFixRedactor.redact(String.join("\n", lines.subList(from, lines.size())));
        List<String> result = new ArrayList<>();
        for (String line : safe.split("\\R")) {
            if (!line.isBlank()) {
                result.add(line);
            }
        }
        return result;
    }

    /** 只读文件尾部（最多 2MB），避免大日志把网关内存打满。 */
    private static List<String> readLogTail(Path file, int limit) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            long length = Files.size(file);
            long from = Math.max(0, length - MAX_LOG_BYTES);
            byte[] bytes;
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(from);
                bytes = new byte[(int) (length - from)];
                raf.readFully(bytes);
            }
            String[] all = new String(bytes, StandardCharsets.UTF_8).split("\\R");
            List<String> lines = new ArrayList<>();
            for (int i = Math.max(0, all.length - limit); i < all.length; i++) {
                if (!all[i].isBlank()) {
                    lines.add(all[i]);
                }
            }
            return lines;
        } catch (IOException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ HOST 状态

    /**
     * 一个环境下的所有 HOST 部署槽：默认槽（不打 tag，覆盖式）+ {@code versions/<版本号>}。
     */
    private List<HostSlot> hostSlots(String projectId, String env) {
        Path base = hostDir(projectId, env);
        // 默认槽的端口来自流水线写入的端口租约（打 tag 的版本槽有自己的 port 文件）
        int leasePort = latestLeasePort(projectId, env);
        List<HostSlot> slots = new ArrayList<>();
        HostSlot baseSlot = hostSlot(base, false, leasePort);
        if (baseSlot != null) {
            slots.add(baseSlot);
        }
        Path versions = base.resolve("versions");
        if (Files.isDirectory(versions)) {
            try (Stream<Path> stream = Files.list(versions)) {
                for (Path slotDir : stream.filter(Files::isDirectory).toList()) {
                    HostSlot slot = hostSlot(slotDir, true, 0);
                    if (slot != null) {
                        slots.add(slot);
                    }
                }
            } catch (IOException ignored) {
                // 目录读不了按没有部署处理
            }
        }
        return slots;
    }

    /** 读取一个版本槽；目录不存在或没有任何部署痕迹时返回 null。 */
    private HostSlot hostSlot(Path dir, boolean tagged, int fallbackPort) {
        Path releasesDir = dir.resolve("releases");
        List<String> releases = new ArrayList<>();
        long newestAt = 0;
        String newestRelease = null;
        if (Files.isDirectory(releasesDir)) {
            try (Stream<Path> stream = Files.list(releasesDir)) {
                for (Path release : stream.filter(Files::isDirectory).toList()) {
                    releases.add(release.getFileName().toString());
                    try {
                        long at = Files.getLastModifiedTime(release).toMillis();
                        if (at >= newestAt) {
                            newestAt = at;
                            newestRelease = release.getFileName().toString();
                        }
                    } catch (IOException ignored) {
                        // 时间读不到不影响版本展示
                    }
                }
            } catch (IOException ignored) {
                // 目录读不了按没有部署处理
            }
        }
        String current = readText(dir.resolve("current"));
        long pid = readPid(dir.resolve("app.pid"));
        boolean running = isHostProcess(pid);
        int port = readPort(dir.resolve("port"));
        if (port <= 0) {
            // 兼容旧部署（没有 port 文件）：从 app.log 里取应用实际监听的端口
            port = parseLogPort(readText(dir.resolve("app.log")));
        }
        if (port <= 0 && running) {
            // 再兜底：直接从运行中的进程命令行取 --server.port
            port = hostProcessPort(pid);
        }
        if (port <= 0) {
            // 旧部署既没有 port 文件也没有可读的命令行：用流水线的端口租约
            port = fallbackPort;
        }
        if (releases.isEmpty() && current == null && pid <= 0) {
            return null; // 只有 Docker 端口记录之类的空壳目录
        }
        String version = readText(dir.resolve("version"));
        if (version == null) {
            version = tagged ? dir.getFileName().toString()
                    : current != null ? current : newestRelease;
        }
        if (version == null || version.isBlank()) {
            version = "latest";
        }
        return new HostSlot(version, dir, tagged, releases, current, pid, running, port, newestAt);
    }

    /** 从运行中的 HOST 进程命令行取 {@code --server.port}，给没有 port 文件的旧部署兜底。 */
    private static int hostProcessPort(long pid) {
        if (pid <= 0) {
            return 0;
        }
        return ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                .map(handle -> handle.info().commandLine().orElse(""))
                .map(commandLine -> {
                    Matcher matcher = PROCESS_PORT.matcher(commandLine);
                    return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
                })
                .orElse(0);
    }

    private boolean isHostProcess(long pid) {
        if (pid <= 0) {
            return false;
        }
        return ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                .map(handle -> {
                    String commandLine = handle.info().commandLine().orElse("");
                    return commandLine.isEmpty() || commandLine.contains("app.jar");
                })
                .orElse(false);
    }

    private void stopHostProcess(Path stateDir) {
        Path pidFile = stateDir.resolve("app.pid");
        long pid = readPid(pidFile);
        if (pid <= 0) {
            deleteQuietly(pidFile);
            return;
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
        if (handle.isEmpty()) {
            deleteQuietly(pidFile);
            return;
        }
        String commandLine = handle.get().info().commandLine().orElse("");
        if (!commandLine.isEmpty() && !commandLine.contains("app.jar")) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "pid=" + pid
                    + " 不是平台部署的 app.jar，拒绝停止（可能是 pid 被复用）");
        }
        handle.get().descendants().forEach(ProcessHandle::destroy);
        handle.get().destroy();
        try {
            handle.get().onExit().get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            handle.get().destroyForcibly();
        }
        deleteQuietly(pidFile);
    }

    private void rotatePointers(Path stateDir, String sha) {
        String current = readText(stateDir.resolve("current"));
        try {
            if (current != null && !current.equals(sha)) {
                Files.writeString(stateDir.resolve("previous"), current, StandardCharsets.UTF_8);
            }
            Files.writeString(stateDir.resolve("current"), sha, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 指针写失败不影响本次启动，下次部署会再次轮转
        }
    }

    // ------------------------------------------------------------------ 端口

    /** 「原来的端口」优先：上次启动记录 → 端口租约 → 环境默认端口。 */
    private int preferredPort(String projectId, String env, HostSlot host,
                              EffectiveEnvironment effective) {
        if (host != null && host.port() > 0) {
            return host.port();
        }
        int leased = latestLeasePort(projectId, env);
        if (leased > 0) {
            return leased;
        }
        return effective.defaultPort() > 0 ? effective.defaultPort() : 8080;
    }

    /** 最近一次流水线部署记录的宿端口租约（默认槽使用）。 */
    private int latestLeasePort(String projectId, String env) {
        try {
            return envStore.leases(projectId, env).stream()
                    .filter(lease -> lease.hostPort() > 0)
                    .max(Comparator.comparingLong(PortLease::updatedAt))
                    .map(PortLease::hostPort)
                    .orElse(0);
        } catch (RuntimeException ignored) {
            // 租约读取失败时回落到环境默认端口
            return 0;
        }
    }

    /** 原端口空闲就沿用（流水线部署时分配的端口）；被占用时向后找空闲端口并说明。 */
    private static PortChoice choosePort(int preferred) {
        int want = preferred > 0 ? preferred : 8080;
        if (awaitPortFree(want, PORT_RELEASE_WAIT_MS)) {
            return new PortChoice(want, false);
        }
        int candidate = Math.max(want + 1, 1);
        for (int i = 0; i < 200; i++, candidate++) {
            if (portFree(candidate)) {
                return new PortChoice(candidate, true);
            }
        }
        throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "端口分配失败：原端口 " + want
                + " 被占用，且其后 200 个端口都不可用");
    }

    private static boolean awaitPortFree(int port, long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        while (true) {
            if (portFree(port)) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return portFree(port);
            }
        }
    }

    private static boolean portFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writePortFile(Path stateDir, int port) {
        try {
            Files.writeString(stateDir.resolve("port"), String.valueOf(port),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 端口记录失败不影响启动，下次会回落到 app.log
        }
    }

    private static int readPort(Path file) {
        String value = readText(file);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ DOCKER 辅助

    private List<ContainerStatus> dockerContainers(String projectId, String env, String version) {
        List<ContainerStatus> containers;
        try {
            containers = inspector.containers(projectId, env);
        } catch (PlatformException | IllegalStateException e) {
            if (version == null && !hasHostRelease(projectId, env)) {
                throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "Docker 不可用：" + e.getMessage());
            }
            return List.of();
        }
        if (version == null) {
            return containers;
        }
        return containers.stream()
                .filter(c -> version.equals(versionOf(c))
                        || version.equals(c.sha() == null || c.sha().isBlank()
                                ? "latest" : c.sha().trim()))
                .toList();
    }

    /** 容器展示版本：优先 agent.version 标签，旧容器回落到提交 sha。 */
    private static String versionOf(ContainerStatus status) {
        if (status.version() != null && !status.version().isBlank()) {
            return status.version().trim();
        }
        return status.sha() == null || status.sha().isBlank() ? "latest" : status.sha().trim();
    }

    private boolean hasHostRelease(String projectId, String env) {
        return hostSlots(projectId, env).stream().anyMatch(slot -> !slot.releases().isEmpty());
    }

    private String docker(String action, String container) {
        List<String> argv = List.of("docker", action, container);
        try {
            Process process = new ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(DOCKER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new DeployException(ErrorCode.DEPLOY_INVALID_STATE, "docker " + action + " 超时：" + container);
            }
            String text = new String(output, StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new DeployException(ErrorCode.DEPLOY_INVALID_STATE, "docker " + action + " 失败：" + text);
            }
            return text;
        } catch (IOException e) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_STATE, "Docker 不可用：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeployException(ErrorCode.DEPLOY_INVALID_STATE, "docker " + action + " 被中断");
        }
    }

    private String stateOf(String projectId, String env, String version) {
        return matrix(projectId).cells().stream()
                .filter(cell -> cell.env().equals(env)
                        && (version == null || cell.version().equals(version)))
                .map(Cell::state)
                .findFirst()
                .orElse("UNKNOWN");
    }

    // ------------------------------------------------------------------ 通用

    private Path hostDir(String projectId, String env) {
        // 与 HostDeployer 的命名保持一致：<deployRoot>/<projectId>-<env>（不再额外小写化）
        if (projectId == null || !SAFE_VERSION.matcher(projectId.trim()).matches()) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "非法项目标识：" + projectId);
        }
        return deployRoot.resolve(projectId.trim() + "-" + env);
    }

    private static String requireEnv(String env) {
        String value = env == null ? "" : env.trim().toLowerCase(Locale.ROOT);
        if (!ENVS.contains(value)) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "未知环境：" + env + "（可选 dev / test / prod）");
        }
        return value;
    }

    private static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String value = version.trim();
        if (!SAFE_VERSION.matcher(value).matches()) {
            throw new DeployException(ErrorCode.DEPLOY_INVALID_INPUT, "非法版本标识：" + version);
        }
        return value;
    }

    private static String readText(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            return null;
        }
    }

    private static long readPid(Path file) {
        String value = readText(file);
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 日志里的监听端口：优先应用自身「Tomcat started on port N」，再回落到部署器标记。 */
    private static int parseLogPort(String log) {
        if (log == null) {
            return 0;
        }
        Matcher matcher = TOMCAT_PORT.matcher(log);
        int port = 0;
        while (matcher.find()) {
            port = Integer.parseInt(matcher.group(1));
        }
        return port > 0 ? port : parseHostPort(log);
    }

    private static int parseHostPort(String log) {
        if (log == null) {
            return 0;
        }
        Matcher matcher = HOST_PORT.matcher(log);
        int port = 0;
        while (matcher.find()) {
            port = Integer.parseInt(matcher.group(1));
        }
        return port;
    }

    private static String tail(Path logFile, int lines) {
        try {
            String text = Files.readString(logFile, StandardCharsets.UTF_8);
            String[] all = text.split("\\R");
            int from = Math.max(0, all.length - lines);
            return String.join("\n", java.util.Arrays.copyOfRange(all, from, all.length));
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 删不掉不影响后续操作
        }
    }

    // ------------------------------------------------------------------ DTO

    // ------------------------------------------------------------ 远程 SERVER 容器

    /** 远端 ps 查询超时：矩阵/日志/启停都不该被一台不可达的服务器拖死。 */
    private static final long REMOTE_SSH_TIMEOUT_MS = 8_000;
    private static final String REMOTE_PS_FORMAT = "{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}"
            + "|{{.Label \"agent.env\"}}|{{.Label \"agent.version\"}}|{{.Label \"agent.sha\"}}"
            + "|{{.CreatedAt}}";
    private static final Pattern REMOTE_HOST_PORT = Pattern.compile(":(\\d+)->");

    private List<DeployServer> enabledServers() {
        if (servers == null) {
            return List.of();
        }
        try {
            return servers.findAll().stream().filter(DeployServer::enabled).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 远端容器清单：容器名以 agent-deploy-&lt;project&gt; 开头（含旧版本无标签的容器）。 */
    private List<RemoteContainer> remoteContainers(DeployServer server, String projectId) {
        String prefix = "agent-deploy-" + projectId;
        String command = "ps -a --filter name=" + prefix + " --format '" + REMOTE_PS_FORMAT + "'";
        SshAccess.Result result = sshDocker(server, command);
        if (!result.ok()) {
            throw new IllegalStateException(shortError(result.error()));
        }
        List<RemoteContainer> found = new ArrayList<>();
        String output = result.output() == null ? "" : result.output();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\\|", -1);
            if (f.length < 8) {
                continue;
            }
            String name = f[0].trim();
            // 边界校验：agent-deploy-proj 不能误匹配 agent-deploy-proj2
            if (!name.equals(prefix) && !name.startsWith(prefix + "-")) {
                continue;
            }
            String rest = name.substring(prefix.length());
            String env = f[4].trim();
            if (env.isEmpty()) {
                env = envFromName(rest);
            }
            if (env.isEmpty()) {
                continue;
            }
            String sha = f[6].trim();
            String version = f[5].trim();
            if (version.isEmpty()) {
                version = versionFromName(rest, env, sha);
            }
            if (version.isEmpty()) {
                version = "latest";
            }
            String status = f[2].trim();
            found.add(new RemoteContainer(server, name, f[1].trim(), env, version, sha,
                    status, f[3].trim(), status.startsWith("Up"), parseCreatedAt(f[7].trim())));
        }
        return found;
    }

    private List<RemoteContainer> remoteTargets(String projectId, String env, String wanted) {
        List<RemoteContainer> targets = new ArrayList<>();
        for (DeployServer server : enabledServers()) {
            try {
                for (RemoteContainer rc : remoteContainers(server, projectId)) {
                    if (!env.equals(rc.env())) {
                        continue;
                    }
                    if (wanted != null && !wanted.equals(rc.version())) {
                        continue;
                    }
                    targets.add(rc);
                }
            } catch (RuntimeException ignored) {
                // 单台服务器不可达不影响其它服务器与本地结果
            }
        }
        return targets;
    }

    private static RemoteContainer pickRemote(List<RemoteContainer> containers, String container) {
        if (container != null && !container.isBlank()) {
            for (RemoteContainer rc : containers) {
                if (container.equals(rc.name())) {
                    return rc;
                }
            }
        }
        return containers.stream().filter(RemoteContainer::running)
                .findFirst().orElse(containers.get(0));
    }

    /**
     * 执行远端 docker 命令；普通用户无权限时自动尝试免密 sudo。
     *
     * <p>生产机上把 SSH 用户加入 docker 组并非总是可行，这里与 ServerDeployer 保持一致。
     */
    private SshAccess.Result sshDocker(DeployServer server, String args) {
        String secret = servers == null ? null : servers.secretOf(server.id()).orElse(null);
        SshAccess.Result plain = ssh.run(server, secret, "docker " + args, REMOTE_SSH_TIMEOUT_MS);
        if (plain.ok()) {
            return plain;
        }
        String error = String.valueOf(plain.error()).toLowerCase(Locale.ROOT);
        if (error.contains("permission denied") || error.contains("access denied")) {
            return ssh.run(server, secret, "sudo -n docker " + args, REMOTE_SSH_TIMEOUT_MS);
        }
        return plain;
    }

    private static String envFromName(String rest) {
        for (String env : ENVS) {
            if (rest.equals("-" + env) || rest.startsWith("-" + env + "-")) {
                return env;
            }
        }
        return "";
    }

    private static String versionFromName(String rest, String env, String sha) {
        String marker = "-" + env + "-";
        int index = rest.indexOf(marker);
        if (index >= 0 && index + marker.length() < rest.length()) {
            return rest.substring(index + marker.length());
        }
        if (sha == null || sha.isBlank()) {
            return "";
        }
        return sha.length() > 12 ? sha.substring(0, 12) : sha;
    }

    /** docker ps 的 CreatedAt：{@code 2026-11-20 10:00:00 +0800 CST}（带时区名）。 */
    private static long parseCreatedAt(String value) {
        if (value == null || value.length() < 19) {
            return 0;
        }
        try {
            // 先按远端偏移解析（服务器时区可能与本机不同），失败再退回本机时区
            if (value.length() >= 25) {
                return java.time.OffsetDateTime.parse(value.substring(0, 25),
                                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z"))
                        .toInstant().toEpochMilli();
            }
            return LocalDateTime.parse(value.substring(0, 19),
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String shortError(String error) {
        String text = error == null ? "" : error.trim();
        return text.length() > 180 ? text.substring(0, 180) + "…" : text;
    }

    private record RemoteContainer(DeployServer server, String name, String image, String env,
                                   String version, String sha, String status, String ports,
                                   boolean running, long createdAt) {
    }

    public record EnvColumn(String key, String label) {
    }

    public record Cell(String env, String version, String state, String target,
                       boolean running, boolean current, List<String> containers,
                       String image, String health, String ports, String url,
                       long deployedAt, String detail, String release, long pid,
                       String server) {
    }

    public record Matrix(List<EnvColumn> environments, List<String> versions,
                         List<Cell> cells, boolean dockerAvailable, String note) {
    }

    public record ActionResult(boolean ok, String message, String state) {
    }

    /** 运行日志：source=DOCKER 时 container 为当前容器，containers 为可选容器列表。 */
    public record DeploymentLogs(String source, String container, List<String> containers,
                                 List<String> lines, String note) {
    }

    private record PortChoice(int port, boolean fallback) {
    }

    private record HostSlot(String version, Path dir, boolean tagged, List<String> releases,
                            String current, long pid, boolean running, int port, long newestAt) {
    }

    /** 同一 (env, version) 可能同时存在 HOST 产物与 Docker 容器，这里合并成一张卡片。 */
    private static final class CellAcc {
        private final String env;
        private final String version;
        private final LinkedHashSet<String> containers = new LinkedHashSet<>();
        private final List<String> details = new ArrayList<>();
        private boolean running;
        private boolean current;
        private String target;
        private String image;
        private String health;
        private String ports;
        private String url;
        private String release;
        private String server;
        private long pid;
        private long deployedAt;

        private CellAcc(String env, String version) {
            this.env = env;
            this.version = version;
        }

        private void mergeHost(HostSlot slot) {
            target = target == null ? "HOST" : target;
            deployedAt = Math.max(deployedAt, slot.newestAt());
            if (slot.running()) {
                running = true;
                target = "HOST";
                if (slot.pid() > 0) {
                    pid = slot.pid();
                }
            }
            if (release == null && slot.current() != null) {
                release = slot.current();
            }
            if (slot.port() > 0 && ports == null) {
                ports = "127.0.0.1:" + slot.port();
                url = "http://127.0.0.1:" + slot.port();
            }
            details.add(slot.tagged() ? "打 tag 版本" : "默认部署");
        }

        private void mergeDocker(List<ContainerStatus> statuses) {
            boolean dockerRunning = statuses.stream().anyMatch(ContainerStatus::running);
            if (target == null || dockerRunning || !running) {
                target = "DOCKER";
            }
            running = running || dockerRunning;
            statuses.forEach(status -> containers.add(status.name()));
            statuses.stream().map(ContainerStatus::image).filter(v -> v != null && !v.isBlank())
                    .findFirst().ifPresent(v -> image = v);
            statuses.stream().map(ContainerStatus::health)
                    .filter(v -> v != null && !v.isBlank() && !"none".equals(v))
                    .findFirst().ifPresent(v -> health = v);
            if (ports == null) {
                statuses.stream().map(ContainerStatus::ports)
                        .filter(v -> v != null && !v.isBlank() && !"无端口映射".equals(v))
                        .findFirst().ifPresent(v -> {
                            ports = v;
                            Matcher matcher = DOCKER_HOST_PORT.matcher(v);
                            if (matcher.find()) {
                                url = "http://127.0.0.1:" + matcher.group(1);
                            }
                        });
            }
            details.add(statuses.size() + " 个容器");
        }

        /** 合并远端 SERVER 容器：同一 (env, version) 与本地卡片合并展示。 */
        private void mergeRemote(RemoteContainer rc) {
            if (target == null || rc.running() || !running) {
                target = "DOCKER";
            }
            running = running || rc.running();
            containers.add(rc.name());
            if ((image == null || image.isBlank()) && rc.image() != null && !rc.image().isBlank()) {
                image = rc.image();
            }
            if (ports == null && rc.ports() != null && !rc.ports().isBlank()) {
                ports = rc.ports();
                Matcher matcher = REMOTE_HOST_PORT.matcher(rc.ports());
                if (matcher.find() && rc.server().host() != null) {
                    url = "http://" + rc.server().host() + ":" + matcher.group(1);
                }
            }
            if (release == null && rc.sha() != null && !rc.sha().isBlank()) {
                release = rc.sha();
            }
            deployedAt = Math.max(deployedAt, rc.createdAt());
            String name = rc.server().name();
            server = server == null ? name : (server.contains(name) ? server : server + "、" + name);
            details.add("服务器 " + name);
        }

        private Cell toCell() {
            String state = running ? "RUNNING" : "STOPPED";
            return new Cell(env, version, state, target == null ? "HOST" : target,
                    running, current, List.copyOf(containers), image, health, ports, url,
                    deployedAt, String.join("；", new LinkedHashSet<>(details)), release, pid,
                    server);
        }
    }
}
