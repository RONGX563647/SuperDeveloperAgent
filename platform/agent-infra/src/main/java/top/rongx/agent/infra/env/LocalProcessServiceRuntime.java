package top.rongx.agent.infra.env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.ServiceLease;
import top.rongx.agent.domain.env.ServiceRuntime;
import top.rongx.agent.domain.port.ProjectRepoLocator;

/**
 * 本机进程服务运行时（dev 专用）：平台负责把项目前后端拉起来 / 停掉 / 探活。
 *
 * <p>命令固定为两种，不接受任意拼接：
 * <ul>
 *   <li>backend：{@code java -jar target/*.jar --server.port=<port>}（没有 jar 时退化为
 *       {@code mvnw spring-boot:run}）；</li>
 *   <li>frontend：{@code npm run dev -- --port <port> --strictPort}。</li>
 * </ul>
 *
 * <p>环境变量来自「设置 → 环境」的有效环境（与 docker 部署同一份数据），直接注入进程；
 * 前端另写一段受管的 {@code .env.local}（只含非敏感的 API 地址）。
 */
@Component
public class LocalProcessServiceRuntime implements ServiceRuntime {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessServiceRuntime.class);

    /** 启动后观察窗口：进程秒退视为失败。 */
    private static final long PROBE_MS = 1500;
    private static final String MANAGED_BEGIN = "# >>> agent-platform managed (dev) >>>";
    private static final String MANAGED_END = "# <<< agent-platform managed (dev) <<<";

    private final EnvironmentStore store;
    private final ProjectRepoLocator repos;
    private final Path stateRoot;

    public LocalProcessServiceRuntime(
            EnvironmentStore store,
            ProjectRepoLocator repos,
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.store = store;
        this.repos = repos;
        this.stateRoot = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("local");
    }

    @Override
    public Result start(String projectId, String env, String service, int port) {
        Availability ready = availability(projectId, service);
        if (!ready.supported()) {
            return Result.failed(ready.hint());
        }
        Path repo = repos.repoDir(projectId).orElse(null);
        if (repo == null) {
            return Result.failed("找不到项目仓库目录：" + projectId);
        }
        Path dir;
        List<String> argv;
        if (ServiceLease.BACKEND.equals(service)) {
            dir = backendDir(repo);
            argv = backendCmd(dir, port);
            if (argv == null) {
                return Result.failed("后端没有可运行产物：请在 " + dir
                        + " 执行 mvn package 生成 target 下的 jar，或保留 mvnw");
            }
        } else {
            dir = frontendDir(repo);
            argv = frontendCmd(port);
        }

        stop(projectId, env, service);

        Path logFile = logFile(projectId, service);
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            return Result.failed("无法创建日志目录：" + e.getMessage());
        }

        ProcessBuilder pb = new ProcessBuilder(argv)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Map<String, String> injected = injectEnv(projectId, env);
        pb.environment().putAll(injected);
        pb.environment().put("SERVER_PORT", String.valueOf(port));
        pb.environment().put("PORT", String.valueOf(port));
        pb.environment().put("AGENT_ENV", env);
        if (ServiceLease.FRONTEND.equals(service)) {
            int backendPort = backendPort(projectId, env);
            pb.environment().put("VITE_API_BASE_URL", "http://127.0.0.1:" + backendPort);
            writeFrontendEnvLocal(dir, backendPort);
        }

        try {
            Process process = pb.start();
            store.saveServiceLease(new ServiceLease(projectId, env, service, port,
                    "RUNNING", process.pid(), System.currentTimeMillis()));
            boolean exited = process.waitFor(PROBE_MS, TimeUnit.MILLISECONDS);
            if (exited && process.exitValue() != 0) {
                store.saveServiceLease(new ServiceLease(projectId, env, service, port,
                        "STOPPED", 0, System.currentTimeMillis()));
                return new Result(false, port, 0, "STOPPED",
                        service + " 启动即退出（exit " + process.exitValue() + "）：" + tail(logFile));
            }
            log.info("本地服务已启动：project={} env={} service={} port={} pid={}",
                    projectId, env, service, port, process.pid());
            return new Result(true, port, process.pid(), "RUNNING",
                    service + " 已启动：http://127.0.0.1:" + port);
        } catch (IOException e) {
            return Result.failed("无法启动 " + service + "：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("启动被中断");
        }
    }

    @Override
    public Result stop(String projectId, String env, String service) {
        Optional<ServiceLease> lease = store.serviceLeases(projectId, env).stream()
                .filter(l -> l.service().equals(service)).findFirst();
        if (lease.isEmpty() || lease.get().pid() <= 0) {
            return new Result(true, lease.map(ServiceLease::hostPort).orElse(0), 0,
                    "STOPPED", service + " 没有由平台启动的进程");
        }
        long pid = lease.get().pid();
        int port = lease.get().hostPort();
        // pid 复用防护：命令行对不上说明是别的进程，绝不误杀
        if (!owns(pid, service, port)) {
            store.saveServiceLease(new ServiceLease(projectId, env, service, port,
                    "STOPPED", 0, System.currentTimeMillis()));
            return new Result(true, port, 0, "STOPPED",
                    "pid " + pid + " 已被其它进程复用，未执行结束操作（租约已清理）");
        }
        boolean stopped = kill(pid);
        store.saveServiceLease(new ServiceLease(projectId, env, service, port,
                "STOPPED", 0, System.currentTimeMillis()));
        if (!stopped) {
            return new Result(false, port, 0, "UNKNOWN", "进程 " + pid + " 结束失败（可能已不存在）");
        }
        log.info("本地服务已停止：project={} service={} pid={}", projectId, service, pid);
        return new Result(true, port, 0, "STOPPED", service + " 已停止");
    }

    @Override
    public String status(String projectId, String env, String service) {
        Optional<ServiceLease> lease = store.serviceLeases(projectId, env).stream()
                .filter(l -> l.service().equals(service)).findFirst();
        if (lease.isEmpty()) {
            return "UNKNOWN";
        }
        long pid = lease.get().pid();
        if (pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && owns(pid, service, lease.get().hostPort())) {
            return "RUNNING";
        }
        return "STOPPED";
    }

    @Override
    public Availability availability(String projectId, String service) {
        Path repo = repos.repoDir(projectId).orElse(null);
        if (repo == null) {
            return new Availability(false, "找不到项目仓库目录：" + projectId);
        }
        if (ServiceLease.BACKEND.equals(service)) {
            Path dir = backendDir(repo);
            if (dir == null) {
                return new Availability(false, "仓库里没有后端模块（需要 backend/ 或根目录 pom.xml）");
            }
            if (newestJar(dir) != null) {
                return new Availability(true, "使用 target 下最新的 jar 启动");
            }
            if (Files.isRegularFile(dir.resolve(wrapperName()))) {
                return new Availability(true, "没有 jar，将用 " + wrapperName() + " spring-boot:run 启动（首次较慢）");
            }
            if (Files.isRegularFile(dir.resolve("pom.xml"))) {
                if (commandOnPath(isWindows() ? "mvn.cmd" : "mvn") == null) {
                    return new Availability(false,
                            "没有可运行 jar，也没找到 mvn/mvnw：请先在 backend 执行 mvn package");
                }
                return new Availability(true, "没有 jar，将用 mvn spring-boot:run 启动（首次较慢）");
            }
            return new Availability(false, "后端目录既没有 target jar 也没有 pom.xml，无法启动");
        }
        if (ServiceLease.FRONTEND.equals(service)) {
            Path dir = frontendDir(repo);
            if (dir == null) {
                return new Availability(false, "仓库里没有前端模块（需要 frontend/ 或 web/ 且含 package.json）");
            }
            if (commandOnPath(isWindows() ? "npm.cmd" : "npm") == null) {
                return new Availability(false, "未找到 npm：请安装 Node.js 并把 npm 加入 PATH");
            }
            return new Availability(true, "使用 npm run dev 启动");
        }
        return new Availability(false, "未知服务：" + service);
    }

    @Override
    public List<String> logTail(String projectId, String service, int lines) {
        Path file = logFile(projectId, service);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            return all.subList(Math.max(0, all.size() - Math.max(1, lines)), all.size());
        } catch (IOException e) {
            return List.of("读取日志失败：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 命令

    private List<String> backendCmd(Path dir, int port) {
        Path jar = newestJar(dir);
        if (jar != null) {
            return List.of(javaBin(), "-jar", jar.toString(), "--server.port=" + port);
        }
        Path wrapper = dir.resolve(wrapperName());
        if (Files.isRegularFile(wrapper)) {
            return List.of(wrapper.toString(), "spring-boot:run",
                    "-Dspring-boot.run.arguments=--server.port=" + port);
        }
        if (Files.isRegularFile(dir.resolve("pom.xml"))) {
            String mvn = commandOnPath(isWindows() ? "mvn.cmd" : "mvn");
            return List.of(mvn == null ? (isWindows() ? "mvn.cmd" : "mvn") : mvn, "spring-boot:run",
                    "-Dspring-boot.run.arguments=--server.port=" + port);
        }
        return null;
    }

    private List<String> frontendCmd(int port) {
        String resolved = commandOnPath(isWindows() ? "npm.cmd" : "npm");
        String npm = resolved == null ? (isWindows() ? "npm.cmd" : "npm") : resolved;
        return List.of(npm, "run", "dev", "--", "--port", String.valueOf(port),
                "--strictPort", "--host", "127.0.0.1");
    }

    private static String wrapperName() {
        return isWindows() ? "mvnw.cmd" : "mvnw";
    }

    /** 在 PATH 中查找可执行文件（Windows 兼容 npm.cmd / mvn.cmd），找不到返回 null。 */
    private static String commandOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(entry.trim(), name);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            } catch (RuntimeException ignored) {
                // 非法 PATH 项直接跳过
            }
        }
        return null;
    }

    /**
     * pid 是否仍是我们启动的那个进程（防 pid 复用误杀）。
     * 拿不到命令行时保守返回 true：Windows 上部分进程不暴露 CommandLine。
     */
    private static boolean owns(long pid, String service, int port) {
        return ProcessHandle.of(pid)
                .flatMap(h -> h.info().commandLine())
                .map(cmd -> {
                    String lower = cmd.toLowerCase(Locale.ROOT);
                    if (lower.contains(":" + port) || lower.contains("server.port=" + port)) {
                        return true;
                    }
                    return ServiceLease.BACKEND.equals(service)
                            ? lower.contains("-jar") || lower.contains("spring-boot")
                            : lower.contains("npm") || lower.contains("node") || lower.contains("vite");
                })
                .orElse(true);
    }

    private Path logFile(String projectId, String service) {
        return stateRoot.resolve(projectId).resolve(service + ".log");
    }

    private static String javaBin() {
        String home = System.getProperty("java.home");
        if (home == null || home.isBlank()) {
            return "java";
        }
        Path bin = Path.of(home, "bin", isWindows() ? "java.exe" : "java");
        return Files.isRegularFile(bin) ? bin.toString() : "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ---------------------------------------------------------------- 目录

    private Path backendDir(Path repo) {
        for (String name : List.of("backend", "server", "app")) {
            Path dir = repo.resolve(name);
            if (Files.isDirectory(dir) && (Files.isRegularFile(dir.resolve("pom.xml"))
                    || Files.isDirectory(dir.resolve("target")))) {
                return dir;
            }
        }
        if (Files.isRegularFile(repo.resolve("pom.xml"))) {
            return repo;
        }
        // RuoYi 等布局：后端在仓库的一级子目录（如 RuoYi-Vue/pom.xml）
        return firstChild(repo, dir -> Files.isRegularFile(dir.resolve("pom.xml")));
    }

    private Path frontendDir(Path repo) {
        for (String name : List.of("frontend", "web", "ui", "client")) {
            Path dir = repo.resolve(name);
            if (Files.isRegularFile(dir.resolve("package.json"))) {
                return dir;
            }
        }
        if (Files.isRegularFile(repo.resolve("package.json"))) {
            return repo;
        }
        Path direct = firstChild(repo, dir -> Files.isRegularFile(dir.resolve("package.json")));
        if (direct != null) {
            return direct;
        }
        // RuoYi 等布局：前端在二级目录（如 RuoYi-Vue/ruoyi-ui/package.json）
        try (Stream<Path> stream = Files.list(repo)) {
            List<Path> parents = stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .toList();
            for (Path parent : parents) {
                Path nested = firstChild(parent, dir -> {
                    String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                    return List.of("ui", "web", "frontend", "client", "ruoyi-ui").contains(name)
                            && Files.isRegularFile(dir.resolve("package.json"));
                });
                if (nested != null) {
                    return nested;
                }
            }
        } catch (java.io.IOException ignored) {
            // 目录不可读按没有前端处理
        }
        return null;
    }

    private static Path firstChild(Path parent, java.util.function.Predicate<Path> match) {
        try (Stream<Path> stream = Files.list(parent)) {
            return stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(match)
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private Path newestJar(Path dir) {
        Path target = dir.resolve("target");
        if (!Files.isDirectory(target)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(target)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-javadoc.jar"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 环境

    private Map<String, String> injectEnv(String projectId, String env) {
        try {
            EffectiveEnvironment effective = store.effective(projectId, env);
            return effective.values();
        } catch (RuntimeException e) {
            log.warn("读取项目环境失败（继续用系统环境）：project={} {}", projectId, e.getMessage());
            return Map.of();
        }
    }

    private int backendPort(String projectId, String env) {
        return store.serviceLeases(projectId, env).stream()
                .filter(l -> ServiceLease.BACKEND.equals(l.service()))
                .map(ServiceLease::hostPort)
                .findFirst().orElse(0);
    }

    /** 前端 .env.local：只保留受管块，其它内容原样保留。 */
    private void writeFrontendEnvLocal(Path dir, int backendPort) {
        Path file = dir.resolve(".env.local");
        List<String> kept = new ArrayList<>();
        try {
            if (Files.isRegularFile(file)) {
                boolean inside = false;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.trim().equals(MANAGED_BEGIN)) {
                        inside = true;
                        continue;
                    }
                    if (line.trim().equals(MANAGED_END)) {
                        inside = false;
                        continue;
                    }
                    if (!inside) {
                        kept.add(line);
                    }
                }
            }
            while (!kept.isEmpty() && kept.get(kept.size() - 1).isBlank()) {
                kept.remove(kept.size() - 1);
            }
            kept.add(MANAGED_BEGIN);
            kept.add("VITE_API_BASE_URL=http://127.0.0.1:" + backendPort);
            kept.add("VITE_APP_ENV=dev");
            kept.add(MANAGED_END);
            Files.write(file, kept, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写 .env.local 失败（不影响启动）：{}", e.getMessage());
        }
    }

    private static boolean kill(long pid) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            return true;
        }
        List<ProcessHandle> children = handle.get().descendants().toList();
        handle.get().destroy();
        children.forEach(ProcessHandle::destroy);
        try {
            handle.get().onExit().get(5, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            handle.get().destroyForcibly();
            children.forEach(ProcessHandle::destroyForcibly);
            return !handle.get().isAlive();
        }
    }

    private static String tail(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 12);
            return String.join(" | ", lines.subList(from, lines.size()));
        } catch (IOException e) {
            return "（无日志）";
        }
    }
}
