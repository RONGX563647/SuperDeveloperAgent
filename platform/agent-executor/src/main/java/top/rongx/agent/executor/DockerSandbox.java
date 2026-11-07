package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
 */
public class DockerSandbox {

    /** 容器内工作目录。 */
    public static final String CONTAINER_WORKDIR = "/ws";

    private final String dockerBinary;
    private final String image;
    private final String network;
    private final String memory;
    private final String cpus;
    private final int pidsLimit;
    private final String user;
    /** 依赖缓存卷前缀（每项目独立），为空则不启用缓存。 */
    private final String cacheVolumePrefix;
    /** 已初始化的卷（避免每次 docker run 都做一次 chown）。 */
    private static final Set<String> INITIALIZED_VOLUMES = ConcurrentHashMap.newKeySet();

    public DockerSandbox(String dockerBinary, String image, String network,
                         String memory, String cpus, int pidsLimit, String user) {
        this(dockerBinary, image, network, memory, cpus, pidsLimit, user, null);
    }

    public DockerSandbox(String dockerBinary, String image, String network,
                         String memory, String cpus, int pidsLimit, String user,
                         String cacheVolumePrefix) {
        this.dockerBinary = dockerBinary == null ? "docker" : dockerBinary;
        this.image = image;
        this.network = network == null ? "bridge" : network;
        this.memory = memory == null ? "2g" : memory;
        this.cpus = cpus == null ? "2" : cpus;
        this.pidsLimit = pidsLimit <= 0 ? 512 : pidsLimit;
        this.user = user == null ? "1000:1000" : user;
        this.cacheVolumePrefix = cacheVolumePrefix;
    }

    /**
     * 构造 docker run 命令。
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
        // 5. 挂载工作区到 /ws 并设为工作目录
        cmd.add("--workdir");
        cmd.add(CONTAINER_WORKDIR);
        cmd.add("-v");
        cmd.add(hostPath(workspace) + ":" + CONTAINER_WORKDIR);
        // 5b. 依赖缓存卷：Maven ~/.m2 与 npm cache 跨构建复用（按项目隔离）
        if (cacheVolumePrefix != null && !cacheVolumePrefix.isBlank()) {
            String name = safeVolumeName(cacheVolumePrefix + "-" + workspace.getFileName());
            ensureCacheVolumeWritable(name);
            cmd.add("-v");
            cmd.add(name + ":/cache");
            cmd.add("-e");
            cmd.add("MAVEN_OPTS=-Dmaven.repo.local=/cache/m2");
            cmd.add("-e");
            cmd.add("npm_config_cache=/cache/npm");
        }
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
     * Docker 新建的命名卷属主是 root；构建容器以 uid 1000 运行会写不进缓存。
     * 这里用一次特权容器把 /cache 及其子目录 chown 给构建用户（幂等、只做一次）。
     */
    private void ensureCacheVolumeWritable(String volume) {
        if (INITIALIZED_VOLUMES.contains(volume)) {
            return;
        }
        synchronized (INITIALIZED_VOLUMES) {
            if (INITIALIZED_VOLUMES.contains(volume)) {
                return;
            }
            try {
                // 两步都用 argv 直执行（不经 shell），固定字符串，不接受外部输入
                runAsRoot(volume, "mkdir", "-p", "/cache/m2", "/cache/npm");
                runAsRoot(volume, "chown", "-R", "1000:1000", "/cache");
                INITIALIZED_VOLUMES.add(volume);
            } catch (Exception ignored) {
                INITIALIZED_VOLUMES.add(volume);
            }
        }
    }

    private void runAsRoot(String volume, String... argv) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(dockerBinary);
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--entrypoint=");
        cmd.add("--user");
        cmd.add("0:0");
        cmd.add("-v");
        cmd.add(volume + ":/cache");
        cmd.add(image);
        cmd.addAll(List.of(argv));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("缓存卷初始化失败: " + String.join(" ", argv));
        }
    }

    /** 卷名只允许小写字母/数字/_-，避免非法项目名注入 docker 参数。 */
    static String safeVolumeName(String raw) {
        String normalized = raw.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
        return normalized.length() > 60 ? normalized.substring(0, 60) : normalized;
    }

    /** 守护进程是否可用（3 秒超时，避免启动被拖住）。 */
    public boolean available() {
        try {
            // 1. 探测 docker info，3 秒超时
            Process process = new ProcessBuilder(dockerBinary, "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            // 2. 退出码 0 即可用
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public String image() {
        return image;
    }

    /** 复制一份沙箱，只替换镜像（其余隔离参数与缓存卷不变）。 */
    public DockerSandbox withImage(String newImage) {
        return new DockerSandbox(dockerBinary, newImage, network, memory, cpus, pidsLimit,
                user, cacheVolumePrefix);
    }

    public String describe() {
        return "docker image=" + image + " network=" + network
                + " user=" + user + " mem=" + memory + " cpus=" + cpus;
    }
}
