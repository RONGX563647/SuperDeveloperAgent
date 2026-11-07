package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 容器沙箱：把一条业务命令包成 {@code docker run}。
 *
 * <p>约束（均在开发机实测过）：
 * <ul>
 *   <li>{@code --network none} —— 构建期无外网（依赖走缓存卷，见后续"缓存"任务）；</li>
 *   <li>{@code --user 1000:1000} —— 非 root；</li>
 *   <li>{@code --cap-drop ALL --security-opt no-new-privileges} —— 不可提权；</li>
 *   <li>{@code --memory/--cpus/--pids-limit} —— 资源上限；</li>
 *   <li>{@code --entrypoint=} —— 清空镜像 ENTRYPOINT，直接执行 argv（不套 shell）；</li>
 *   <li>工作区挂到 {@code /ws} 并作为工作目录，产物落回宿主目录。</li>
 * </ul>
 *
 * <p><b>注意</b>：镜像需自带工具链（如 {@code maven:3.9-eclipse-temurin-21}），
 * 且当前**不注入凭据**——需要私服凭据时应挂载只读 settings.xml（下一阶段）。
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

    public DockerSandbox(String dockerBinary, String image, String network,
                         String memory, String cpus, int pidsLimit, String user) {
        this.dockerBinary = dockerBinary == null ? "docker" : dockerBinary;
        this.image = image;
        this.network = network == null ? "none" : network;
        this.memory = memory == null ? "2g" : memory;
        this.cpus = cpus == null ? "2" : cpus;
        this.pidsLimit = pidsLimit <= 0 ? 512 : pidsLimit;
        this.user = user == null ? "1000:1000" : user;
    }

    /**
     * 构造 docker run 命令。
     *
     * @param workspace 宿主工作区（必须绝对路径）
     * @param argv      业务命令
     */
    public List<String> wrap(Path workspace, List<String> argv) {
        List<String> cmd = new ArrayList<>();
        cmd.add(dockerBinary);
        cmd.add("run");
        cmd.add("--rm");
        // 清空 ENTRYPOINT：保证 argv 被直接执行，而不是交给镜像默认入口
        cmd.add("--entrypoint=");
        cmd.add("--network");
        cmd.add(network);
        cmd.add("--memory");
        cmd.add(memory);
        cmd.add("--cpus");
        cmd.add(cpus);
        cmd.add("--pids-limit");
        cmd.add(String.valueOf(pidsLimit));
        cmd.add("--user");
        cmd.add(user);
        cmd.add("--security-opt");
        cmd.add("no-new-privileges");
        cmd.add("--cap-drop");
        cmd.add("ALL");
        cmd.add("--workdir");
        cmd.add(CONTAINER_WORKDIR);
        cmd.add("-v");
        cmd.add(hostPath(workspace) + ":" + CONTAINER_WORKDIR);
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

    /** 守护进程是否可用（3 秒超时，避免启动被拖住）。 */
    public boolean available() {
        try {
            Process process = new ProcessBuilder(dockerBinary, "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public String image() {
        return image;
    }

    public String describe() {
        return "docker image=" + image + " network=" + network
                + " user=" + user + " mem=" + memory + " cpus=" + cpus;
    }
}
