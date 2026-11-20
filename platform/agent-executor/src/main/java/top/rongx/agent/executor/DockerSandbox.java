package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import top.rongx.agent.domain.process.BoundedCommand;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.domain.error.SandboxDeniedException;

/**
 * 容器沙箱：把一条业务命令包成 {@code docker run}。
 *
 * <p>约束（均在开发机实测过）：
 * <ul>
 *   <li><b>网络按档位</b>：默认 {@code bridge}（能拉依赖）；{@code build-net} 为生产建议的专用网络；
 *       {@code none} 仅在"缓存已预热"的发布构建或构建不可信代码时使用；</li>
 *   <li>{@code --user 1000:1000} —— 非 root；</li>
 *   <li>{@code --cap-drop ALL --security-opt no-new-privileges} —— 不可提权；</li>
 *   <li>{@code --memory/--cpus/--pids-limit} —— 资源上限；</li>
 *   <li>{@code --entrypoint=} —— 清空镜像 ENTRYPOINT，直接执行 argv（不套 shell）；</li>
 *   <li>工作区挂到 {@code /ws} 并作为工作目录，产物落回宿主目录。</li>
 * </ul>
 *
 * <p><b>注意</b>：镜像需自带工具链（如 {@code maven:3.9-eclipse-temurin-21}）。
 * 当前**不往容器注入任何凭据**（本环境无私服）；需要时挂只读 settings.xml。
 *
 * <p><b>准备阶段的可观测性</b>：依赖缓存卷的初始化曾经藏在 {@link #wrap} 里，用
 * {@code readAllBytes()} 无限等待 docker CLI（且超时写在读流之后、异常被吞），
 * 一次镜像拉取卡住就会让整条运行静默停在 RUNNING。现在它由
 * {@link #prepareCacheVolume} 在动作开始前显式执行，走 {@link ProcessExecutor}
 * 的超时 / 取消 / 实时日志链路，失败即抛出并交给调用方记为失败。
 */
public class DockerSandbox {

    /** 容器内工作目录。 */
    public static final String CONTAINER_WORKDIR = "/ws";

    /** 准备阶段单步预算：镜像未预热时拉镜像可能很慢，但不能无限等。 */
    private static final long PREPARE_TIMEOUT_MS = 30_000L;

    /** 缓存卷初始化预算：首次创卷 + 冷镜像启动会超过 30s（线上实测偶发 E-302）。 */
    private static final long CACHE_INIT_TIMEOUT_MS = 90_000L;
    /** 缓存卷初始化尝试次数：超时属于瞬时基础设施问题，先自动重试再交给自愈。 */
    private static final int CACHE_INIT_ATTEMPTS = 2;

    /** 初始化完成标记：进程重启后据此跳过 chown -R，避免每次白白扫一遍 GB 级缓存。 */
    private static final String READY_MARKER = "/cache/.agent-ready";

    /** 构建容器的运行用户：缓存目录也归它所有（与 --user 保持一致）。 */
    private static final String CACHE_UID = "1000:1000";

    /** 镜像预检预算：本机已有时秒级返回；缺镜像时不能把"没有"误判成"网络慢"。 */
    private static final long IMAGE_INSPECT_TIMEOUT_MS = 5_000L;

    /** 自动拉取工具链镜像的默认预算（可由 agent.sandbox.pull-timeout-seconds 覆盖）。 */
    private static final long DEFAULT_IMAGE_PULL_TIMEOUT_MS = 180_000L;

    /** 缓存卷初始化的可信最小镜像：只做 mkdir/chown/touch，绝不用构建镜像（可能是不可信代码）。 */
    private static final String DEFAULT_INIT_IMAGE = "busybox:1.36";

    /**
     * 根文件系统只读后唯一可写的位置：{@code /tmp}。
     *
     * <p>必须显式带 {@code exec}：tmpfs 默认 {@code noexec}，而 JVM 会把 jansi 原生库释放到
     * {@code /tmp} 再 dlopen——noexec 下 Maven 构建会直接失败（开发机已实测）。
     * 也不能省掉 {@code /tmp}：Gradle、corepack、各类安装脚本都会往临时目录写。
     */
    private static final String TMPFS_TMP = "/tmp:rw,exec,mode=1777";

    /** 生命周期标签：启动时据此清扫上个进程遗留的容器，不误伤别人的容器。 */
    private static final String MANAGED_LABEL = "agent.sandbox.managed=true";

    /**
     * 归属标签：{@code <主机名>:<pid>}。
     *
     * <p>只有 {@link #MANAGED_LABEL} 无法回答"这个容器是不是孤儿"——多个平台实例
     * 共用同一个 Docker 守护进程时，启动即清扫会误杀别人正在跑的构建。
     * 记下 pid 后，启动清扫只在「同主机且该 pid 已不存在」时动手。
     */
    private static final String OWNER_LABEL = "agent.sandbox.owner";

    /** 本进程的归属标记：主机名:pid。进程重启后 pid 变化，旧容器就成了可回收的孤儿。 */
    private static final String OWNER_TAG = hostName() + ":" + ProcessHandle.current().pid();

    /** 清扫遗留容器时识别"本平台起的容器"的容器名前缀（与 {@link #beginContainer} 一致）。 */
    private static final String CONTAINER_PREFIX = "agent-act-";

    /** 外部命令执行器：抽出以便超时逻辑复用、也便于在不依赖 Docker 的情况下单测。 */
    @FunctionalInterface
    interface CommandExecutor {
        ProcessExecutor.StepOutcome run(List<String> cmd, long timeoutMs,
                                        BooleanSupplier cancelled, Consumer<String> live);
    }

    private final String dockerBinary;
    private final String image;
    private final String network;
    private final String memory;
    private final String cpus;
    private final int pidsLimit;
    private final String user;
    /** 依赖缓存卷前缀（每项目独立），为空则不启用缓存。 */
    private final String cacheVolumePrefix;
    /** 已初始化的卷（避免每次 docker run 都做一次 chown）。withImage 派生的沙箱共享同一份。 */
    private final Set<String> initializedVolumes;
    /**
     * 缓存卷初始化锁：按卷粒度而不是整个集合。
     *
     * <p>原先 {@code synchronized (initializedVolumes)} 把所有项目串成一条队列：
     * 一次冷启动建卷（mkdir + chown -R + 重试，最长约数分钟）会让其他项目已经初始化好的
     * 构建白等。按卷加锁后，不同项目并行，同卷（含 withImage 派生的沙箱）仍然串行。
     */
    private static final ConcurrentHashMap<String, Object> VOLUME_LOCKS = new ConcurrentHashMap<>();
    /** 当前线程正在运行的容器名（用于超时/取消后清理，避免 docker run 客户端被杀而容器残留）。 */
    private static final ThreadLocal<String> ACTIVE_CONTAINER = new ThreadLocal<>();
    private final CommandExecutor commandExecutor;
    /** 动作前是否预检工具链镜像（离线/自建 registry 场景可关闭）。 */
    private boolean precheckImage = true;
    /** 缺镜像时自动拉取的硬超时。 */
    private long imagePullTimeoutMs = DEFAULT_IMAGE_PULL_TIMEOUT_MS;
    /** npm registry 镜像：默认直连 npmjs 在国内沙箱只有几 KB/s，install 会吃光构建预算。 */
    private String npmRegistry = "";
    /** docker 运行时（如 gVisor 的 runsc）；空 = 默认 runc。 */
    private String runtime = "";
    /** 缓存卷初始化使用的可信镜像（只做 mkdir/chown/touch）。 */
    private String initImage = DEFAULT_INIT_IMAGE;

    public DockerSandbox(String dockerBinary, String image, String network,
                         String memory, String cpus, int pidsLimit, String user) {
        this(dockerBinary, image, network, memory, cpus, pidsLimit, user, null);
    }

    public DockerSandbox(String dockerBinary, String image, String network,
                         String memory, String cpus, int pidsLimit, String user,
                         String cacheVolumePrefix) {
        this(dockerBinary, image, network, memory, cpus, pidsLimit, user, cacheVolumePrefix,
                DockerSandbox::defaultExec);
    }

    /** 测试装配点：注入命令执行器，独立卷状态，不依赖 Docker 守护进程。 */
    DockerSandbox(String dockerBinary, String image, String network,
                  String memory, String cpus, int pidsLimit, String user,
                  String cacheVolumePrefix, CommandExecutor commandExecutor) {
        this(dockerBinary, image, network, memory, cpus, pidsLimit, user, cacheVolumePrefix,
                ConcurrentHashMap.newKeySet(), commandExecutor);
    }

    private DockerSandbox(String dockerBinary, String image, String network,
                          String memory, String cpus, int pidsLimit, String user,
                          String cacheVolumePrefix, Set<String> initializedVolumes,
                          CommandExecutor commandExecutor) {
        this.dockerBinary = dockerBinary == null ? "docker" : dockerBinary;
        // 镜像名最终是 docker run 的位置参数：以 - 开头会被当成选项（参数注入）。
        // 在装配处就校验，配置错了启动即失败，而不是等到某次构建才炸。
        this.image = ImageRef.require(image, "agent.sandbox.image");
        this.network = network == null ? "bridge" : network;
        this.memory = memory == null ? "2g" : memory;
        this.cpus = cpus == null ? "2" : cpus;
        this.pidsLimit = pidsLimit <= 0 ? 512 : pidsLimit;
        this.user = user == null ? "1000:1000" : user;
        this.cacheVolumePrefix = cacheVolumePrefix;
        this.initializedVolumes = initializedVolumes;
        this.commandExecutor = commandExecutor;
    }

    private static ProcessExecutor.StepOutcome defaultExec(List<String> cmd, long timeoutMs,
                                                           BooleanSupplier cancelled,
                                                           Consumer<String> live) {
        return new ProcessExecutor().exec(Path.of("."), cmd, timeoutMs, new StringBuilder(),
                cancelled, live);
    }

    /**
     * 构造 docker run 命令。
     *
     * <p><b>纯函数</b>：不再在这里启动任何进程。缓存卷的初始化由
     * {@link #prepareCacheVolume} 在动作开始前显式完成，否则命令构造这个
     * 看起来无副作用的方法会偷偷阻塞几分钟且绕过动作超时预算。
     *
     * @param workspace 宿主工作区（必须绝对路径）
     * @param argv      业务命令
     */
    public List<String> wrap(Path workspace, List<String> argv) {
        List<String> cmd = new ArrayList<>();
        // 1. 基础：docker run --rm，并清空镜像 ENTRYPOINT（argv 直执行，不套 shell）
        cmd.add(dockerBinary);
        cmd.add("run");
        cmd.add("--rm");
        // 1a. 生命周期标签 + 固定容器名：标签供启动清扫遗留容器，名字供超时后精确 rm -f
        cmd.add("--label");
        cmd.add(MANAGED_LABEL);
        cmd.add("--label");
        cmd.add(OWNER_LABEL + "=" + OWNER_TAG);
        // 1b. 可选运行时（gVisor runsc 等）：空则用默认 runc
        if (runtime != null && !runtime.isBlank()) {
            cmd.add("--runtime");
            cmd.add(runtime);
        }
        // 1c. 固定容器名：超时/取消时据此 docker rm -f，防止 CLI 被杀后容器成孤儿
        String containerName = ACTIVE_CONTAINER.get();
        if (containerName != null) {
            cmd.add("--name");
            cmd.add(containerName);
        }
        cmd.add("--entrypoint=");
        // 2. 网络档位（默认 bridge，可配 none 防外联）
        cmd.add("--network");
        cmd.add(network);
        // 3. 资源上限：内存 / CPU / 进程数
        cmd.add("--memory");
        cmd.add(memory);
        cmd.add("--cpus");
        cmd.add(cpus);
        cmd.add("--pids-limit");
        cmd.add(String.valueOf(pidsLimit));
        // 4. 降权：非 root + 禁提权 + 丢全部 Linux capabilities
        cmd.add("--user");
        cmd.add(user);
        cmd.add("--security-opt");
        cmd.add("no-new-privileges");
        cmd.add("--cap-drop");
        cmd.add("ALL");
        // 4b. 根文件系统只读：构建只需要写工作区与缓存卷（都已挂载），
        //     镜像层不落盘 → 投毒脚本改不了 /usr/local/bin 等位置，容器也不会被写胖。
        //     /tmp 必须可写且可执行，否则 JVM/pnpm 一启动就失败（见 TMPFS_TMP）。
        cmd.add("--read-only");
        cmd.add("--tmpfs");
        cmd.add(TMPFS_TMP);
        // 5. 挂载工作区到 /ws 并设为工作目录
        cmd.add("--workdir");
        cmd.add(CONTAINER_WORKDIR);
        cmd.add("-v");
        cmd.add(hostPath(workspace) + ":" + CONTAINER_WORKDIR);
        // 5b. 依赖缓存卷：Maven ~/.m2 与 npm cache 跨构建复用（按项目隔离）
        String name = cacheVolumeName(workspace);
        if (name != null) {
            cmd.add("-v");
            cmd.add(name + ":/cache");
            cmd.add("-e");
            cmd.add("MAVEN_OPTS=-Dmaven.repo.local=/cache/m2");
            cmd.add("-e");
            cmd.add("npm_config_cache=/cache/npm");
            // pnpm 的 store 默认在 <workdir>/node_modules/.pnpm-store：工作树一删就全量重下。
            // 必须显式指到缓存卷，否则 node_modules 复用永远是 0。
            cmd.add("-e");
            cmd.add("npm_config_store_dir=/cache/pnpm");
        }
        // 5b-2. npm 镜像源：国内直连 registry.npmjs.org 常见 2~40 KiB/s，
        //       冷安装会直接吃满前端阶段预算（E-302）。留空则沿用容器默认源。
        if (npmRegistry != null && !npmRegistry.isBlank()) {
            String registry = npmRegistry.trim();
            cmd.add("-e");
            cmd.add("npm_config_registry=" + registry);
            // Corepack 拉 pnpm 自身也走镜像（旧版本忽略该变量，无副作用）
            cmd.add("-e");
            cmd.add("COREPACK_NPM_REGISTRY=" + registry);
        }
        // 5c. Corepack 下载缓存：非 root 用户不能写 /usr/local/bin，
        //     用 COREPACK_HOME 指向可写目录；禁止交互式下载提示。
        cmd.add("-e");
        cmd.add("COREPACK_HOME=" + (name != null ? "/cache/corepack" : "/tmp/corepack"));
        cmd.add("-e");
        cmd.add("COREPACK_ENABLE_DOWNLOAD_PROMPT=0");
        // 5d. 根文件系统只读后 /home/<user> 不可写，但很多工具（git、npm、ssh、Gradle）
        //     会在 $HOME 下建目录：显式指到可写的 /tmp，否则报一堆莫名其妙的 EACCES。
        cmd.add("-e");
        cmd.add("HOME=/tmp");
        // 6. 镜像 + 业务 argv
        cmd.add(image);
        cmd.addAll(argv);
        return List.copyOf(cmd);
    }

    /** 宿主路径 → Docker 可识别的 POSIX 风格绝对路径（Windows 下把反斜杠换掉）。 */
    static String hostPath(Path workspace) {
        String absolute = workspace.toAbsolutePath().normalize().toString().replace('\\', '/');
        // Windows 下 E:\x → E:/x 即可被 Docker Desktop 接受；若以 / 开头说明已是 POSIX
        return absolute;
    }

    /**
     * 缓存卷的项目键：worktree 每次运行名字都不同（wt-build-run-xxx），
     * 用 worktree 目录名会让缓存永远命中不了；取 {@code <project>/worktrees/<wt>} 里的项目名。
     */
    static String cacheKey(Path workspace) {
        Path parent = workspace.getParent();
        if (parent != null && parent.getFileName() != null
                && "worktrees".equals(parent.getFileName().toString())
                && parent.getParent() != null && parent.getParent().getFileName() != null) {
            return parent.getParent().getFileName().toString();
        }
        return workspace.getFileName() == null ? "default" : workspace.getFileName().toString();
    }

    /** 卷名（未启用缓存时返回 null）。 */
    String cacheVolumeName(Path workspace) {
        return cacheVolumePrefix == null || cacheVolumePrefix.isBlank()
                ? null : safeVolumeName(cacheVolumePrefix + "-" + cacheKey(workspace));
    }

    /** 开始一次容器执行：生成容器名并绑定到当前线程，供超时清理使用。 */
    public String beginContainer() {
        String name = "agent-act-" + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12);
        ACTIVE_CONTAINER.set(name);
        return name;
    }

    /** 关闭/开启镜像预检（由 SandboxConfig 的配置注入）。 */
    public DockerSandbox setImagePrecheck(boolean enabled) {
        this.precheckImage = enabled;
        return this;
    }

    /** 设置缺镜像时自动拉取的硬超时（由 SandboxConfig 的配置注入）。 */
    public DockerSandbox setImagePullTimeout(long millis) {
        this.imagePullTimeoutMs = millis <= 0 ? DEFAULT_IMAGE_PULL_TIMEOUT_MS : millis;
        return this;
    }

    /** 设置 npm registry 镜像；空值表示用容器默认源。 */
    public DockerSandbox setNpmRegistry(String registry) {
        this.npmRegistry = registry == null ? "" : registry.trim();
        return this;
    }

    /** 设置 docker 运行时（gVisor runsc 等）；空值回落到默认 runc。 */
    public DockerSandbox setRuntime(String runtime) {
        this.runtime = runtime == null ? "" : runtime.trim();
        return this;
    }

    /** 设置缓存卷初始化的可信镜像；空值回落到默认最小镜像。 */
    public DockerSandbox setInitImage(String initImage) {
        this.initImage = initImage == null || initImage.isBlank() ? DEFAULT_INIT_IMAGE : initImage.trim();
        return this;
    }

    /**
     * 动作前确认工具链镜像已在本机；缺失时先自动拉取，再复核。
     *
     * <p>原先缺镜像直接失败、要求人工 {@code docker pull}，等于把确定性故障留给人；
     * 现在由平台自己完成「pull → inspect 复核」，pull 有硬超时且可取消，失败才报错。
     * 不复用日志/模型提供的镜像名：拉取命令只来自平台配置。
     */
    public void ensureImageReady(BooleanSupplier cancelled, Consumer<String> live) {
        if (!precheckImage) {
            return;
        }
        BooleanSupplier abort = cancelled == null ? () -> false : cancelled;
        List<String> inspect = List.of(dockerBinary, "image", "inspect", image);
        // 不要把 inspect 的完整 JSON 流进运行日志：70+ 行会把真正的失败原因挤出截断窗口，
        // 只在这里静默判断"镜像是否存在"
        ProcessExecutor.StepOutcome outcome = commandExecutor.run(inspect,
                IMAGE_INSPECT_TIMEOUT_MS, abort, null);
        if (outcome.errorCode() == null) {
            return;
        }
        if (abort.getAsBoolean()) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "构建已取消：跳过自动拉取镜像 " + image);
        }
        notify(live, "[sandbox] 镜像 " + image + " 不在本机，自动执行 "
                + String.join(" ", imagePullCommand()) + "（最长 "
                + imagePullTimeoutMs / 1000 + "s，可取消）\n");
        ProcessExecutor.StepOutcome pull = commandExecutor.run(imagePullCommand(),
                imagePullTimeoutMs, abort, live);
        if (pull.errorCode() != null) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "构建镜像未就绪（" + image + "）：自动拉取失败（"
                    + pull.errorCode() + "：" + pull.summary()
                    + "）；请检查 Docker 网络/registry，或手动 docker pull " + image
                    + "，或用 agent.sandbox.image 指定本机已有镜像");
        }
        // 只信平台自己执行的 inspect，不采信 pull 的退出码或输出文本
        ProcessExecutor.StepOutcome verify = commandExecutor.run(inspect,
                IMAGE_INSPECT_TIMEOUT_MS, abort, null);
        if (verify.errorCode() != null) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "构建镜像未就绪（" + image + "）：拉取后仍无法通过 docker image inspect"
                    + "（" + verify.errorCode() + "：" + verify.summary() + "）");
        }
        notify(live, "[sandbox] ✅ 镜像已就绪：" + image + "\n");
    }

    /**
     * 动作开始前准备依赖缓存卷：用特权容器建目录并 chown，幂等且只做一次。
     *
     * <p>每一步都有 90s 超时与取消检查；失败时抛异常且**不**把卷标记为已初始化，
     * 避免后续构建误用一个属主还是 root 的缓存卷。异常信息会进入运行日志。
     *
     * <p>瞬时超时（冷镜像/磁盘抖动）先自动重试一次；卷里写了完成标记，
     * 进程重启后直接跳过 chown -R，不再为一次构建重扫整个 m2 缓存。
     */
    public void prepareCacheVolume(Path workspace, BooleanSupplier cancelled, Consumer<String> live) {
        String volume = cacheVolumeName(workspace);
        if (volume == null || initializedVolumes.contains(volume)) {
            return;
        }
        // 按卷加锁：不同项目互不阻塞，同卷（含 withImage 派生的沙箱）仍需串行初始化
        Object lock = VOLUME_LOCKS.computeIfAbsent(volume, key -> new Object());
        synchronized (lock) {
            if (initializedVolumes.contains(volume)) {
                return;
            }
            if (!cacheReady(volume, cancelled)) {
                notify(live, "[sandbox] 首次初始化依赖缓存卷 " + volume
                        + "（每步 90s 超时，可取消）\n");
                initCacheVolume(volume, cancelled, live);
                notify(live, "[sandbox] ✅ 依赖缓存卷已就绪：" + volume + "\n");
            }
            initializedVolumes.add(volume);
        }
    }

    /** 完成标记探测：容器启动失败或读不到标记都按"未初始化"处理，交给初始化流程报错。 */
    private boolean cacheReady(String volume, BooleanSupplier cancelled) {
        ProcessExecutor.StepOutcome outcome = commandExecutor.run(
                rootCommand(volume, "test", "-f", READY_MARKER), CACHE_INIT_TIMEOUT_MS,
                cancelled == null ? () -> false : cancelled, null);
        return outcome.exitCode() == 0;
    }

    /** 建目录 + 校正属主 + 写完成标记：任一步超时先自动重试，仍失败才抛给调用方。 */
    private void initCacheVolume(String volume, BooleanSupplier cancelled, Consumer<String> live) {
        for (int attempt = 1; attempt <= CACHE_INIT_ATTEMPTS; attempt++) {
            try {
                runInit(volume, cancelled, live, "mkdir", "-p",
                        "/cache/m2", "/cache/npm", "/cache/pnpm", "/cache/corepack");
                chmodWorldWritable(volume, cancelled, live);
                chownBestEffort(volume, cancelled, live);
                runInit(volume, cancelled, live, "touch", READY_MARKER);
                return;
            } catch (EnvironmentException e) {
                boolean aborted = cancelled != null && cancelled.getAsBoolean();
                if (attempt >= CACHE_INIT_ATTEMPTS || aborted) {
                    throw e;
                }
                notify(live, "[sandbox] 缓存卷初始化失败，自动重试（"
                        + (attempt + 1) + "/" + CACHE_INIT_ATTEMPTS + "）\n");
            }
        }
    }

    /**
     * 目录放权（root 执行）：确保构建用户（1000）可写。
     *
     * <p>为什么不能只靠 chown：Windows 的 Docker Desktop 卷（WSL2/9p）不支持 chown，
     * root 建出的目录属主停在 root:root 0755，uid 1000 写 {@code /cache/m2} 直接
     * Permission denied（Docker 29 实测，构建阶段 E-101 秒败）。chmod 不走 9p 属主
     * 语义，root 对自属目录放权 777 稳定生效；构建进程以 1000 运行，能写即可。
     */
    private void chmodWorldWritable(String volume, BooleanSupplier cancelled, Consumer<String> live) {
        List<String> cmd = initCommand(volume, "0:0", "chmod", "777",
                "/cache", "/cache/m2", "/cache/npm", "/cache/pnpm", "/cache/corepack");
        notify(live, "[sandbox] $ " + String.join(" ", cmd) + "\n");
        ProcessExecutor.StepOutcome outcome = commandExecutor.run(cmd, CACHE_INIT_TIMEOUT_MS,
                cancelled == null ? () -> false : cancelled, live);
        if (outcome.exitCode() != 0) {
            notify(live, "[sandbox] 目录放权未生效（exit " + outcome.exitCode()
                    + "），继续初始化（若构建仍报 Permission denied 需检查卷驱动）\n");
        }
    }

    /**
     * 缓存卷初始化命令：先按构建用户执行，被拒再回退 root。
     *
     * <p>为什么不能只用 root：Docker Desktop（Windows/WSL2）对已有卷做 userns 映射时，
     * 容器内 root 反而写不了属主为 1000 的目录（Permission denied）；而全新卷的根目录
     * 属主是 root，1000 用户又写不了。两种卷都要能初始化，所以按"1000 → root"回退。
     * 只对命令被拒（E-101）回退，超时/中断直接抛出，避免双倍等待。
     */
    private void runInit(String volume, BooleanSupplier cancelled, Consumer<String> live,
                         String... argv) {
        ProcessExecutor.StepOutcome outcome =
                execAs(volume, CACHE_UID, CACHE_INIT_TIMEOUT_MS, cancelled, live, argv);
        if (outcome.errorCode() == null) {
            return;
        }
        // 只有"命令被执行器拒绝/非零退出"才值得换 root 再试；超时与取消是基础设施问题。
        if (!ErrorCode.COMPILE_FAILED.code().equals(outcome.errorCode())) {
            throw initFailure(outcome, volume, argv);
        }
        ProcessExecutor.StepOutcome asRoot =
                execAs(volume, "0:0", CACHE_INIT_TIMEOUT_MS, cancelled, live, argv);
        if (asRoot.errorCode() != null) {
            throw initFailure(asRoot, volume, argv);
        }
    }

    private EnvironmentException initFailure(ProcessExecutor.StepOutcome outcome, String volume,
                                             String... argv) {
        return new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                "缓存卷初始化失败（" + outcome.errorCode() + "，卷 " + volume + "）："
                        + outcome.summary() + "；命令：" + String.join(" ", argv));
    }

    /**
     * 属主校正只是"尽力而为"：Windows 的 Docker Desktop 卷不支持 chown
     * （会报 Operation not permitted），而缓存卷的可用性由 mkdir 与完成标记保证。
     * 这里失败只记日志，否则整个流水线会被一个无实际影响的步骤卡死。
     */
    private void chownBestEffort(String volume, BooleanSupplier cancelled, Consumer<String> live) {
        List<String> cmd = initCommand(volume, "0:0", "chown", "-R", "1000:1000", "/cache");
        notify(live, "[sandbox] $ " + String.join(" ", cmd) + "\n");
        ProcessExecutor.StepOutcome outcome = commandExecutor.run(cmd, CACHE_INIT_TIMEOUT_MS,
                cancelled == null ? () -> false : cancelled, live);
        if (outcome.exitCode() != 0) {
            notify(live, "[sandbox] 属主校正未生效（exit " + outcome.exitCode()
                    + "，Windows 卷不支持 chown 属正常），继续初始化\n");
        }
    }

    private ProcessExecutor.StepOutcome execAs(String volume, String user, long timeoutMs,
                                               BooleanSupplier cancelled, Consumer<String> live,
                                               String... argv) {
        List<String> cmd = initCommand(volume, user, argv);
        notify(live, "[sandbox] $ " + String.join(" ", cmd) + "\n");
        return commandExecutor.run(cmd, timeoutMs, cancelled == null ? () -> false : cancelled, live);
    }

    /** 特权容器命令行：只用于缓存卷初始化（需要 root 才能建目录 / chown）。 */
    private List<String> rootCommand(String volume, String... argv) {
        return initCommand(volume, "0:0", argv);
    }

    private List<String> initCommand(String volume, String user, String... argv) {
        List<String> cmd = new ArrayList<>();
        cmd.add(dockerBinary);
        cmd.add("run");
        cmd.add("--rm");
        if (runtime != null && !runtime.isBlank()) {
            cmd.add("--runtime");
            cmd.add(runtime);
        }
        cmd.add("--entrypoint=");
        // 初始化容器也一样「不可提权 + 无外联 + 资源受限」：root 只用于 chown 卷属主，
        // 不能带着默认 capabilities / 网络去跑一个（可能是不可信的）构建镜像。
        cmd.add("--network");
        cmd.add("none");
        cmd.add("--cap-drop");
        cmd.add("ALL");
        cmd.add("--security-opt");
        cmd.add("no-new-privileges");
        cmd.add("--pids-limit");
        cmd.add("64");
        cmd.add("--memory");
        cmd.add(memory);
        cmd.add("--user");
        cmd.add(user);
        cmd.add("-v");
        cmd.add(volume + ":/cache");
        // 用可信最小镜像初始化，绝不用构建镜像（构建镜像可能被投毒，root 下直接接管宿主 Docker）
        cmd.add(initImage);
        cmd.addAll(List.of(argv));
        return cmd;
    }

    private static void notify(Consumer<String> live, String text) {
        if (live != null) {
            live.accept(text);
        }
    }

    /** 结束容器执行：无论成功、失败还是超时都清理容器，避免孤儿进程占端口/内存。 */
    public void endContainer(String name) {
        ACTIVE_CONTAINER.remove();
        if (name == null || name.isBlank()) {
            return;
        }
        // 清理不可被取消跳过（否则取消运行会留下容器），但仍受 30s 超时保护：
        // 原先 readAllBytes() 在 waitFor 之前，docker CLI 不退出就会永久卡住收尾。
        commandExecutor.run(List.of(dockerBinary, "rm", "-f", name), PREPARE_TIMEOUT_MS,
                () -> false, null);
    }

    /**
     * 启动清扫：删掉上一个平台进程遗留的构建容器。
     *
     * <p>只靠 {@code --rm} 不够：动作超时或进程被 kill 时 docker CLI 先死，
     * 容器会带着固定名字继续跑并占住内存/CPU，下次启动还可能因重名而失败。
     *
     * <p>清扫范围严格受限，绝不误杀其他实例正在跑的构建：
     * <ul>
     *   <li>必须带 {@link #MANAGED_LABEL}（平台自己起的）；</li>
     *   <li>{@link #OWNER_LABEL} 记录的主机名与本机一致；</li>
     *   <li>该 pid 已不存在（旧进程确实死了）。</li>
     * </ul>
     * 归属信息缺失的容器（旧版本留下的）只记日志不删除——无法判断是否仍有人在使用。
     *
     * @return 实际删除的容器名（空 = 没有需要清扫的）
     */
    public List<String> sweepStaleContainers(Consumer<String> log) {
        BoundedCommand.Outcome listed = BoundedCommand.run(
                List.of(dockerBinary, "ps", "-a", "--filter", "label=" + MANAGED_LABEL,
                        "--format", "{{.Names}}|{{.Label \"" + OWNER_LABEL + "\"}}"),
                15_000L);
        if (!listed.ok()) {
            notify(log, "[sandbox] 遗留容器清扫跳过：docker ps 失败（"
                    + listed.detail() + "）\n");
            return List.of();
        }
        List<String> removed = new ArrayList<>();
        for (String name : staleCandidates(listed.output(), hostName(), DockerSandbox::processAlive)) {
            commandExecutor.run(List.of(dockerBinary, "rm", "-f", name), PREPARE_TIMEOUT_MS,
                    () -> false, null);
            removed.add(name);
            notify(log, "[sandbox] 已清理遗留容器 " + name + "（原进程已退出）\n");
        }
        return List.copyOf(removed);
    }

    /**
     * 从 {@code docker ps} 的输出里挑出可以安全删除的遗留容器（纯函数，便于单测）。
     *
     * <p>每条记录形如 {@code agent-act-xxx|host:pid}。只有同时满足「平台前缀 + 本机 + pid 已退出」
     * 才返回；归属信息缺失的（旧版本容器）不删，因为无法判断是否仍有人在使用。
     *
     * @param alive pid 是否存活（注入以便测试）
     */
    static List<String> staleCandidates(String dockerPsOutput, String host, LongPredicate alive) {
        if (dockerPsOutput == null || dockerPsOutput.isBlank()) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        for (String raw : dockerPsOutput.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            int sep = line.indexOf('|');
            String name = sep < 0 ? line : line.substring(0, sep).trim();
            String owner = sep < 0 ? "" : line.substring(sep + 1).trim();
            if (!name.startsWith(CONTAINER_PREFIX) || owner.isBlank()) {
                continue;
            }
            if (!owner.startsWith(host + ":")) {
                continue;
            }
            String pid = owner.substring(host.length() + 1);
            try {
                if (alive.test(Long.parseLong(pid.trim()))) {
                    continue;
                }
            } catch (NumberFormatException e) {
                continue; // 归属信息损坏时不冒险删除
            }
            candidates.add(name);
        }
        return List.copyOf(candidates);
    }

    /** 镜像指纹缓存：同一镜像在同一进程内不会变，只有 withImage 派生的沙箱需要重新取。 */
    private volatile String resolvedImageId;

    /**
     * 镜像指纹：{@code docker image inspect --format {{.Id}}} 的 sha256。
     *
     * <p>只记 tag 无法回答"这次构建到底用了哪份镜像"——同名 tag 被重新 pull 后
     * 两次构建的行为可以完全不同，排障时必须有可对照的指纹。
     *
     * @return 形如 {@code sha256:…}；取不到时返回空串（日志缺一项，不影响构建）
     */
    public String imageId(BooleanSupplier cancelled) {
        String cached = resolvedImageId;
        if (cached != null) {
            return cached;
        }
        StringBuilder out = new StringBuilder();
        ProcessExecutor.StepOutcome outcome = commandExecutor.run(
                List.of(dockerBinary, "image", "inspect", "--format", "{{.Id}}", image),
                IMAGE_INSPECT_TIMEOUT_MS, cancelled == null ? () -> false : cancelled,
                out::append);
        if (outcome.exitCode() != 0) {
            return "";
        }
        String id = out.toString().trim();
        resolvedImageId = id;
        return id;
    }

    private static String hostName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) {
            name = System.getenv("HOSTNAME");
        }
        if (name == null || name.isBlank()) {
            try {
                name = java.net.InetAddress.getLocalHost().getHostName();
            } catch (java.net.UnknownHostException e) {
                name = "unknown";
            }
        }
        return name.trim();
    }

    /** pid 是否仍存活；解析失败在 {@link #staleCandidates} 里按"已退出"处理。 */
    private static boolean processAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** 卷名只允许小写字母/数字/_-，避免非法项目名注入 docker 参数。 */
    static String safeVolumeName(String raw) {
        String normalized = raw.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
        return normalized.length() > 60 ? normalized.substring(0, 60) : normalized;
    }

    /** 守护进程是否可用（3 秒硬超时 + 并行排空输出，避免启动被拖住）。 */
    public boolean available() {
        return BoundedCommand.run(
                List.of(dockerBinary, "info", "--format", "{{.ServerVersion}}"), 3_000L).ok();
    }

    public String image() {
        return image;
    }

    /** 自动修复用的拉取命令：镜像只来自平台配置，不接受日志/模型提供的参数。 */
    public List<String> imagePullCommand() {
        return List.of(dockerBinary, "pull", image);
    }

    /** 自动修复用的复核命令：修复是否生效只由平台自己执行的 inspect 判定。 */
    public List<String> imageInspectCommand() {
        return List.of(dockerBinary, "image", "inspect", image);
    }

    /** 复制一份沙箱，只替换镜像（其余隔离参数与缓存卷不变）。 */
    public DockerSandbox withImage(String newImage) {
        // stage.image 来自仓库里的流水线定义，是外部输入：校验与基础镜像完全一致
        DockerSandbox copy = new DockerSandbox(dockerBinary,
                ImageRef.require(newImage, "stage.image"), network, memory, cpus,
                pidsLimit, user, cacheVolumePrefix, initializedVolumes, commandExecutor);
        return copy.setImagePrecheck(precheckImage).setImagePullTimeout(imagePullTimeoutMs)
                .setNpmRegistry(npmRegistry).setRuntime(runtime).setInitImage(initImage);
    }

    public String describe() {
        return "docker image=" + image + " network=" + network
                + " user=" + user + " mem=" + memory + " cpus=" + cpus;
    }

    /**
     * 当前生效的隔离参数快照（结构化，供运行日志、拒绝信息与设置页展示）。
     *
     * <p>为什么要有快照：出问题时第一句话永远是「这次到底隔离了什么」。
     * 散落在 {@link #wrap} 里的参数无法回答，用户只能去翻配置。
     */
    public List<String> isolationSnapshot() {
        List<String> snapshot = new ArrayList<>();
        snapshot.add("runtime=" + (runtime == null || runtime.isBlank() ? "runc" : runtime));
        snapshot.add("network=" + network);
        snapshot.add("user=" + user);
        snapshot.add("memory=" + memory);
        snapshot.add("cpus=" + cpus);
        snapshot.add("pids=" + pidsLimit);
        snapshot.add("cap-drop=ALL");
        snapshot.add("no-new-privileges");
        snapshot.add("read-only-rootfs");
        snapshot.add("tmpfs=/tmp:rw,exec");
        snapshot.add("mounts=/ws,+cache");
        return List.copyOf(snapshot);
    }

    /** 一次性写入快照到运行日志（有 live 消费者时）。 */
    public void logIsolation(Consumer<String> live) {
        notify(live, "[sandbox] 隔离参数 " + String.join(" ", isolationSnapshot()) + "\n");
    }

    /**
     * prepare 阶段的网络就绪保证（BUG-35）：
     * {@code agent.sandbox.network} 配置成自定义网络（如 build-net）时先确保存在，
     * 否则构建会在 {@code docker run --network} 处以 {@code network not found} 失败
     * 且被误归因为代码问题。bridge/host/none 是内置网络，跳过。
     *
     * @throws EnvironmentException 网络检查/创建失败（错误信息含 {@code docker network create} 可复制命令）
     */
    public void prepareNetwork(BooleanSupplier cancelled, Consumer<String> live) {
        if ("bridge".equalsIgnoreCase(network) || "host".equalsIgnoreCase(network)
                || "none".equalsIgnoreCase(network) || network == null || network.isBlank()) {
            return;
        }
        StringBuilder log = new StringBuilder();
        DockerCli cli = new DockerCli(dockerBinary);
        // 走注入的 commandExecutor：单测可用 fake runner 断言 inspect → create 序列
        DockerNetwork.Exec exec = (cmd, timeoutMs, abort, out) ->
                commandExecutor.run(cmd, timeoutMs, abort == null ? () -> false : abort, out);
        boolean ready = DockerNetwork.ensure(exec, cli, Path.of("."), network, log);
        if (!ready) {
            throw new EnvironmentException(ErrorCode.ENV_PREPARE_FAILED,
                    "沙箱网络未就绪（agent.sandbox.network=" + network + "）："
                            + "自动创建失败——" + ProcessExecutor.redact(log.toString())
                            + "。请手动执行 docker network create " + network
                            + " 后重试，或改用 bridge 档位");
        }
        notify(live, "[sandbox] ✅ 沙箱网络已就绪：" + network + "\n");
    }

    /**
     * 网络档位校验：声明要外联但策略把网络关掉时必须拒绝，而不是静默让它失败。
     *
     * <p>典型误用：流水线定义里写了「需要下载依赖」，平台却配了
     * {@code agent.sandbox.network=none}；不在这里拒绝的话，构建跑几十秒后死于
     * 一句 {@code Could not resolve host}，没人知道是策略而不是代码的问题。
     *
     * <p>修复建议动态生成（BUG-35）：不再硬编码推荐 {@code build-net}——
     * 只有该网络确实存在时才推荐，否则引导先创建，避免"按文档配置即炸"。
     *
     * @param needsNetwork 该动作是否声明需要外联
     * @throws SandboxDeniedException 策略不允许外联但动作要求外联
     */
    public void requireNetworkFor(boolean needsNetwork, String stageName) {
        if (!needsNetwork || !"none".equalsIgnoreCase(network)) {
            return;
        }
        throw new SandboxDeniedException(SandboxDeniedException.Rule.NETWORK,
                "阶段 " + stageName + " 需要外联，但沙箱网络档位是 none",
                isolationSnapshot(),
                networkSuggestion());
    }

    /** 动态生成网络档位建议：内置网络直接推荐；自定义网络先确认存在再推荐。 */
    private String networkSuggestion() {
        // bridge 永远存在，直接推荐；自定义网络（build-net 等）由 prepareNetwork 在
        // prepare 阶段保证过存在，这里只推荐"换档位"而不是凭空点名一个不存在的网络。
        return "改用 agent.sandbox.network=bridge（平台会自动确保网络就绪），"
                + "或把该阶段改成只用已预热缓存的动作；"
                + "确需专用网络时先执行 docker network create build-net";
    }

    /**
     * 挂载校验：只允许工作区与缓存卷，其余宿主路径一律拒绝。
     *
     * <p>挂载是沙箱边界上最危险的一项（{@code -v /var/run/docker.sock} 等于交出宿主），
     * 因此不允许任何来自流水线定义/模型的额外挂载。
     *
     * @param requestedMounts 调用方想追加的宿主路径
     * @throws SandboxDeniedException 出现工作区/缓存卷之外的挂载
     */
    public void requireNoExtraMounts(List<String> requestedMounts) {
        if (requestedMounts == null || requestedMounts.isEmpty()) {
            return;
        }
        throw new SandboxDeniedException(SandboxDeniedException.Rule.MOUNT,
                "不允许追加宿主挂载：" + String.join(", ", requestedMounts),
                isolationSnapshot(),
                "把需要的文件放进工作区（它已挂到 /ws），或用缓存卷 /cache；"
                        + "宿主目录与 docker.sock 永不挂载");
    }

    /**
     * 特权校验：特权模式、额外 capability、关闭 no-new-privileges 一律拒绝。
     *
     * @param requested 调用方请求的越权项（如 {@code --privileged}）
     * @throws SandboxDeniedException 请求里出现任何提升权限的项
     */
    public void requireUnprivileged(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return;
        }
        throw new SandboxDeniedException(SandboxDeniedException.Rule.PRIVILEGE,
                "不允许提升容器权限：" + String.join(", ", requested),
                isolationSnapshot(),
                "构建与部署都不需要特权；确需特殊能力时改平台配置（需人工评审），"
                        + "不要写在流水线定义里");
    }

    /**
     * 运行时校验：配置了 gVisor 等运行时但守护进程不支持时必须明确拒绝。
     *
     * <p>静默回落到 runc 会让「以为跑在 gVisor 里」这种最危险的误判发生：
     * 不可信代码其实仍然跑在普通 runc 上。
     *
     * @param availableRuntimes {@code docker info} 报告的可信运行时列表（空表示未知）
     * @throws SandboxDeniedException 要求的运行时不可用
     */
    public void requireRuntimeAvailable(List<String> availableRuntimes) {
        if (runtime == null || runtime.isBlank()) {
            return;
        }
        if (availableRuntimes == null || availableRuntimes.isEmpty()) {
            // 无法探测时不做断言：交给动作执行时的 docker run 显式失败，避免误拦
            return;
        }
        if (availableRuntimes.stream().anyMatch(r -> r.equalsIgnoreCase(runtime))) {
            return;
        }
        throw new SandboxDeniedException(SandboxDeniedException.Rule.RUNTIME,
                "要求运行时 " + runtime + "，但守护进程只报告 " + String.join(", ", availableRuntimes),
                isolationSnapshot(),
                "安装并注册该运行时（如 gVisor 的 runsc），或把 agent.sandbox.runtime 置空以使用 runc");
    }
}
