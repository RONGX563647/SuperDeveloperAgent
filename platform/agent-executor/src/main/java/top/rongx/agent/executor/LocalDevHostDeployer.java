package top.rongx.agent.executor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.ServiceLease;

/**
 * 开发环境本机托管：后端 {@code java -jar} + 前端 dist（Node 静态服务器），全部跑在宿主机。
 *
 * <p>与 Docker 目标的区别：不开容器、不打镜像；进程 pid 与日志落在项目本地
 * {@code <repo>/.agent/deploy/<project>-<env>/}，因此可随时「停止本机服务」后改由 IDE 启动。
 *
 * <pre>
 * &lt;repo&gt;/.agent/deploy/&lt;project&gt;-&lt;env&gt;/
 *   ├── backend.pid / backend.log     ← java -jar &lt;jar&gt; --server.port=…
 *   ├── frontend.pid / frontend.log   ← node static-server.cjs &lt;dist&gt; &lt;port&gt; &lt;backendPort&gt;
 *   ├── static-server.cjs             ← 平台生成的静态服务器（/prod-api、/dev-api 反代后端）
 *   ├── maven.log / npm.log           ← 本机构建日志
 * </pre>
 */
public class LocalDevHostDeployer {

    /** 后端启动等待（RuoYi 首次启动含连接池/迁移，给足 3 分钟）。 */
    private static final long BACKEND_READY_TIMEOUT_MS = 180_000;
    /** 前端静态服务器就绪等待。 */
    private static final long FRONTEND_READY_TIMEOUT_MS = 60_000;

    /** 环境仓储（可空）：非空时把本机前后端登记成 ServiceLease，让 env_runtime_status 可见。 */
    private final EnvironmentStore store;
    /** 日志共享目录（可空）：与 LocalProcessServiceRuntime.logFile 对齐，让 app_logs 读得到。 */
    private final Path sharedLogRoot;

    public LocalDevHostDeployer() {
        this(null, null);
    }

    public LocalDevHostDeployer(EnvironmentStore store, Path sharedLogRoot) {
        this.store = store;
        this.sharedLogRoot = sharedLogRoot;
    }

    /**
     * @param ok          是否部署成功
     * @param endpoint    对外访问地址（有前端 = 前端地址；无前端 = 后端地址）
     * @param errorCode   失败码（成功为 null）
     * @param summary     失败摘要 / 成功摘要
     * @param log         过程日志（已脱敏）
     * @param backendPort 后端端口
     * @param frontendPort 前端端口（0 = 无前端）
     */
    public record Result(boolean ok, String endpoint, String errorCode, String summary, String log,
                         int backendPort, int frontendPort) {
    }

    /** 本机进程状态目录。 */
    public static Path stateDir(Path repo, String projectId, String env) {
        String suffix = env == null || env.isBlank() ? "" : "-" + env.trim();
        return repo.resolve(".agent/deploy").resolve(normalize(projectId) + suffix);
    }

    /**
     * 启动本机后端 + 前端静态服务器（先停旧，保证替换式发布）。
     *
     * @param jar       可运行 jar
     * @param distDir   前端 dist 目录（null/不存在 = 只托管后端）
     */
    public Result deploy(Path repo, String projectId, String env, Path jar, Path distDir,
                         int backendPort, int frontendPort, Map<String, String> envVars) {
        StringBuilder log = new StringBuilder();
        Path dir = stateDir(repo, projectId, env);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return new Result(false, null, "E-601",
                    "无法创建本机部署目录：" + e.getMessage(), "", backendPort, frontendPort);
        }
        if (jar == null || !Files.isRegularFile(jar)) {
            return new Result(false, null, "E-601",
                    "本机部署需要可运行 jar（target/*.jar），本次未找到", "", backendPort, frontendPort);
        }

        stopIn(dir, projectId, env, log);

        // 1. 后端
        Path backendLog = logFile(projectId, dir, ServiceLease.BACKEND);
        Path backendPid = dir.resolve("backend.pid");
        startLog(backendLog, "[dev] java -jar " + jar.getFileName() + " --server.port=" + backendPort);
        Process backend;
        try {
            List<String> argv = List.of(javaBin(), "-jar", jar.toString(),
                    "--server.port=" + backendPort);
            ProcessBuilder pb = new ProcessBuilder(argv)
                    .directory(repo.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(backendLog.toFile()));
            pb.environment().put("CI", "true");
            if (envVars != null) {
                envVars.forEach((k, v) -> pb.environment().put(k, v == null ? "" : v));
            }
            backend = pb.start();
            Files.writeString(backendPid, String.valueOf(backend.pid()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Result(false, null, "E-601",
                    "无法启动后端进程：" + e.getMessage(), "", backendPort, frontendPort);
        }
        log.append("[dev] 后端启动中 pid=").append(backend.pid())
                .append("，端口 ").append(backendPort).append('\n');
        String backendFail = awaitBackend(backend, backendPort);
        if (backendFail != null) {
            kill(backend.pid());
            deleteQuietly(backendPid);
            return new Result(false, null, "E-601",
                    "后端未就绪：" + backendFail, redact(log.toString() + tail(backendLog)),
                    backendPort, frontendPort);
        }
        log.append("[dev] 后端就绪 http://127.0.0.1:").append(backendPort).append('\n');
        saveLease(projectId, env, ServiceLease.BACKEND, backendPort, backend.pid());

        // 2. 前端（可选）
        int actualFrontend = 0;
        if (distDir != null && Files.isRegularFile(distDir.resolve("index.html"))) {
            Path script = dir.resolve("static-server.cjs");
            Path frontendLog = logFile(projectId, dir, ServiceLease.FRONTEND);
            Path frontendPid = dir.resolve("frontend.pid");
            try {
                Files.writeString(script, StaticServerScript.text(), StandardCharsets.UTF_8);
                startLog(frontendLog, "[dev] node static-server.mjs " + distDir
                        + " " + frontendPort + " " + backendPort);
                ProcessBuilder pb = new ProcessBuilder(List.of(nodeBin(), script.toString(),
                        distDir.toAbsolutePath().toString(), String.valueOf(frontendPort),
                        String.valueOf(backendPort)))
                        .directory(repo.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(frontendLog.toFile()));
                Process frontend = pb.start();
                Files.writeString(frontendPid, String.valueOf(frontend.pid()), StandardCharsets.UTF_8);
                log.append("[dev] 前端静态服务器启动中 pid=").append(frontend.pid())
                        .append("，端口 ").append(frontendPort).append('\n');
                String frontendFail = awaitFrontend(frontend, frontendPort);
                if (frontendFail != null) {
                    kill(frontend.pid());
                    deleteQuietly(frontendPid);
                    return new Result(false, null, "E-603",
                            "前端静态服务器未就绪：" + frontendFail,
                            redact(log.toString() + tail(frontendLog)), backendPort, frontendPort);
                }
                actualFrontend = frontendPort;
                log.append("[dev] 前端就绪 http://127.0.0.1:").append(frontendPort).append('\n');
                saveLease(projectId, env, ServiceLease.FRONTEND, frontendPort, frontend.pid());
            } catch (IOException e) {
                return new Result(false, null, "E-603",
                        "无法启动前端静态服务器：" + e.getMessage(), log.toString(),
                        backendPort, frontendPort);
            }
        } else {
            log.append("[dev] 未提供前端 dist，仅托管后端\n");
        }

        String endpoint = actualFrontend > 0
                ? "http://127.0.0.1:" + actualFrontend + "/"
                : "http://127.0.0.1:" + backendPort + "/";
        return new Result(true, endpoint, null,
                actualFrontend > 0 ? "后端 + 前端（本机）" : "后端（本机，无前端）",
                redact(log.toString()), backendPort, actualFrontend);
    }

    /** 停掉平台托管的 dev 前后端（幂等）。@return true = 至少停掉一个仍在运行的进程。 */
    public boolean stop(Path repo, String projectId, String env) {
        return stopIn(stateDir(repo, projectId, env), projectId, env, new StringBuilder());
    }

    private boolean stopIn(Path dir, String projectId, String env, StringBuilder log) {
        boolean stopped = false;
        for (String name : List.of("frontend.pid", "backend.pid")) {
            Path pidFile = dir.resolve(name);
            if (Files.isRegularFile(pidFile)) {
                try {
                    long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
                    Optional<ProcessHandle> handle = ProcessHandle.of(pid);
                    if (handle.isPresent() && handle.get().isAlive()) {
                        kill(pid);
                        stopped = true;
                        log.append("[dev] 已停止 ").append(name.replace(".pid", ""))
                                .append(" pid=").append(pid).append('\n');
                    }
                } catch (Exception e) {
                    log.append("[dev] 停止 ").append(name).append(" 失败（继续）：")
                            .append(e.getMessage()).append('\n');
                }
                deleteQuietly(pidFile);
            }
            deleteLease(projectId, env, name.replace(".pid", ""));
        }
        return stopped;
    }

    /** 登记本机服务租约：平台 env_runtime_status / port_check 由此看到 dev 应用。 */
    private void saveLease(String projectId, String env, String service, int port, long pid) {
        if (store == null || projectId == null || projectId.isBlank() || port <= 0) {
            return;
        }
        try {
            store.saveServiceLease(new ServiceLease(projectId, env, service, port,
                    "RUNNING", pid, System.currentTimeMillis()));
        } catch (RuntimeException ignored) {
            // 登记失败不影响部署（只影响可观测性）
        }
    }

    private void deleteLease(String projectId, String env, String service) {
        if (store == null || projectId == null || projectId.isBlank()) {
            return;
        }
        try {
            store.deleteServiceLease(projectId, env, service);
        } catch (RuntimeException ignored) {
            // 同上
        }
    }

    /** 日志文件：配了共享目录就与 app_logs 读同一份，否则留在项目状态目录。 */
    private Path logFile(String projectId, Path dir, String service) {
        if (sharedLogRoot == null) {
            return dir.resolve(service + ".log");
        }
        // 目录名与 LocalProcessServiceRuntime.logFile 保持一致：projectId 原样（不转小写）
        Path file = sharedLogRoot.resolve(projectId).resolve(service + ".log");
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException ignored) {
            return dir.resolve(service + ".log");
        }
        return file;
    }

    /** 平台托管的本机服务是否在运行（前端或后端任一存活）。 */
    public boolean isRunning(Path repo, String projectId, String env) {
        Path dir = stateDir(repo, projectId, env);
        for (String name : List.of("backend.pid", "frontend.pid")) {
            Path pidFile = dir.resolve(name);
            if (!Files.isRegularFile(pidFile)) {
                continue;
            }
            try {
                long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).trim());
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 坏 pid 文件视为未运行
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 本机构建

    /** 优先仓库自带的 {@code ./mvnw}，否则用 PATH 里的 mvn；构建阶段跳过测试。 */
    public int mvnPackage(Path backendRoot, Path logFile) {
        boolean wrapper = Files.isRegularFile(backendRoot.resolve(isWindows() ? "mvnw.cmd" : "mvnw"));
        List<String> argv;
        if (isWindows()) {
            // spec-ignore: R02 Windows 的 mvn/mvnw 是 .cmd 批处理，CreateProcess 只能经 cmd /c 启动，参数全是常量无拼接
            argv = List.of("cmd", "/c", (wrapper ? "mvnw.cmd" : "mvn") + " -q -DskipTests package");
        } else {
            argv = wrapper
                    ? List.of("./mvnw", "-q", "-DskipTests", "package")
                    : List.of("mvn", "-q", "-DskipTests", "package");
        }
        return exec(argv, backendRoot, logFile, null, 15);
    }

    /** 前端打包：按 package.json 实际脚本选择 build:prod / build。@return 退出码。 */
    public int buildFrontend(Path frontendRoot, Path logFile) {
        String script = hasBuildProdScript(frontendRoot) ? "build:prod" : "build";
        List<String> argv;
        Map<String, String> extra = new TreeMap<>();
        extra.put("NODE_OPTIONS", "--openssl-legacy-provider");
        if (isWindows()) {
            // spec-ignore: R02 Windows 的 npm 是 .cmd 批处理，CreateProcess 只能经 cmd /c 启动，参数全是常量无拼接
            argv = List.of("cmd", "/c", "npm run " + script);
        } else {
            argv = List.of("npm", "run", script);
        }
        return exec(argv, frontendRoot, logFile, extra, 20);
    }

    private boolean hasBuildProdScript(Path frontendRoot) {
        try {
            return Files.readString(frontendRoot.resolve("package.json"),
                    StandardCharsets.UTF_8).contains("\"build:prod\"");
        } catch (Exception e) {
            return false;
        }
    }

    private int exec(List<String> argv, Path workdir, Path logFile, Map<String, String> extraEnv,
                     int timeoutMinutes) {
        try {
            Files.createDirectories(logFile.getParent());
            startLog(logFile, "[dev] " + String.join(" ", argv));
            ProcessBuilder pb = new ProcessBuilder(argv)
                    .directory(workdir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            if (extraEnv != null) {
                pb.environment().putAll(extraEnv);
            }
            Process process = pb.start();
            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                kill(process.pid());
                return -2;
            }
            return process.exitValue();
        } catch (IOException e) {
            try {
                Files.writeString(logFile, "[dev] 命令无法启动：" + e.getMessage() + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // 日志写不了不影响返回码
            }
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -2;
        }
    }

    // ------------------------------------------------------------------ 内部

    private String awaitBackend(Process process, int port) {
        long deadline = System.currentTimeMillis() + BACKEND_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (portOpen(port)) {
                return null;
            }
            if (!process.isAlive()) {
                return "进程已退出（exit " + process.exitValue() + "）";
            }
            sleep(500);
        }
        return "等待 " + (BACKEND_READY_TIMEOUT_MS / 1000) + " 秒后端口仍未监听";
    }

    private String awaitFrontend(Process process, int port) {
        long deadline = System.currentTimeMillis() + FRONTEND_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (portOpen(port)) {
                return null;
            }
            if (!process.isAlive()) {
                return "进程已退出（exit " + process.exitValue() + "）";
            }
            sleep(300);
        }
        return "等待 " + (FRONTEND_READY_TIMEOUT_MS / 1000) + " 秒后端口仍未监听";
    }

    private static boolean portOpen(int port) {
        if (port <= 0) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 结束进程树（后端会派生子进程；Windows 用 taskkill /T）。 */
    private static void kill(long pid) {
        try {
            if (isWindows()) {
                Process killer = new ProcessBuilder("taskkill", "/T", "/F", "/PID",
                        String.valueOf(pid)).redirectErrorStream(true).start();
                killer.waitFor(15, TimeUnit.SECONDS);
            } else {
                ProcessHandle.of(pid).ifPresent(h -> {
                    h.descendants().forEach(ProcessHandle::destroy);
                    h.destroy();
                });
            }
        } catch (Exception ignored) {
            // 结束失败不阻塞流程
        }
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
    }

    private static void startLog(Path logFile, String header) {
        try {
            Files.writeString(logFile, "\n===== " + header + " =====\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
            // 日志文件写不了时进程仍会重定向失败，交给调用方错误处理
        }
    }

    private static String tail(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 60);
            return String.join("\n", new ArrayList<>(lines.subList(from, lines.size())));
        } catch (IOException e) {
            return "";
        }
    }

    /** 构建日志尾部（供流水线展示）。 */
    public String logTail(Path logFile) {
        return ProcessExecutor.redact(tail(logFile));
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 删不掉不影响后续
        }
    }

    private static String javaBin() {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        return Files.isRegularFile(java) ? java.toString() : "java";
    }

    private static String nodeBin() {
        return isWindows() ? "node.exe" : "node";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "project";
        }
        return value.trim().toLowerCase().replaceAll("[^a-z0-9._-]", "-");
    }

    private static String redact(String text) {
        return ProcessExecutor.redact(text == null ? "" : text);
    }
}
