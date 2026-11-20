package top.rongx.agent.executor;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.domain.image.ImageReadinessStore;

/**
 * 执行模式装配。
 *
 * <p>{@code agent.sandbox.mode}：
 * <ul>
 *   <li>{@code docker}（默认）—— 强制容器；不可用则**启动失败**
 *       （不让"以为隔离了其实没隔离"发生）；</li>
 *   <li>{@code process} —— 强制宿主（仅开发，必须显式开启）。</li>
 * </ul>
 *
 * <p>历史配置 {@code auto} 仍然接受，但语义改为 fail-closed：Docker 不可用时启动失败，
 * 不再静默退回宿主进程。
 */
@Configuration
public class SandboxConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxConfig.class);

    @Bean
    public DockerSandbox dockerSandbox(
            org.springframework.beans.factory.ObjectProvider<ImageReadinessStore> readinessStore,
            @Value("${agent.sandbox.docker-binary:docker}") String binary,
            @Value("${agent.sandbox.image:maven:3.9-eclipse-temurin-21}") String image,
            @Value("${agent.sandbox.network:bridge}") String network,
            @Value("${agent.sandbox.memory:2g}") String memory,
            @Value("${agent.sandbox.cpus:2}") String cpus,
            @Value("${agent.sandbox.pids-limit:512}") int pidsLimit,
            @Value("${agent.sandbox.user:1000:1000}") String user,
            @Value("${agent.sandbox.cache-volume-prefix:}") String cacheVolumePrefix,
            @Value("${agent.sandbox.precheck-image:true}") boolean precheckImage,
            @Value("${agent.sandbox.pull-timeout-seconds:900}") long pullTimeoutSeconds,
            @Value("${agent.sandbox.pull-idle-timeout-seconds:60}") long pullIdleTimeoutSeconds,
            @Value("${agent.sandbox.pull-attempts:2}") int pullAttempts,
            @Value("${agent.sandbox.pull-backoff-seconds:60}") long pullBackoffSeconds,
            @Value("${agent.sandbox.pull-backoff-max-seconds:900}") long pullBackoffMaxSeconds,
            @Value("${agent.sandbox.pull-mirrors:}") String pullMirrors,
            @Value("${agent.sandbox.host:}") String host,
            @Value("${agent.sandbox.npm-registry:}") String npmRegistry,
            @Value("${agent.sandbox.runtime:}") String runtime,
            @Value("${agent.sandbox.init-image:busybox:1.36}") String initImage) {
        // 镜像标签由 agent.sandbox.image 决定；缺镜像时自动 pull 并复核，
        // 离线/自建 registry 仍可通过 agent.sandbox.precheck-image=false 关闭预检。
        // pull-timeout-seconds 是总上限（只在一直有进度时兜底），判卡死用的是静默阈值：
        // 固定总预算会把「层已下完、正在解压」误判成网络故障（实测 node:22 冷拉超过 180s）。
        // 就绪状态落库（V6）后，同一台机器上的重复构建可直接复用失败结论与 digest。
        ImageReadinessStore store = readinessStore.getIfAvailable();
        if (store == null) {
            log.warn("未接镜像就绪状态存储：每次动作前都会重新探测/拉取（不影响正确性，只是更慢）");
        }
        return new DockerSandbox(binary, image, network, memory, cpus, pidsLimit, user,
                cacheVolumePrefix).setImagePrecheck(precheckImage)
                .setImagePullTimeout(pullTimeoutSeconds * 1000L)
                .setImagePullIdle(pullIdleTimeoutSeconds * 1000L)
                .setImagePullAttempts(pullAttempts)
                .setPullBackoff(pullBackoffSeconds * 1000L, pullBackoffMaxSeconds * 1000L)
                .setPullMirrors(splitList(pullMirrors))
                .setHost(host)
                .setReadinessStore(store)
                .setNpmRegistry(npmRegistry)
                .setRuntime(runtime)
                .setInitImage(initImage);
    }

    /** 逗号/空白分隔的配置项拆成列表（镜像加速源等）。 */
    static List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public ActionRunner actionRunner(DockerSandbox sandbox,
                                     @Value("${agent.sandbox.mode:docker}") String mode) {
        String normalized = mode == null ? "docker" : mode.trim().toLowerCase();
        switch (normalized) {
            case "process" -> {
                log.warn("动作执行模式：宿主进程（无隔离，仅用于开发）");
                return new ProcessActionRunner();
            }
            case "docker" -> {
                if (!sandbox.available()) {
                    throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "agent.sandbox.mode=docker 但 Docker 守护进程不可用；"
                                    + "请启动 Docker，或改为 mode=process（不隔离）");
                }
                log.info("动作执行模式：容器沙箱（{}）", sandbox.describe());
                sweep(sandbox);
                return new DockerActionRunner(sandbox);
            }
            default -> {
                if (sandbox.available()) {
                    log.info("动作执行模式：容器沙箱（{}）", sandbox.describe());
                    sweep(sandbox);
                    return new DockerActionRunner(sandbox);
                }
                // fail-closed：绝不因为 Docker 没起来就悄悄降级为无隔离的宿主执行
                throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "Docker 不可用，拒绝以宿主进程执行（无隔离）。"
                                + "请启动 Docker，或仅限本地开发显式设置 "
                                + "agent.sandbox.mode=process");
            }
        }
    }

    /**
     * 启动清扫：删除上个进程遗留的构建容器。
     *
     * <p>{@code --rm} 只在 docker CLI 正常退出时生效；动作超时或平台被 kill 时 CLI 先死，
     * 容器会带着固定名字继续跑，占住内存并可能让下一次同名启动失败。
     * 清理失败不影响启动：沙箱可用性已在调用前确认。
     */
    private static void sweep(DockerSandbox sandbox) {
        try {
            sandbox.sweepStaleContainers(log::info);
        } catch (RuntimeException e) {
            log.warn("遗留容器清扫失败（不影响启动）：{}", e.toString());
        }
    }
}
