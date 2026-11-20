package top.rongx.agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.PortLease;
import top.rongx.agent.domain.env.ServiceLease;
import top.rongx.agent.domain.port.PipelineDefStore;
import top.rongx.agent.domain.port.ProjectRepoLocator;

/**
 * 「这个环境实际跑在哪个端口」的唯一解析口径。
 *
 * <p>历史问题（BUG-44）：本口径只读 {@code ci/pipeline.yml} 的期望端口，从不读部署
 * 真实分配到的端口租约；部署器在期望端口被占时会换端口并写入 {@code PortLease}，
 * 于是对外宣告的是"愿望"而不是事实。
 *
 * <p>优先级：① 环境内 RUNNING 的本地服务租约（dev 本机托管）
 * → ② 端口租约 hostPort（容器部署，actualPort 校正后写入）
 * → ③ 仓库/平台 ci/pipeline.yml 的 deploy.port（未部署时的期望值）
 * → ④ 环境模板 defaultPort。
 *
 * <p>source 区分「租约（已部署的真实端口）」与「定义（未部署的期望值）」，
 * 调用方据此把"愿望"与"事实"分开呈现。
 */
@Component
public class EffectiveEndpointResolver {

    private final PipelineDefStore defStore;
    private final ProjectRepoLocator locator;
    private final EnvironmentStore envStore;

    public EffectiveEndpointResolver(PipelineDefStore defStore, ProjectRepoLocator locator,
                                     EnvironmentStore envStore) {
        this.defStore = defStore;
        this.locator = locator;
        this.envStore = envStore;
    }

    /** @param source 端口/健康路径来自哪里，便于排查"以谁为准" */
    public record Endpoint(int port, String healthPath, String source) {
    }

    public Endpoint resolve(String projectId, String env) {
        // 定义链（期望值）：仓库 pipeline.yml > 平台 pipeline.yml > 环境模板
        Endpoint definition = definitionEndpoint(projectId, env);
        // ① dev 本机托管：平台拉起的服务进程有 RUNNING 租约 → 用它的真实端口
        Optional<ServiceLease> running = envStore.serviceLeases(projectId, env).stream()
                .filter(ServiceLease::running)
                .filter(l -> ServiceLease.BACKEND.equals(l.service()))
                .findFirst();
        if (running.isPresent()) {
            return new Endpoint(running.get().hostPort(), definition.healthPath(),
                    "本地服务租约（已部署）");
        }
        // ② 容器部署：最近一次部署实际绑定（并校正过）的宿主端口。
        // 优先按流水线声明的 containerPort 精确取租约（PortLease 以容器端口为键），
        // 取不到（声明缺失或租约已换键）再退回该环境最新的一条，避免误报别的端口。
        Optional<PortLease> lease = Optional.empty();
        int containerPort = containerPort(projectId);
        if (containerPort > 0) {
            lease = envStore.lease(projectId, env, containerPort);
        }
        if (lease.isEmpty()) {
            lease = envStore.leases(projectId, env).stream()
                    .max(Comparator.comparingLong(PortLease::updatedAt));
        }
        if (lease.isPresent()) {
            return new Endpoint(lease.get().hostPort(), definition.healthPath(),
                    "端口租约（已部署）");
        }
        // ③/④ 未部署：期望值，source 明确标注"未部署，期望端口"
        return definition;
    }

    /** 期望端口口径（无租约时对外返回的值）；healthPath 即使有租约也会沿用。 */
    private Endpoint definitionEndpoint(String projectId, String env) {
        Optional<Path> repo = locator.repoDir(projectId);
        if (repo.isPresent()) {
            Path file = repo.get().resolve("ci").resolve("pipeline.yml");
            if (Files.isRegularFile(file)) {
                Endpoint fromRepo = parse(read(file), "仓库 ci/pipeline.yml（未部署，期望端口）");
                if (fromRepo != null) {
                    return fromRepo;
                }
            }
        }
        Endpoint fromPlatform = parse(defStore.read(projectId).orElse(""),
                "平台 ci/pipeline.yml（未部署，期望端口）");
        if (fromPlatform != null) {
            return fromPlatform;
        }
        EffectiveEnvironment effective = envStore.effective(projectId, env);
        return new Endpoint(effective.defaultPort(),
                effective.healthPath() == null ? "/healthz" : effective.healthPath(),
                "环境模板（流水线没有 deploy 段）");
    }

    /**
     * 流水线声明的容器端口（PortLease 的键）；仓库 pipeline.yml 优先，其次平台 pipeline.yml。
     *
     * @return 未声明时返回 0
     */
    private int containerPort(String projectId) {
        Optional<Path> repo = locator.repoDir(projectId);
        if (repo.isPresent()) {
            Path file = repo.get().resolve("ci").resolve("pipeline.yml");
            if (Files.isRegularFile(file)) {
                int port = containerPortOfYaml(read(file));
                if (port > 0) {
                    return port;
                }
            }
        }
        return containerPortOfYaml(defStore.read(projectId).orElse(""));
    }

    private static int containerPortOfYaml(String yaml) {
        Map<?, ?> deploy = deploySection(yaml);
        return deploy == null ? 0 : intValue(deploy.get("containerPort"), 0);
    }

    private static Map<?, ?> deploySection(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return null;
        }
        Object loaded;
        try {
            loaded = new Yaml().load(yaml);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(loaded instanceof Map<?, ?> doc)
                || !(doc.get("deploy") instanceof Map<?, ?> deploy)) {
            return null;
        }
        return deploy;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    private static Endpoint parse(String yaml, String source) {
        if (yaml == null || yaml.isBlank()) {
            return null;
        }
        Object loaded;
        try {
            loaded = new Yaml().load(yaml);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(loaded instanceof Map<?, ?> doc)
                || !(doc.get("deploy") instanceof Map<?, ?> deploy)) {
            return null;
        }
        int port = intValue(deploy.get("port"), intValue(deploy.get("containerPort"), 0));
        if (port <= 0) {
            return null;
        }
        String healthPath = deploy.get("healthPath") == null
                ? "/healthz" : String.valueOf(deploy.get("healthPath")).trim();
        if (healthPath.isBlank()) {
            healthPath = "/healthz";
        }
        return new Endpoint(port, healthPath, source);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
