package top.rongx.agent.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
            data.put("env", snapshot.env());
            data.put("pipelineId", snapshot.pipelineId());
            data.put("gitSha", snapshot.gitSha());
            data.put("status", snapshot.status().name());
            data.put("createdAt", snapshot.createdAt());
            data.put("seq", snapshot.seq());
            data.put("log", snapshot.log());
            // 定义来源：让重启后 ci_history 仍能回答"这次跑的是哪份定义"
            data.put("definitionSource", snapshot.definitionSource() == null ? "" : snapshot.definitionSource());
            data.put("definitionNote", snapshot.definitionNote() == null ? "" : snapshot.definitionNote());
            data.put("stages", snapshot.stages().stream().map(RunHistoryStore::stageMap).toList());
            data.put("artifacts", snapshot.artifacts().stream().map(RunHistoryStore::artifactMap).toList());
            Files.writeString(dir.resolve(snapshot.runId() + ".yml"),
                    new Yaml().dump(data));
        } catch (IOException ignored) {
            // 持久化失败不阻断构建
        }
    }

    /**
     * 列出历史（按创建时间倒序，最新在前；缺时间戳的旧文件排最后）。
     *
     * <p>旧文件没有 {@code seq}：按「项目 + 环境」的创建时间顺序补一个从 1 开始的序号，
     * 这样界面上的构建序号不会因为历史格式升级而断档。
     */
    public List<RunSnapshot> list() {
        if (!enabled || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<RunSnapshot> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".yml"))
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
        out.sort(Comparator.comparingLong(RunSnapshot::createdAt));
        Map<String, Integer> cursor = new HashMap<>();
        List<RunSnapshot> numbered = new ArrayList<>(out.size());
        for (RunSnapshot s : out) {
            String key = s.projectId() + '\u0000' + s.env();
            int next = cursor.getOrDefault(key, 0) + 1;
            int seq = s.seq() > 0 ? s.seq() : next;
            cursor.put(key, Math.max(next, seq));
            if (seq == s.seq()) {
                numbered.add(s);
            } else {
                // 补的号立即落盘：否则删掉中间一条后，后面旧文件的号会整体前移
                RunSnapshot filled = s.withSeq(seq);
                save(filled);
                numbered.add(filled);
            }
        }
        numbered.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return numbered;
    }

    /** 同一项目 + 环境内的下一个构建序号（从 1 开始，取现有最大值 +1）。 */
    public synchronized int nextSeq(String projectId, String env) {
        int max = 0;
        for (RunSnapshot s : list()) {
            if (s.projectId().equals(projectId) && s.env().equals(env)) {
                max = Math.max(max, s.seq());
            }
        }
        return max + 1;
    }

    /** 删除一条构建历史；文件不存在视为已删除。 */
    public boolean delete(String runId) {
        if (!enabled || runId == null || runId.isBlank()) {
            return false;
        }
        try {
            return Files.deleteIfExists(dir.resolve(runId + ".yml"));
        } catch (IOException e) {
            return false;
        }
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
                            ((Number) a.getOrDefault("sizeBytes", 0)).longValue(), metaOf(a.get("meta"))));
                }
            }
        }
        String env = str.apply("env");
        return new RunSnapshot(str.apply("runId"), str.apply("projectId"),
                env == null || env.isBlank() ? "test" : env,
                str.apply("pipelineId"),
                str.apply("gitSha"), StageStatus.valueOf(str.apply("status")),
                m.get("createdAt") instanceof Number n ? n.longValue() : 0L,
                m.get("seq") instanceof Number seq ? seq.intValue() : 0,
                stages, artifacts, String.valueOf(m.getOrDefault("log", "")),
                m.get("definitionSource") == null || String.valueOf(m.get("definitionSource")).isBlank()
                        ? null : String.valueOf(m.get("definitionSource")),
                m.get("definitionNote") == null || String.valueOf(m.get("definitionNote")).isBlank()
                        ? null : String.valueOf(m.get("definitionNote")));
    }

    /** 读取产物 meta（旧文件没有该字段，按空处理）。 */
    private static Map<String, String> metaOf(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> meta = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null && v != null) {
                meta.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return meta;
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
        m.put("meta", a.meta() == null ? Map.of() : a.meta());
        return m;
    }

    /** 一次运行的不可变快照。 */
    public record RunSnapshot(String runId, String projectId, String env, String pipelineId, String gitSha,
                              StageStatus status, long createdAt, int seq, List<StageResult> stages,
                              List<ArtifactRef> artifacts, String log,
                              String definitionSource, String definitionNote) {

        /** 旧文件没有 seq：补号时保持其它字段不变。 */
        public RunSnapshot withSeq(int value) {
            return new RunSnapshot(runId, projectId, env, pipelineId, gitSha, status, createdAt,
                    value, stages, artifacts, log, definitionSource, definitionNote);
        }
    }
}
