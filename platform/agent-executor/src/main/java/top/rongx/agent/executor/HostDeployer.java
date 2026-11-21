package top.rongx.agent.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.port.Deployer;
import top.rongx.agent.domain.process.JavaRuntime;
import top.rongx.agent.domain.error.PlatformException;

/**
 * 本机直接运行部署器（HOST）。
 *
 * <p>版本目录（方案 C 的可回滚要求）：
 * <pre>
 * &lt;deployRoot&gt;/&lt;project&gt;-&lt;env&gt;/   ← 平台数据目录，跨运行稳定（不再放进每次运行的临时工作树）
 *   ├── releases/&lt;sha&gt;/app.jar     ← 每次部署一份只读副本
 *   ├── web/                        ← 前端 dist 副本（有则交给后端静态托管）
 *   ├── current                     ← 当前版本 sha（纯文本）
 *   ├── previous                    ← 上一版 sha（回滚锚点）
 *   ├── app.pid                    ← 当前进程 pid
 *   └── app.log                    ← 进程 stdout/stderr
 * </pre>
 *
 * <p>部署顺序：落盘新版本 → 停旧进程 → 起新进程 → 成功后才轮转 current/previous。
 * 因此<b>启动失败不会污染回滚锚点</b>。
 *
 * <p>边界：只支持 Java jar（{@code java -jar}）。有前端 dist 时复制到状态目录，
 * 由后端通过 {@code AGENT_WEB_DIST} 静态托管，单端口同时提供页面与接口。
 */
public class HostDeployer implements Deployer {

    private static final long STARTUP_PROBE_MS = 800;

    /**
     * 产物复制的尝试次数与间隔。
     *
     * <p>「另一个程序正在使用此文件」在 Windows 上多是短暂占用（旧实例退出中、
     * 杀毒在扫），重试 6 次共约 3 秒即可覆盖，不必让人手动重跑。
     */
    private static final int COPY_ATTEMPTS = 6;
    private static final long COPY_RETRY_DELAY_MS = 500L;

    /** 稳定的部署状态根（通常 platform/data/workspaces/_deploy）；null = 退回工作树内旧行为。 */
    private final Path deployRoot;

    public HostDeployer() {
        this(null);
    }

    public HostDeployer(Path deployRoot) {
        this.deployRoot = deployRoot;
    }

    @Override
    public boolean supports(DeployTarget target) {
        return target == DeployTarget.HOST;
    }

    @Override
    public DeployResult deploy(DeployRequest request) {
        Path workdir = Path.of(request.workspace());
        // 状态目录必须跨运行稳定：放在本次运行的临时工作树里，会导致日志随工作树消失、
        // 每次部署都看不到 previous（回滚永远 E-507）。
        String suffix = request.envName() == null || request.envName().isBlank()
                ? "" : "-" + request.envName().trim();
        Path baseDir = deployRoot != null
                ? deployRoot.resolve(request.projectId() + suffix)
                : workdir.resolve(".agent/deploy").resolve(request.projectId());
        // 打 tag = 独立版本槽（各自 pid / current / app.log）；不打 tag = 覆盖默认槽。
        // 这样同一环境可以同时跑多个版本的 jar，互不替换。
        Path stateDir = baseDir;
        String slot = request.tagged() ? safeSlot(request.version()) : null;
        if (slot != null) {
            stateDir = baseDir.resolve("versions").resolve(slot);
        }
        StringBuilder log = new StringBuilder();

        try {
            Files.createDirectories(stateDir.resolve("releases"));
            // 版本号落盘：环境矩阵按它展示行（没填版本号的普通部署记为 latest；回滚不改标签）
            String label = request.version() != null && !request.version().isBlank()
                    ? request.version().trim()
                    : (request.rollback() ? null : "latest");
            if (label != null) {
                Files.writeString(stateDir.resolve("version"), label, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return DeployResult.failed(DeployTarget.HOST, ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                    "无法创建部署状态目录：" + e.getMessage(), "");
        }

        String releaseSha;
        Path jar;
        if (request.rollback()) {
            releaseSha = readPointer(stateDir.resolve("previous"));
            if (releaseSha == null) {
                return DeployResult.failed(DeployTarget.HOST, ErrorCode.ROLLBACK_VERSION_MISSING.code(),
                        "没有可回滚的本机版本（尚无 previous 指针）", "");
            }
            jar = stateDir.resolve("releases").resolve(releaseSha).resolve("app.jar");
            if (!Files.isRegularFile(jar)) {
                return DeployResult.failed(DeployTarget.HOST, ErrorCode.ROLLBACK_VERSION_MISSING.code(),
                        "上一版 jar 不存在：" + jar, "");
            }
            log.append("[host] rollback to ").append(releaseSha).append('\n');
        } else {
            releaseSha = request.gitSha();
            Path sourceJar = resolveJar(request, workdir);
            if (sourceJar == null) {
                return DeployResult.failed(DeployTarget.HOST, ErrorCode.DEPLOY_TARGET_UNDETERMINED.code(),
                        "HOST 目标需要可运行的 jar（target/*.jar）；前端静态产物请选择 DOCKER 目标",
                        "");
            }
            jar = stateDir.resolve("releases").resolve(releaseSha).resolve("app.jar");
            try {
                Files.createDirectories(jar.getParent());
                copyReplacing(sourceJar, jar);
                // 前端 dist 与 jar 同版本落盘：回滚时页面与后端保持一致
                Path dist = findWebDist(workdir);
                if (dist != null) {
                    Path web = jar.getParent().resolve("web");
                    if (copyTree(dist, web)) {
                        log.append("[host] 前端静态产物 ").append(rel(workdir, dist)).append('\n');
                    } else {
                        log.append("[host] 前端静态产物复制失败，仅部署后端\n");
                    }
                }
            } catch (IOException e) {
                // 产物落盘失败要给出「该怎么做」，而不是把 Windows 那句
                // 「另一个程序正在使用此文件」直接抛给用户。
                return DeployResult.failed(DeployTarget.HOST, ErrorCode.DEPLOY_PREREQUISITE_MISSING.code(),
                        "复制产物失败：" + e.getMessage()
                                + "；若提示文件被占用，说明同一版本的应用正在运行且未停止——"
                                + "先停止该实例（或改用不同的 version 发布）后重试",
                        "");
            }
        }

        Path pidFile = stateDir.resolve(HostProcessSupervisor.PID_FILE);
        Path logFile = stateDir.resolve(HostProcessSupervisor.LOG_FILE);
        // pid 文件可能在上一次运行里丢失/被覆盖（线上日志里 8090 上的旧实例就是这么留下的）。
        // 槽内另有追加式进程登记，一并回收，避免旧进程继续占端口、健康检查打到旧进程。
        HostProcessSupervisor.stop(stateDir, false, log);

        // 用与当前平台一致的 JDK 启动，不去 PATH 上碰运气（PATH 可能是 JDK17、平台跑在 25）。
        List<String> argv = List.of(JavaRuntime.javaBinary(), "-jar", jar.toString(),
                "--server.port=" + request.hostPort());
        // 平台「设置 → 项目环境」里的变量（与 DOCKER 目标同一份数据）
        Map<String, String> env = new java.util.LinkedHashMap<>(request.env());
        // 前端静态目录：后端同端口托管页面（SPA fallback 由网关实现），省掉第二个静态服务
        Path webDist = jar.getParent().resolve("web");
        if (Files.isDirectory(webDist)) {
            env.put("AGENT_WEB_DIST", webDist.toAbsolutePath().toString());
            log.append("[host] 前端由后端同端口托管 ").append(webDist.toAbsolutePath()).append('\n');
        }

        try {
            Process process = HostProcessSupervisor.start(stateDir, argv, env);
            if (HostProcessSupervisor.exitedDuringProbe(process) && process.exitValue() != 0) {
                Files.deleteIfExists(pidFile);
                return DeployResult.failed(DeployTarget.HOST, ErrorCode.DEPLOY_START_FAILED.code(),
                        "进程启动即退出（exit " + process.exitValue() + "）", tailOf(logFile));
            }
            rotatePointers(stateDir, releaseSha, request.rollback());
            // 端口落盘：控制台「重启」要沿用流水线分配的端口，冲突时才漂移
            HostProcessSupervisor.writePort(stateDir, request.hostPort());
            log.append("[host] started pid=").append(process.pid())
                    .append(" release=").append(releaseSha)
                    .append(" port=").append(request.hostPort()).append('\n');
            return DeployResult.ok(DeployTarget.HOST,
                    // 部署地址 = 根路径（前端页面入口）；healthPath 只用于探活判定，
                    // 返回的是 JSON，拿它当「打开部署地址」的跳转目标会误导用户
                    "http://127.0.0.1:" + request.hostPort() + "/",
                    releaseSha, ProcessExecutor.redact(log.toString()));
        } catch (IOException e) {
            return DeployResult.failed(DeployTarget.HOST, ErrorCode.DEPLOY_START_FAILED.code(),
                    "无法启动进程：" + e.getMessage(), "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeployResult.failed(DeployTarget.HOST, ErrorCode.DEPLOY_START_FAILED.code(), "启动被中断", "");
        }
    }

    /** 成功后轮转指针：普通部署 current→previous 变成新 current；回滚把 current 退回 previous。 */
    private void rotatePointers(Path stateDir, String releaseSha, boolean rollback) {
        Path current = stateDir.resolve("current");
        Path previous = stateDir.resolve("previous");
        try {
            if (rollback) {
                String old = readPointer(current);
                writePointer(current, releaseSha);
                if (old != null) {
                    writePointer(previous, old);
                }
                return;
            }
            String existing = readPointer(current);
            if (existing != null && !existing.equals(releaseSha)) {
                writePointer(previous, existing);
            }
            writePointer(current, releaseSha);
        } catch (IOException e) {
            ProcessExecutor.warn("部署指针写入失败：" + e.getMessage());
        }
    }

    private String readPointer(Path file) {
        return HostProcessSupervisor.readText(file);
    }

    private void writePointer(Path file, String value) throws IOException {
        Files.writeString(file, value, StandardCharsets.UTF_8);
    }

    /**
     * 回收本槽的遗留进程：按槽内追加的进程登记（{@code app.pids}）。
     *
     * <p>只看 pid 文件不够：上一次运行失败/被强制结束时 pid 文件可能已经丢了，
     * 但进程还活着（线上日志里 8090 上那个旧 release 就是这样留下来的）。
     * 登记文件是槽私有的，{@code versions/<tag>} 下的并行版本互不影响。
     *
     * <p>Windows 上 {@code ProcessHandle.Info.commandLine()} 对其它进程为空，
     * 无法按命令行识别，因此登记时同时写入启动时刻：pid 被系统复用时启动时刻对不上，
     * 一律跳过，避免误杀无关进程。
     */
    void stopRecorded(Path stateDir, StringBuilder log) {
        HostProcessSupervisor.stopRecorded(stateDir, log);
    }

    /** 追加登记刚拉起的进程：每行 {@code pid 启动时刻毫秒}。 */
    void recordProcess(Path stateDir, ProcessHandle handle) {
        HostProcessSupervisor.recordProcess(stateDir, handle);
    }

    /** 找可运行 jar：显式 artifact 优先，否则 target/*.jar（排除 sources/javadoc）。 */
    private Path resolveJar(DeployRequest request, Path workdir) {
        if (request.artifactPath() != null && !request.artifactPath().isBlank()) {
            Path explicit = workdir.resolve(request.artifactPath()).normalize();
            return Files.isRegularFile(explicit) ? explicit : null;
        }
        Path base = request.workdir() == null || request.workdir().isBlank()
                ? workdir : workdir.resolve(request.workdir()).normalize();
        Path target = base.resolve("target");
        if (Files.isDirectory(target)) {
            try (var stream = Files.list(target)) {
                Path found = stream
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                        .filter(p -> !p.getFileName().toString().endsWith("-javadoc.jar"))
                        .sorted()
                        .findFirst()
                        .orElse(null);
                if (found != null) {
                    return found;
                }
            } catch (IOException ignored) {
                // 根 target 读不了时继续按子模块找
            }
        }
        // Maven 聚合工程：可运行 jar 在子模块的 target 下（如 ruoyi-admin/target/*.jar）
        return BuiltInPipelineEngine.findRunnableJar(base);
    }

    private String tailOf(Path logFile) {
        return HostProcessSupervisor.tail(logFile, 20);
    }

    /** 版本槽目录名：只保留安全字符（上游已校验，这里兜底防路径穿越）。 */
    private static String safeSlot(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String safe = version.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        return safe.isBlank() ? null : safe;
    }

    /** 本次构建的前端产物：优先 platform/web/dist，其次任意 dist/index.html（排除依赖目录）。 */
    private static Path findWebDist(Path root) {
        Path prefer = root.resolve("platform/web/dist");
        if (Files.isRegularFile(prefer.resolve("index.html"))) {
            return prefer;
        }
        try (var stream = Files.walk(root, 5)) {
            return stream.filter(p -> p.getFileName().toString().equals("dist"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("node_modules"))
                    .filter(p -> Files.isRegularFile(p.resolve("index.html")))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 供同包测试调用的薄封装：复制逻辑本身不对外暴露（它是部署器的内部细节）。
     *
     * <p>可见性保持包级，生产代码没有任何调用点。
     */
    static void copyReplacingForTest(Path source, Path target) throws IOException {
        copyReplacing(source, target);
    }

    /**
     * 复制产物到目标路径，对被短暂占用的情况做有限重试。
     *
     * <p>为什么需要重试：Windows 上「另一个程序正在使用此文件」有两个真实来源——
     * 目标位置的旧实例还没完全退出（文件句柄未释放），或源文件正被 JVM/杀毒扫描持有。
     * 这两种都是短暂状态，直接失败会把「等一秒就好」变成一次部署失败，
     * 也就是线上那种「同一份代码重跑一次就成功」的怪现象。
     *
     * <p>先复制到同目录临时文件再原子替换：避免复制中途失败在目标位置留下半个 jar，
     * 让下一次启动拿到一个坏产物。
     */
    private static void copyReplacing(Path source, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        IOException last = null;
        for (int attempt = 0; attempt < COPY_ATTEMPTS; attempt++) {
            try {
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(COPY_RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("复制产物被中断：" + e.getMessage(), e);
                }
            }
        }
        Files.deleteIfExists(tmp);
        throw last == null ? new IOException("复制产物失败：" + source) : last;
    }

    private static boolean copyTree(Path from, Path to) {
        try {
            Files.createDirectories(to);
            try (var stream = Files.walk(from)) {
                for (Path src : (Iterable<Path>) stream::iterator) {
                    Path dst = to.resolve(from.relativize(src).toString());
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String rel(Path root, Path path) {
        try {
            return root.relativize(path).toString();
        } catch (PlatformException | IllegalArgumentException e) {
            return path.toString();
        }
    }
}
