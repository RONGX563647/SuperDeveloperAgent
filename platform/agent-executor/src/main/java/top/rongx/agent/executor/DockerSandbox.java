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
import top.rongx.agent.domain.image.ImageReadinessStore;

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

    /** 自动拉取工具链镜像的总时长上限（可由 agent.sandbox.pull-timeout-seconds 覆盖）。 */
    private static final long DEFAULT_IMAGE_PULL_TIMEOUT_MS = 900_000L;

    /**
     * 拉取静默多久判定为卡死（可由 agent.sandbox.pull-idle-timeout-seconds 覆盖）。
     *
     * <p>拉取是「慢但一直在推进」的长任务：固定总预算会把「层已下完、正在解压」误判成
     * 网络故障——实测 node:22-bookworm 在构建并行、磁盘被 chown 抢占时 180s 只够下完所有层，
     * 随后被砍掉并报 E-302。判据改成「有没有新输出」而不是「一共跑了多久」。
     */
    private static final long DEFAULT_IMAGE_PULL_IDLE_MS = 60_000L;

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

    /** 初始化容器的名字前缀：同样以 {@link #CONTAINER_PREFIX} 开头，清扫逻辑一并覆盖。 */
    private static final String INIT_PREFIX = CONTAINER_PREFIX + "init-";

    /** 外部命令执行器：抽出以便超时逻辑复用、也便于在不依赖 Docker 的情况下单测。 */
    @FunctionalInterface
    interface CommandExecutor {
        ProcessExecutor.StepOutcome run(List<String> cmd, long timeoutMs,
                                        BooleanSupplier cancelled, Consumer<String> live);

        /**
         * 进度感知执行（自动拉镜像用）：只要还有输出就继续，静默 {@code idleMs} 才终止。
         *
         * <p>默认按总预算退回固定语义，测试里的假执行器不必改；生产实现见
         * {@link #DEFAULT_EXECUTOR}。
         */
        default ProcessExecutor.StepOutcome runUntilIdle(List<String> cmd, long idleMs, long totalMs,
                                                         BooleanSupplier cancelled,
                                                         Consumer<String> live) {
            return run(cmd, totalMs > 0 ? totalMs : idleMs, cancelled, live);
        }
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
    /** 缺镜像时自动拉取：总上限只在「一直有进度」时兜底，真正判定卡死的是静默超时。 */
    private long imagePullTimeoutMs = DEFAULT_IMAGE_PULL_TIMEOUT_MS;
    /** 拉取静默多久判定卡死。 */
    private long imagePullIdleMs = DEFAULT_IMAGE_PULL_IDLE_MS;
    /** 镜像加速源/缓存源（registry 前缀，如 docker.m.daocloud.io、localhost:5000）：直连失败时按序回退。 */
    private List<String> pullMirrors = List.of();
    /** 直连拉取的自动重试次数（只对"值得再试"的失败重试：鉴权/不存在/磁盘满不重试）。 */
    private int imagePullAttempts = 2;
    /** 失败退避：下一次可重试时间按指数增长，避免每次构建都白等一轮超时。 */
    private long pullBackoffBaseMs = 60_000L;
    private long pullBackoffMaxMs = 900_000L;
    /** 镜像就绪状态（跨运行复用）；null = 只判断本次，不落库。 */
    private ImageReadinessStore readinessStore;
    /** Docker 守护所在主机：就绪状态按主机隔离（镜像层缓存属于这台机器）。 */
    private String host = defaultHost();
    /** npm registry 镜像：默认直连 npmjs 在国内沙箱只有几 KB/s，install 会吃光构建预算。 */
    private String npmRegistry = "";
    /** docker 运行时（如 gVisor 的 runsc）；空 = 默认 runc。 */
    private String runtime = "";
    /** 缓存卷初始化使用的可信镜像（只做 mkdir/chown/touch）。 */
    private String initImage = DEFAULT_INIT_IMAGE;

    /**
     * 镜像准入策略（允许列表 / 浮动 tag）。
     *
     * <p>默认 {@link SandboxImagePolicy#permissive()}：引入本字段不改变任何现有行为，
     * 收紧靠 {@code agent.sandbox.image-allowlist} 与 {@code agent.sandbox.allow-floating-tags}。
     */
    private SandboxImagePolicy imagePolicy = SandboxImagePolicy.permissive();

    /** 离线逃生门：{@code network=none} 是否需要按"必须外联"拒绝（默认拒绝）。 */
    private boolean allowOffline;

    /**
     * 守护进程报告的可用运行时（{@code docker info} 结论，探测一次后缓存）。
     *
     * <p>{@code null} = 尚未探测；空列表 = 探测过但拿不到结论（按"未知"放行）。
     */
    private List<String> availableRuntimes;

    public DockerSandbox(String dockerBinary, String image, String network,
                         String memory, String cpus, int pidsLimit, String user) {
        this(dockerBinary, image, network, memory, cpus, pidsLimit, user, null);
    }

    public DockerSandbox(String dockerBinary, String image, String network,
                         String memory, String cpus, int pidsLimit, String user,
                         String cacheVolumePrefix) {
        this(dockerBinary, image, network, memory, cpus, pidsLimit, user, cacheVolumePrefix,
                DEFAULT_EXECUTOR);
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

    /** 生产执行器：普通命令走固定预算，拉镜像走进度感知（有输出就继续）。 */
    private static final CommandExecutor DEFAULT_EXECUTOR = new CommandExecutor() {
        @Override
        public ProcessExecutor.StepOutcome run(List<String> cmd, long timeoutMs,
                                               BooleanSupplier cancelled, Consumer<String> live) {
            return defaultExec(cmd, timeoutMs, cancelled, live);
        }

        @Override
        public ProcessExecutor.StepOutcome runUntilIdle(List<String> cmd, long idleMs, long totalMs,
                                                        BooleanSupplier cancelled,
                                                        Consumer<String> live) {
            return new ProcessExecutor().execUntilIdle(Path.of("."), cmd, idleMs, totalMs,
                    new StringBuilder(), cancelled, live);
        }
    };

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
        // 3b. pid 1 必须是会回收僵尸的 init：镜像自带的 ENTRYPOINT 已被清空，业务进程
        //     直接当 pid 1，不会 wait 子进程——pnpm/maven 这类会 fork 大量短命子进程的工具
        //     在 pids-limit 下会真的撞上限（表现为莫名其妙的 "Resource temporarily unavailable"）。
        cmd.add("--init");
        // 3c. 停止宽限与 core dump：默认 10s 会在构建脚本写盘时被 SIGKILL 打断缓存卷；
        //     core dump 对构建容器没有价值，只会往只读根文件系统外写大文件。
        cmd.add("--stop-timeout");
        cmd.add("10");
        cmd.add("--ulimit");
        cmd.add("core=0");
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
     * 缓存卷的项目键：<b>可读前缀 + 规范化项目根路径的哈希</b>。
     *
     * <p>原实现只取目录名（worktree 时取所属项目名），等于假设"目录名唯一"。三种真实撞名：
     * 同名的两份仓库拷贝、被 {@link #safeVolumeName} 60 字符截断的长名字、以及不同父目录下
     * 的同名工程——它们会落到同一个卷上，A 项目投毒的 {@code ~/.m2} 会被 B 项目解析。
     *
     * <p>现在读起来仍然是项目名（便于 {@code docker volume ls} 排障），但末尾带 16 位
     * 路径哈希作为唯一性来源：不同绝对路径必然不同卷，同一路径跨运行稳定命中。
     */
    static String cacheKey(Path workspace) {
        Path root = workspace.toAbsolutePath().normalize();
        Path parent = root.getParent();
        // worktree 每次运行名字都不同（wt-build-run-xxx），键要取所属项目根，否则缓存永远不命中
        if (parent != null && "worktrees".equals(fileName(parent)) && parent.getParent() != null) {
            root = parent.getParent();
        }
        String readable = safeVolumeName(fileName(root));
        if (readable.length() > 24) {
            readable = readable.substring(0, 24);
        }
        return readable + "-" + pathHash(root);
    }

    /** 目录名（null 安全）。 */
    private static String fileName(Path path) {
        Path name = path == null ? null : path.getFileName();
        return name == null ? "default" : name.toString();
    }

    /** 路径的稳定指纹：SHA-256 前 16 位小写 hex（碰撞概率足够低，且不会像截断那样丢熵）。 */
    private static String pathHash(Path root) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(md.digest(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            // 平台只跑在 JDK 21 上，SHA-256 必然存在；真缺失时退回路径 hashCode，
            // 宁可退化也不能让整个构建因为缓存卷命名而失败。
            return Integer.toHexString(root.toString().hashCode());
        }
    }

    /** 卷名（未启用缓存时返回 null）。 */
    String cacheVolumeName(Path workspace) {
        return cacheVolumePrefix == null || cacheVolumePrefix.isBlank()
                ? null : safeVolumeName(cacheVolumePrefix + "-" + cacheKey(workspace));
    }

    /** 开始一次容器执行：生成容器名并绑定到当前线程，供超时清理使用。 */
    public String beginContainer() {
        String name = CONTAINER_PREFIX + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12);
        ACTIVE_CONTAINER.set(name);
        return name;
    }

    /**
     * 初始化容器名：按卷分区（同一卷的两次初始化串行，不同卷互不干扰），带 8 位随机后缀。
     *
     * <p>必须唯一：{@code docker run} 遇到重名会直接失败，而初始化失败会被当成
     * "缓存卷不可用"导致构建失败。
     */
    private static String initContainerName(String volume) {
        String base = safeVolumeName(volume == null || volume.isBlank() ? "default" : volume);
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        return INIT_PREFIX + base + "-" + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8);
    }

    /** 是否为平台自己起、可被清扫的容器名（活动容器与初始化容器都算）。 */
    static boolean isManagedContainerName(String name) {
        return name != null && name.startsWith(CONTAINER_PREFIX);
    }

    /** 关闭/开启镜像预检（由 SandboxConfig 的配置注入）。 */
    public DockerSandbox setImagePrecheck(boolean enabled) {
        this.precheckImage = enabled;
        return this;
    }

    /** 设置缺镜像时自动拉取的总时长上限（由 SandboxConfig 的配置注入）。 */
    public DockerSandbox setImagePullTimeout(long millis) {
        this.imagePullTimeoutMs = millis <= 0 ? DEFAULT_IMAGE_PULL_TIMEOUT_MS : millis;
        return this;
    }

    /** 设置拉取的静默判定阈值：连续这么久没有任何输出才视为卡死。 */
    public DockerSandbox setImagePullIdle(long millis) {
        this.imagePullIdleMs = millis <= 0 ? DEFAULT_IMAGE_PULL_IDLE_MS : millis;
        return this;
    }

    /** 设置镜像加速源（registry 前缀）；空表示只用 Docker 守护进程自带的 mirrors。 */
    public DockerSandbox setPullMirrors(List<String> mirrors) {
        this.pullMirrors = mirrors == null ? List.of() : mirrors.stream()
                .map(m -> m == null ? "" : m.trim())
                .filter(m -> !m.isEmpty())
                .map(m -> m.endsWith("/") ? m.substring(0, m.length() - 1) : m)
                .toList();
        return this;
    }

    /** 设置直连拉取的自动重试次数（只对"值得再试"的失败重试，1 = 不重试）。 */
    public DockerSandbox setImagePullAttempts(int attempts) {
        this.imagePullAttempts = Math.max(1, attempts);
        return this;
    }

    /** 设置失败退避区间：第 n 次失败后等 base*2^(n-1)，最长不超过 max。 */
    public DockerSandbox setPullBackoff(long baseMs, long maxMs) {
        this.pullBackoffBaseMs = baseMs <= 0 ? 60_000L : baseMs;
        this.pullBackoffMaxMs = maxMs <= 0 ? 900_000L : maxMs;
        return this;
    }

    /** 注入镜像就绪状态存储（跨运行复用失败结论与 digest）；null = 只判断本次。 */
    public DockerSandbox setReadinessStore(ImageReadinessStore store) {
        this.readinessStore = store;
        return this;
    }

    /** 设置 Docker 守护所在主机名（就绪状态按主机隔离）。 */
    public DockerSandbox setHost(String host) {
        this.host = host == null || host.isBlank() ? defaultHost() : host.trim();
        return this;
    }

    /**
     * 默认主机名（就绪状态的归属主机）。
     *
     * <p>与容器归属标签共用 {@link #localHostName()}：两处必须是<em>同一个</em>名字，
     * 否则就绪状态与容器标签会指向不同主机（见 {@link #localHostName()} 的说明）。
     */
    private static String defaultHost() {
        return localHostName();
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

    /** 设置镜像准入策略（允许列表 / 浮动 tag）；null = 不限制。 */
    public DockerSandbox setImagePolicy(SandboxImagePolicy policy) {
        this.imagePolicy = policy == null ? SandboxImagePolicy.permissive() : policy;
        return this;
    }

    /** 当前镜像准入策略（部署器与设置页共用同一份判定）。 */
    SandboxImagePolicy imagePolicy() {
        return imagePolicy;
    }

    /**
     * 是否允许在 {@code network=none} 下执行需要外联的动作（逃生门，默认关闭）。
     *
     * <p>关闭时{@code network=none} + 需要外联 = 立即 E-707 结构化拒绝；
     * 打开时不拦截，交给实际网络错误（用于"缓存已完全预热、确实要离线构建"的场景）。
     */
    public boolean allowOffline() {
        return allowOffline;
    }

    /** 设置离线逃生门（由 SandboxConfig 注入 {@code agent.sandbox.allow-offline}）。 */
    public DockerSandbox setAllowOffline(boolean allowOffline) {
        this.allowOffline = allowOffline;
        return this;
    }

    /**
     * 动作前确认工具链镜像已在本机；缺失时自动把它弄到本机。
     *
     * <p>策略全部在 {@link ImageProvisioner}（进度感知超时、退避重试、镜像源回退、
     * 跨运行就绪状态），这里只负责把结论翻译成运行日志与可操作的异常。
     * 不复用日志/模型提供的镜像名：拉取命令只来自平台配置。
     */
    public void ensureImageReady(BooleanSupplier cancelled, Consumer<String> live) {
        if (!precheckImage) {
            return;
        }
        BooleanSupplier abort = cancelled == null ? () -> false : cancelled;
        ImageProvisioner.Result result = provisioner().provision(abort, live);
        if (result.ready()) {
            notify(live, "[sandbox] ✅ 镜像已就绪：" + image + "（来源：" + result.source()
                    + (result.digest() == null ? "" : "，" + result.digest()) + "）\n");
            return;
        }
        if (abort.getAsBoolean()) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                    "构建已取消：镜像 " + image + " 尚未就绪（" + result.summary() + "）");
        }
        // 镜像未就绪是「本机缺资源」（E-705），不是「可执行文件不存在」（E-209）：
        // 报错码要能指向正确的自愈方向，否则自动修复会去查二进制而不是拉镜像。
        throw new EnvironmentException(ErrorCode.LOCAL_IMAGE_UNAVAILABLE,
                "构建镜像未就绪（" + image + "）：自动拉取失败（" + result.errorCode() + "："
                        + result.summary() + "）；诊断：" + result.diagnosis()
                        + "；可手动 docker pull " + image
                        + "（已下载的层会复用，不会从头再下），或用 agent.sandbox.pull-mirrors"
                        + " 配镜像加速/本地缓存源，或用 agent.sandbox.image 指定本机已有镜像");
    }

    /** 按当前配置装配就绪策略（每次现取，保证 setter 改动即时生效）。自动修复路径共用它。 */
    ImageProvisioner provisioner() {
        return new ImageProvisioner(dockerBinary, image, host, commandExecutor, readinessStore,
                new ImageProvisioner.Policy(imagePullIdleMs, imagePullTimeoutMs, imagePullAttempts,
                        pullBackoffBaseMs, pullBackoffMaxMs, pullMirrors));
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
     *
     * <p><b>回退条件是"命令失败"，不是某个特定错误码</b>：早先这里只认
     * {@code COMPILE_FAILED}(E-101)，而初始化容器（{@code busybox mkdir}）的失败按
     * {@link ProcessExecutor.FailureKind#fromArgv} 归类为 {@code SCRIPT_FAILED}(E-104)——
     * 于是回退分支从未执行过。这个 bug 长期被掩盖，因为卷名过去不含哈希，测试复用同一批
     * 已 chmod 777 的旧卷；卷名唯一化（D4）后每个新卷都是冷卷，立刻暴露。
     * 现在只放过"超时/取消"（那是基础设施问题，换 root 也一样会超时）。
     */
    private void runInit(String volume, BooleanSupplier cancelled, Consumer<String> live,
                         String... argv) {
        ProcessExecutor.StepOutcome outcome =
                execAs(volume, CACHE_UID, CACHE_INIT_TIMEOUT_MS, cancelled, live, argv);
        if (outcome.errorCode() == null) {
            return;
        }
        if (isInfrastructureFailure(outcome)) {
            throw initFailure(outcome, volume, argv);
        }
        ProcessExecutor.StepOutcome asRoot =
                execAs(volume, "0:0", CACHE_INIT_TIMEOUT_MS, cancelled, live, argv);
        if (asRoot.errorCode() != null) {
            throw initFailure(asRoot, volume, argv);
        }
        notify(live, "[sandbox] 以构建用户执行 " + String.join(" ", argv)
                + " 被拒（" + outcome.errorCode() + "），已用 root 完成\n");
    }

    /** 超时与取消换用户也没用：直接抛出，别把等待时间翻倍。 */
    private static boolean isInfrastructureFailure(ProcessExecutor.StepOutcome outcome) {
        String code = outcome.errorCode();
        return ErrorCode.TIMEOUT.code().equals(code) || ErrorCode.CANCELLED.code().equals(code);
    }

    private EnvironmentException initFailure(ProcessExecutor.StepOutcome outcome, String volume,
                                             String... argv) {
        // 子步骤的错误码只能写进消息：EnvironmentException 只接受 ENVIRONMENT 组的码，
        // 运行层的 E-302/E-303 由 DockerActionRunner 按此消息与取消信号还原。
        return new EnvironmentException(ErrorCode.ENV_PREPARE_FAILED,
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
        // 初始化容器也必须带生命周期标签 + 固定名字：
        //   · 没有标签 → sweepStaleContainers 按 MANAGED_LABEL 过滤，永远看不到它们；
        //   · 没有名字 → CLI 被 kill 时 --rm 不生效，只能靠标签清扫（而现在清扫也看不见）。
        // 结果就是一次 Ctrl-C 会在 `docker ps -a` 里永久留下一个无名 init 容器。
        cmd.add("--label");
        cmd.add(MANAGED_LABEL);
        cmd.add("--label");
        cmd.add(OWNER_LABEL + "=" + OWNER_TAG);
        cmd.add("--name");
        cmd.add(initContainerName(volume));
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
        BoundedCommand.Outcome listed = new DockerOps(dockerBinary).run(
                List.of("ps", "-a", "--filter", "label=" + MANAGED_LABEL,
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
            if (!isManagedContainerName(name) || owner.isBlank()) {
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

    /**
     * 主机名的唯一实现（{@link #defaultHost} 与 {@link #hostName} 曾经是两份，
     * 环境变量优先级相反——见 {@link #localHostName()}）。
     */
    private static String hostName() {
        return localHostName();
    }

    /**
     * 本机主机名：<b>HOSTNAME 优先，其次 COMPUTERNAME</b>，最后退回 {@code InetAddress}。
     *
     * <p>为什么顺序必须固定且只有一份：这个值同时被两个地方消费——
     * <ul>
     *   <li>{@code OWNER_LABEL = hostName() + ":" + pid}：容器归属标签，清扫孤儿容器时按它比对；</li>
     *   <li>{@code defaultHost()}：镜像就绪状态的归属主机，落库时按它分区。</li>
     * </ul>
     * 两份实现一旦顺序相反（原来 {@code defaultHost} 是 HOSTNAME→COMPUTERNAME，
     * {@code hostName} 是 COMPUTERNAME→HOSTNAME），在同时设置这两个变量的混合主机上
     * （Windows + Git-Bash / WSL / 某些 CI runner）就会出现：就绪状态记在 A 名下，
     * 容器标签写着 B —— 表现为"刚拉好的镜像又被判缺失"或"活着的容器被当孤儿扫掉"。
     *
     * <p>选 HOSTNAME 优先：容器内它由 docker 注入且一定存在；Windows 宿主上两者通常相同，
     * 不同时以 HOSTNAME 为准更接近"docker 守护进程眼里这台机器叫什么"。
     */
    static String localHostName() {
        for (String key : new String[] {"HOSTNAME", "COMPUTERNAME"}) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "unknown";   // 解析失败时不抛：主机名只用于分区，不是关键路径
        }
    }

    /** pid 是否仍存活；解析失败在 {@link #staleCandidates} 里按"已退出"处理。 */
    private static boolean processAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * 卷名只允许小写字母/数字/_-，避免非法项目名注入 docker 参数。
     *
     * <p>60 字符上限是 docker 侧的实际约束；唯一性由 {@link #cacheKey} 末尾的路径哈希保证，
     * 不再依赖"名字短到不会被截断"。
     */
    static String safeVolumeName(String raw) {
        String normalized = raw.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
        return normalized.length() > 60 ? normalized.substring(0, 60) : normalized;
    }

    /** 守护进程是否可用（3 秒硬超时 + 并行排空输出，避免启动被拖住）。 */
    public boolean available() {
        return new DockerOps(dockerBinary).available();
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

    /**
     * 复制一份沙箱，只替换镜像（其余隔离参数、缓存卷、就绪状态全部带走）。
     *
     * <p>改用拷贝构造而不是"构造 + 一串 setter"：原先新增一个旋钮只要忘了在
     * {@code :928-933} 补一行，派生沙箱就会静默丢掉它（比如后加的 {@code initImage} /
     * {@code runtime} 都曾漏过）。拷贝构造把"字段清单"变成编译期唯一的同步点。
     */
    public DockerSandbox withImage(String newImage) {
        // stage.image 来自仓库里的流水线定义，是外部输入：语法校验与准入策略都要过
        String checked = ImageRef.require(newImage, "stage.image");
        imagePolicy.require(checked, SandboxImagePolicy.Source.STAGE);
        return new DockerSandbox(this, checked);
    }

    /** 拷贝构造：所有可变配置原样带走，只换镜像。 */
    private DockerSandbox(DockerSandbox base, String image) {
        this.dockerBinary = base.dockerBinary;
        this.image = image;
        this.network = base.network;
        this.memory = base.memory;
        this.cpus = base.cpus;
        this.pidsLimit = base.pidsLimit;
        this.user = base.user;
        this.cacheVolumePrefix = base.cacheVolumePrefix;
        this.initializedVolumes = base.initializedVolumes;
        this.commandExecutor = base.commandExecutor;
        this.precheckImage = base.precheckImage;
        this.imagePullTimeoutMs = base.imagePullTimeoutMs;
        this.imagePullIdleMs = base.imagePullIdleMs;
        this.pullMirrors = base.pullMirrors;
        this.imagePullAttempts = base.imagePullAttempts;
        this.pullBackoffBaseMs = base.pullBackoffBaseMs;
        this.pullBackoffMaxMs = base.pullBackoffMaxMs;
        this.readinessStore = base.readinessStore;
        this.host = base.host;
        this.npmRegistry = base.npmRegistry;
        this.runtime = base.runtime;
        this.initImage = base.initImage;
        this.imagePolicy = base.imagePolicy;
        this.allowOffline = base.allowOffline;
        this.availableRuntimes = base.availableRuntimes;
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
     * 挂载边界（<b>不变量</b>，不是请求校验）。
     *
     * <p>曾经的 {@code requireNoExtraMounts(List)} / {@code requireUnprivileged(List)} 收一个
     * "调用方想追加什么"的入参，但 {@code PipelineDefinition.StageDef} 里根本没有
     * mounts / privileged / capAdd 字段——它们永远只能收到空列表，属于给评审看的死代码，
     * 线上一次都没触发过。真正的边界由 {@link #wrap} 保证，并由
     * {@code DockerSandboxTest#wrapNeverRelaxesIsolation} 每次构建都验一遍。
     *
     * @return 本沙箱允许的全部宿主挂载（只有工作区与缓存卷两类）
     */
    List<String> allowedMounts(Path workspace) {
        String cache = cacheVolumeName(workspace);
        return cache == null
                ? List.of(CONTAINER_WORKDIR)
                : List.of(CONTAINER_WORKDIR, "/cache");
    }

    /**
     * 运行时就绪保证：配置了 gVisor 等运行时但守护进程不支持时必须**在起容器前**拒绝。
     *
     * <p>原实现只有 {@link #requireRuntimeAvailable}，而它零生产调用——配置
     * {@code agent.sandbox.runtime=runsc} 却装不上 runsc 时，用户要等到
     * {@code docker run} 才拿到一句 {@code unknown or invalid runtime name: runsc}，
     * 既没有隔离快照也没有可执行的下一步。
     *
     * <p>探测只做一次（结论缓存在实例上）：{@code docker info} 在冷启动时可能要 1~2s，
     * 每次动作前都问一遍是纯浪费。空 runtime 直接返回，不产生任何子进程。
     */
    public void ensureRuntimeReady(BooleanSupplier cancelled, Consumer<String> live) {
        if (runtime == null || runtime.isBlank()) {
            return;     // 默认 runc，无需探测
        }
        if (availableRuntimes == null) {
            BoundedCommand.Outcome info = new DockerOps(dockerBinary).run(
                    List.of("info", "--format", "{{json .Runtimes}}"),
                    IMAGE_INSPECT_TIMEOUT_MS);
            // 探测失败时留空列表：requireRuntimeAvailable 对空列表按"无法断言"放行，
            // 让后面的 docker run 给出真实错误，而不是在这里误拦一个可能正常的部署。
            availableRuntimes = info.ok() ? runtimeKeys(info.output()) : List.of();
            notify(live, "[sandbox] 守护进程可用运行时："
                    + (availableRuntimes.isEmpty() ? "未知" : String.join(", ", availableRuntimes))
                    + "\n");
        }
        requireRuntimeAvailable(availableRuntimes);
    }

    /**
     * 从 {@code docker info --format '{{json .Runtimes}}'} 的 JSON 里取出<b>顶层</b>运行时名。
     *
     * <p>必须按花括号深度过滤：每个运行时对象内部还有嵌套对象（真实输出里
     * {@code "io.containerd.runc.v2":{"path":"runc","status":{"org.opencontainers...":"..."}}}），
     * 只抓 {@code "key":\{} 的正则会把 {@code status} 也当成运行时名——于是
     * {@code agent.sandbox.runtime=status} 这种明显错误的配置会被判定为"可用"。
     * 不引 JSON 库是为了让 executor 模块保持零额外依赖；手写解析只需区分
     * "顶层键 + 值是对象"这一种情况，可变复杂度很低。
     */
    static List<String> runtimeKeys(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        int depth = 0;
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {
                int j = i + 1;
                StringBuilder name = new StringBuilder();
                while (j < json.length() && json.charAt(j) != '"') {
                    if (json.charAt(j) == '\\' && j + 1 < json.length()) {
                        j++;
                    }
                    name.append(json.charAt(j));
                    j++;
                }
                int after = skipWhitespace(json, j + 1);
                if (depth == 1 && after < json.length() && json.charAt(after) == ':') {
                    int value = skipWhitespace(json, after + 1);
                    if (value < json.length() && json.charAt(value) == '{') {
                        keys.add(name.toString());
                    }
                }
                i = j + 1;
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
            i++;
        }
        return List.copyOf(keys);
    }

    private static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
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
