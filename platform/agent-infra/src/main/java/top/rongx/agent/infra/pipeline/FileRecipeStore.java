package top.rongx.agent.infra.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import top.rongx.agent.domain.deploy.DeployRecipe;
import top.rongx.agent.domain.port.RecipeStore;

/**
 * recipe 的文件存储：{@code <workspace>/<projectId>/recipes/<env>/v<N>/{pipeline.yml,actions.yml,meta.yml}}，
 * 另有 {@code current} 文件指向生效版本。
 *
 * <p>目录形状（{@code v<N>/ci/…}）与仓库根一致，解析器与配方查找可以直接复用。
 */
@Component
public class FileRecipeStore implements RecipeStore {

    private static final Logger log = LoggerFactory.getLogger(FileRecipeStore.class);
    private final Path workspaceRoot;

    public FileRecipeStore(@Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
    }

    @Override
    public DeployRecipe save(DeployRecipe recipe) {
        String projectId = safe(recipe.projectId());
        String env = safe(recipe.env());
        int version = nextVersion(projectId, env);
        Path dir = versionDir(projectId, env, version);
        try {
            Files.createDirectories(dir.resolve("ci"));
            Files.writeString(dir.resolve("ci/pipeline.yml"), recipe.pipelineYaml(), StandardCharsets.UTF_8);
            if (recipe.actionsYaml() != null && !recipe.actionsYaml().isBlank()) {
                Files.writeString(dir.resolve("ci/actions.yml"), recipe.actionsYaml(), StandardCharsets.UTF_8);
            }
            DeployRecipe saved = new DeployRecipe(projectId, env, version, recipe.pipelineYaml(),
                    recipe.actionsYaml(), recipe.evidenceRunId(), recipe.gitSha(),
                    System.currentTimeMillis(), recipe.createdBy());
            Files.writeString(dir.resolve("meta.yml"), new Yaml().dump(metaOf(saved)),
                    StandardCharsets.UTF_8);
            Files.writeString(envDir(projectId, env).resolve("current"),
                    "v" + version, StandardCharsets.UTF_8);
            log.info("固化环境脚本：{} / {} → v{}（证据 {}）", projectId, env, version, saved.evidenceRunId());
            return saved;
        } catch (IOException e) {
            throw new IllegalStateException("保存环境脚本失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<DeployRecipe> current(String projectId, String env) {
        return currentVersion(projectId, env).flatMap(v -> version(projectId, env, v));
    }

    @Override
    public Optional<DeployRecipe> version(String projectId, String env, int version) {
        Path dir = versionDir(safe(projectId), safe(env), version);
        Path pipeline = dir.resolve("ci/pipeline.yml");
        if (!Files.isRegularFile(pipeline)) {
            return Optional.empty();
        }
        try {
            String yaml = Files.readString(pipeline, StandardCharsets.UTF_8);
            Path actionsFile = dir.resolve("ci/actions.yml");
            String actions = Files.isRegularFile(actionsFile)
                    ? Files.readString(actionsFile, StandardCharsets.UTF_8) : null;
            Map<String, Object> meta = readMeta(dir.resolve("meta.yml"));
            return Optional.of(new DeployRecipe(safe(projectId), safe(env), version, yaml, actions,
                    str(meta.get("evidenceRunId")), str(meta.get("gitSha")),
                    num(meta.get("createdAt")), str(meta.get("createdBy"))));
        } catch (IOException e) {
            log.warn("读取环境脚本失败 {}：{}", dir, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<DeployRecipe> versions(String projectId, String env) {
        Path dir = envDir(safe(projectId), safe(env));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            List<Integer> nums = stream
                    .filter(p -> p.getFileName().toString().matches("v\\d+"))
                    .map(p -> Integer.parseInt(p.getFileName().toString().substring(1)))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            List<DeployRecipe> out = new ArrayList<>();
            for (int v : nums) {
                version(projectId, env, v).ifPresent(out::add);
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    @Override
    public Optional<DeployRecipe> updateEvidence(String projectId, String env,
                                                 String runId, String gitSha) {
        return current(projectId, env).map(cur -> {
            DeployRecipe updated = cur.withEvidence(runId, gitSha);
            Path dir = versionDir(safe(projectId), safe(env), cur.version());
            try {
                Files.writeString(dir.resolve("meta.yml"), new Yaml().dump(metaOf(updated)),
                        StandardCharsets.UTF_8);
                log.info("刷新环境脚本证据：{} / {} v{} → 证据 {}", projectId, env,
                        cur.version(), updated.evidenceRunId());
            } catch (IOException e) {
                log.warn("刷新环境脚本证据失败 {} / {} v{}：{}", projectId, env, cur.version(),
                        e.getMessage());
            }
            return updated;
        });
    }

    @Override
    public boolean activate(String projectId, String env, int version) {
        Path dir = versionDir(safe(projectId), safe(env), version);
        if (!Files.isRegularFile(dir.resolve("ci/pipeline.yml"))) {
            return false;
        }
        try {
            Files.writeString(envDir(projectId, env).resolve("current"), "v" + version, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public Path rootOf(String projectId, String env, int version) {
        return versionDir(safe(projectId), safe(env), version);
    }

    /** 只删 current 指针：历史版本保留，必要时可用 activate 回到旧脚本。 */
    @Override
    public void invalidate(String projectId, String env) {
        Path pointer = envDir(safe(projectId), safe(env)).resolve("current");
        try {
            if (Files.deleteIfExists(pointer)) {
                log.info("环境脚本已失效：{} / {}（定义已重新生成，下次运行重新固化）", projectId, env);
            }
        } catch (IOException e) {
            log.warn("失效环境脚本失败 {} / {}：{}", projectId, env, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 内部

    private static Map<String, Object> metaOf(DeployRecipe recipe) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("projectId", recipe.projectId());
        meta.put("env", recipe.env());
        meta.put("version", recipe.version());
        meta.put("evidenceRunId", recipe.evidenceRunId() == null ? "" : recipe.evidenceRunId());
        meta.put("gitSha", recipe.gitSha() == null ? "" : recipe.gitSha());
        meta.put("createdAt", recipe.createdAt());
        meta.put("createdBy", recipe.createdBy() == null ? "" : recipe.createdBy());
        return meta;
    }

    private Optional<Integer> currentVersion(String projectId, String env) {
        Path pointer = envDir(safe(projectId), safe(env)).resolve("current");
        if (!Files.isRegularFile(pointer)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            return text.matches("v\\d+") ? Optional.of(Integer.parseInt(text.substring(1))) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private int nextVersion(String projectId, String env) {
        Path dir = envDir(projectId, env);
        int max = 0;
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                max = stream.map(p -> p.getFileName().toString())
                        .filter(n -> n.matches("v\\d+"))
                        .mapToInt(n -> Integer.parseInt(n.substring(1)))
                        .max().orElse(0);
            } catch (IOException ignored) {
                // 读不到就从头开始编号
            }
        }
        return max + 1;
    }

    private Path envDir(String projectId, String env) {
        return workspaceRoot.resolve(projectId).resolve("recipes").resolve(env);
    }

    private Path versionDir(String projectId, String env, int version) {
        return envDir(projectId, env).resolve("v" + version);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMeta(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            Object loaded = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
            return loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /** projectId / env 只允许字母数字下划线短横（防路径穿越）。 */
    private static String safe(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_\\-]{1,32}")) {
            throw new IllegalArgumentException("非法标识：" + value);
        }
        return value;
    }
}
