package top.rongx.agent.executor;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 *   <li>{@code docker}（默认）—— 强制容器；守护进程可用性在<b>执行动作时</b>检查：
 *       不可用则该次动作失败（fail-closed），但平台本身照常启动、health 正常
 *       （不让"以为隔离了其实没隔离"发生，也不让没有 daemon 的平台起不来）；</li>
 *   <li>{@code process} —— 强制宿主（仅开发，必须显式开启）。</li>
 * </ul>
 *
 * <p>历史值 {@code auto} 与任何未知值一律启动失败（见 {@link #actionRunner}）：
 * 它曾经是"自动切换"，现在即使写了也会得到明确报错，而不是悄悄降级为无隔离执行。
 *
 * <p>所有 {@code agent.sandbox.*} 旋钮的声明点在 {@link SandboxProperties}：
 * 23 个 {@code @Value} 散在 bean 方法签名里的写法，加一个旋钮要改三处（yml / 签名 / 装配链），
 * 漏一处就是静默不生效，而且没法做启动期校验。
 */
@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxConfig.class);

    @Bean
    public DockerSandbox dockerSandbox(
            org.springframework.beans.factory.ObjectProvider<ImageReadinessStore> readinessStore,
            SandboxProperties props) {
        // 配置问题在这里收敛成"一条明确告警 + 安全默认"，而不是让 docker run 时报 CLI 原始错误。
        // 例：memory 写成 "2"（少了单位）过去要等到起容器才失败，现在是启动时就说清楚。
        for (String problem : props.normalize()) {
            log.warn("沙箱配置：{}", problem);
        }
        log.info("沙箱配置：{}", props.describe());
        // 镜像标签由 agent.sandbox.image 决定；缺镜像时自动 pull 并复核，
        // 离线/自建 registry 仍可通过 agent.sandbox.precheck-image=false 关闭预检。
        // pull-timeout-seconds 是总上限（只在一直有进度时兜底），判卡死用的是静默阈值：
        // 固定总预算会把「层已下完、正在解压」误判成网络故障（实测 node:22 冷拉超过 180s）。
        // 就绪状态落库（V6）后，同一台机器上的重复构建可直接复用失败结论与 digest。
        ImageReadinessStore store = readinessStore.getIfAvailable();
        if (store == null) {
            log.warn("未接镜像就绪状态存储：每次动作前都会重新探测/拉取（不影响正确性，只是更慢）");
        }
        SandboxImagePolicy policy = new SandboxImagePolicy(
                splitList(props.getImageAllowlist()), props.isAllowFloatingTags());
        if (!policy.describe().get(1).endsWith("(不限)")) {
            log.info("镜像准入策略：{}", String.join(" ", policy.describe()));
        }
        return new DockerSandbox(props.getDockerBinary(), props.getImage(), props.getNetwork(),
                props.getMemory(), props.getCpus(), props.getPidsLimit(), props.getUser(),
                props.getCacheVolumePrefix())
                .setImagePrecheck(props.isPrecheckImage())
                .setImagePullTimeout(props.getPullTimeoutSeconds() * 1000L)
                .setImagePullIdle(props.getPullIdleTimeoutSeconds() * 1000L)
                .setImagePullAttempts(props.getPullAttempts())
                .setPullBackoff(props.getPullBackoffSeconds() * 1000L,
                        props.getPullBackoffMaxSeconds() * 1000L)
                .setPullMirrors(splitList(props.getPullMirrors()))
                .setHost(props.getHost())
                .setReadinessStore(store)
                .setNpmRegistry(props.getNpmRegistry())
                .setRuntime(props.getRuntime())
                .setInitImage(props.getInitImage())
                .setAllowOffline(props.isAllowOffline())
                .setImagePolicy(policy);
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

    /**
     * 执行模式装配。
     *
     * <p>取值只有两个，<b>没有默认兜底分支</b>：
     * <ul>
     *   <li>{@code docker}（默认）—— 强制容器；守护进程不可用则<b>执行动作时失败</b>
     *       （不让"以为隔离了其实没隔离"发生；但平台启动与 health 不依赖 daemon）；</li>
     *   <li>{@code process} —— 强制宿主（仅开发，必须显式开启）。</li>
     * </ul>
     *
     * <p>历史值 {@code auto} 与任何未知值（含拼写错误 {@code dockr}）一律抛
     * {@link ErrorCode#ENVIRONMENT_INVALID_STATE}：原先 {@code default ->} 会把未知值静默当
     * docker 处理，"配置写错了"表现为"行为和预期不同"而不是一条明确报错。
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public ActionRunner actionRunner(DockerSandbox sandbox, SandboxProperties props) {
        String mode = props.getMode();
        String normalized = mode == null || mode.isBlank() ? "docker" : mode.trim().toLowerCase();
        return switch (normalized) {
            case "process" -> {
                log.warn("动作执行模式：宿主进程（无隔离，仅用于开发）");
                yield new ProcessActionRunner();
            }
            case "docker" -> dockerRunner(sandbox);
            default -> throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                    "agent.sandbox.mode 取值非法：'" + mode + "'（合法值：docker | process）；"
                            + "历史值 auto 已废弃——它过去会在 Docker 不可用时静默降级为无隔离的宿主执行，"
                            + "现在一律 fail-closed");
        };
    }

    /**
     * 起容器执行器。
     *
     * <p>守护进程可用性<b>不在这里检查</b>：过去在此调用 {@code available()} 失败会直接抛异常，
     * 让整个 Spring context 起不来、平台 health 永远不通——即使平台只是启动 UI / 配置管理，
     * 根本不跑构建动作（典型场景：单独用平台镜像部署、没有 dind sidecar）。
     * 现在把检查后移到 {@link DockerActionRunner#run} 每次执行动作之前：无守护进程则该次动作
     * 结构化失败（fail-closed），平台本身照常启动、health 正常。安全属性不变——不可信的构建
     * 配方永远不会以无隔离的宿主进程模式执行。
     */
    private ActionRunner dockerRunner(DockerSandbox sandbox) {
        log.info("动作执行模式：容器沙箱（{}）；守护进程可用性在执行动作时检查", sandbox.describe());
        sweep(sandbox);
        return new DockerActionRunner(sandbox);
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
