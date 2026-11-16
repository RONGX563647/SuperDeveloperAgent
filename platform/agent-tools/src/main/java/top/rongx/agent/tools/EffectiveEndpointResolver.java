package top.rongx.agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.port.PipelineDefStore;
import top.rongx.agent.domain.port.ProjectRepoLocator;

/**
 * 「这个环境实际跑在哪个端口」的唯一解析口径。
 *
 * <p>历史问题：登记地址、环境模板、流水线 deploy 段、容器端口各说各话
 * （8180 / 8080 / 8182 / 8090），Agent 和用户都判断不了以谁为准。
 *
 * <p>优先级：仓库 ci/pipeline.yml > 平台侧 ci/pipeline.yml > 环境模板默认值。
 * 前两者与 {@code PipelineDefinitionResolver} 的发布口径一致；环境模板只做兜底。
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
        Optional<Path> repo = locator.repoDir(projectId);
        if (repo.isPresent()) {
            Path file = repo.get().resolve("ci").resolve("pipeline.yml");
            if (Files.isRegularFile(file)) {
                Endpoint fromRepo = parse(read(file), "仓库 ci/pipeline.yml");
                if (fromRepo != null) {
                    return fromRepo;
                }
            }
        }
        Endpoint fromPlatform = parse(defStore.read(projectId).orElse(""), "平台 ci/pipeline.yml");
        if (fromPlatform != null) {
            return fromPlatform;
        }
        EffectiveEnvironment effective = envStore.effective(projectId, env);
        return new Endpoint(effective.defaultPort(),
                effective.healthPath() == null ? "/healthz" : effective.healthPath(),
                "环境模板（流水线没有 deploy 段）");
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
