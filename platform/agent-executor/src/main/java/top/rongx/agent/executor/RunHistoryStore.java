package top.rongx.agent.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.yaml.snakeyaml.Yaml;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;

/**
 * 构建历史持久化：每次运行一个 YAML 文件，重启后可读回。
 *
 * <p>文件位置：{@code <workspaceRoot>/_history/<runId>.yml}。
 * 用 YAML 而非数据库，是因为当前是单机平台，且产物/阶段都是简单结构；
 * 换 MySQL 时只需替换本类，接口不变。
 */
public class RunHistoryStore {

    private final Path dir;
    private final boolean enabled;

    private RunHistoryStore(Path dir, boolean enabled) {
        this.dir = dir;
        this.enabled = enabled;
    }

    public static RunHistoryStore file(Path workspaceRoot) {
        Path dir = workspaceRoot.resolve("_history");
        try {
            Files.createDirectories(dir);
            return new RunHistoryStore(dir, true);
        } catch (IOException e) {
            return disabled();
        }
    }

    public static RunHistoryStore disabled() {
        return new RunHistoryStore(Path.of("."), false);
    }

    /** 保存一次运行的快照（覆盖写，保证终态是最后一次结果）。 */
    public void save(RunSnapshot snapshot) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("runId", snapshot.runId());
            data.put("projectId", snapshot.projectId());
            data.put("pipelineId", snapshot.pipelineId());
            data.put("gitSha", snapshot.gitSha());
            data.put("status", snapshot.status().name());
            data.put("log", snapshot.log());
            data.put("stages", snapshot.stages().stream().map(RunHistoryStore::stageMap).toList());
            data.put("artifacts", snapshot.artifacts().stream().map(RunHistoryStore::artifactMap).toList());
            Files.writeString(dir.resolve(snapshot.runId() + ".yml"),
                    new Yaml().dump(data));
        } catch (IOException ignored) {
            // 持久化失败不阻断构建
        }
    }

    /** 列出历史（按文件名倒序，最新在前）。 */
    public List<RunSnapshot> list() {
        if (!enabled || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<RunSnapshot> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".yml"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            out.add(read(p));
                        } catch (Exception ignored) {
                            // 单个损坏文件不影响其他历史
                        }
                    });
        } catch (IOException ignored) {
            // ignore
        }
        return out;
    }

    private RunSnapshot read(Path file) throws IOException {
        Object loaded = new Yaml().load(Files.readString(file));
        if (!(loaded instanceof Map<?, ?> raw)) {
            throw new IOException("历史文件格式错误: " + file);
        }
        Map<String, Object> m = cast(raw);
        java.util.function.Function<String, String> str =
                key -> String.valueOf(m.getOrDefault(key, ""));
        List<StageResult> stages = new ArrayList<>();
        if (m.get("stages") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> rawStage) {
                    Map<String, Object> s = cast(rawStage);
                    stages.add(new StageResult(String.valueOf(s.get("id")),
                            StageStatus.valueOf(String.valueOf(s.get("status"))),
                            ((Number) s.getOrDefault("exitCode", 0)).intValue(),
                            s.get("errorCode") == null ? null : String.valueOf(s.get("errorCode")),
                            s.get("summary") == null ? null : String.valueOf(s.get("summary")),
                            List.of(), ((Number) s.getOrDefault("durationMs", 0)).longValue(), 0));
                }
            }
        }
        List<ArtifactRef> artifacts = new ArrayList<>();
        if (m.get("artifacts") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> rawArtifact) {
                    Map<String, Object> a = cast(rawArtifact);
                    artifacts.add(new ArtifactRef(String.valueOf(a.get("kind")),
                            String.valueOf(a.get("ref")),
                            a.get("digest") == null ? null : String.valueOf(a.get("digest")),
                            ((Number) a.getOrDefault("sizeBytes", 0)).longValue(), Map.of()));
                }
            }
        }
        return new RunSnapshot(str.apply("runId"), str.apply("projectId"), str.apply("pipelineId"),
                str.apply("gitSha"), StageStatus.valueOf(str.apply("status")),
                stages, artifacts, String.valueOf(m.getOrDefault("log", "")));
    }

    /** 把 SnakeYAML 的 {@code Map<?,?>} 安全转成 {@code Map<String,Object>}。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private static Map<String, Object> stageMap(StageResult s) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", s.stageId());
        m.put("status", s.status().name());
        m.put("exitCode", s.exitCode());
        m.put("errorCode", s.errorCode() == null ? "" : s.errorCode());
        m.put("summary", s.errorSummary() == null ? "" : s.errorSummary());
        m.put("durationMs", s.durationMs());
        return m;
    }

    private static Map<String, Object> artifactMap(ArtifactRef a) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("kind", a.kind());
        m.put("ref", a.ref());
        m.put("digest", a.digest() == null ? "" : a.digest());
        m.put("sizeBytes", a.sizeBytes());
        return m;
    }

    /** 一次运行的不可变快照。 */
    public record RunSnapshot(String runId, String projectId, String pipelineId, String gitSha,
                              StageStatus status, List<StageResult> stages,
                              List<ArtifactRef> artifacts, String log) {
    }
}
