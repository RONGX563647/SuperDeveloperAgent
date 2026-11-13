package top.rongx.agent.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.Yaml;
import top.rongx.agent.domain.port.PipelineEngine.ImageVersion;

/**
 * 镜像版本账本：每个 (项目, 环境) 一份 YAML，记录发布成功过的镜像版本。
 *
 * <p>与构建历史（{@link RunHistoryStore}）的职责区别：
 * <ul>
 *   <li>历史是"每次运行"的流水账，删历史是用户的日常操作；</li>
 *   <li>版本账本是"这个环境部署过哪些镜像"的正式记录，删历史不能让它消失，
 *       否则回滚锚点/按版本部署会凭空丢版本。</li>
 * </ul>
 *
 * <p>文件位置：{@code <workspaceRoot>/_images/<projectId>-<env>.yml}。
 * 单机平台用 YAML 足够；换数据库时只需替换本类。
 */
public class ImageVersionStore {

    private final Path dir;
    private final boolean enabled;

    private ImageVersionStore(Path dir, boolean enabled) {
        this.dir = dir;
        this.enabled = enabled;
    }

    public static ImageVersionStore file(Path workspaceRoot) {
        Path dir = workspaceRoot.resolve("_images");
        try {
            Files.createDirectories(dir);
            return new ImageVersionStore(dir, true);
        } catch (IOException e) {
            return disabled();
        }
    }

    public static ImageVersionStore disabled() {
        return new ImageVersionStore(Path.of("."), false);
    }

    /** 发布成功后登记/更新一个版本（同一 tag 覆盖：同一提交重复发布只保留一条）。 */
    public synchronized void upsert(String projectId, String env, ImageVersion version) {
        if (!enabled || projectId == null || env == null || version == null
                || version.tag() == null || version.tag().isBlank()) {
            return;
        }
        List<ImageVersion> rows = read(projectId, env);
        rows.removeIf(v -> version.tag().equals(v.tag()));
        rows.add(version);
        write(projectId, env, rows);
    }

    /** 该环境下部署过的版本（最近部署在前）；current / previous 由调用方按序取前两条。 */
    public synchronized List<ImageVersion> list(String projectId, String env) {
        if (!enabled) {
            return List.of();
        }
        List<ImageVersion> rows = read(projectId, env);
        rows.sort(Comparator.comparingLong(ImageVersionStore::sortKey).reversed());
        return rows;
    }

    /** 当前版本（最近一次成功发布的镜像），即回滚锚点。 */
    public synchronized Optional<ImageVersion> current(String projectId, String env) {
        return list(projectId, env).stream().findFirst();
    }

    /** 按 tag 查版本；不存在返回空。 */
    public synchronized Optional<ImageVersion> find(String projectId, String env, String tag) {
        if (tag == null) {
            return Optional.empty();
        }
        return list(projectId, env).stream()
                .filter(v -> tag.equals(v.tag()))
                .findFirst();
    }

    /** 移除一个版本（镜像清理后同步账本）。 */
    public synchronized void remove(String projectId, String env, String tag) {
        if (!enabled || tag == null) {
            return;
        }
        List<ImageVersion> rows = read(projectId, env);
        if (rows.removeIf(v -> tag.equals(v.tag()))) {
            write(projectId, env, rows);
        }
    }

    /** 排序键：优先部署时间，旧记录没有部署时间时退回构建时间。 */
    private static long sortKey(ImageVersion v) {
        return v.deployedAt() > 0 ? v.deployedAt() : v.createdAt();
    }

    private Path fileOf(String projectId, String env) {
        String safeProject = projectId.replaceAll("[^A-Za-z0-9._-]", "_");
        String safeEnv = env.replaceAll("[^A-Za-z0-9._-]", "_");
        return dir.resolve(safeProject + "-" + safeEnv + ".yml");
    }

    @SuppressWarnings("unchecked")
    private List<ImageVersion> read(String projectId, String env) {
        Path file = fileOf(projectId, env);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            Object loaded = new Yaml().load(Files.readString(file));
            if (!(loaded instanceof Map<?, ?> raw)) {
                return new ArrayList<>();
            }
            Object images = ((Map<String, Object>) raw).get("images");
            if (!(images instanceof List<?> list)) {
                return new ArrayList<>();
            }
            List<ImageVersion> rows = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> item)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) item;
                rows.add(new ImageVersion(
                        str(m.get("tag")),
                        str(m.get("digest")),
                        num(m.get("sizeBytes")),
                        str(m.get("version")),
                        str(m.get("gitSha")),
                        str(m.get("runId")),
                        str(m.get("status")),
                        num(m.get("createdAt")),
                        num(m.get("deployedAt")),
                        str(m.get("deployedBy")),
                        (int) num(m.get("seq"))));
            }
            return rows;
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    private void write(String projectId, String env, List<ImageVersion> rows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectId", projectId);
        data.put("env", env);
        data.put("images", rows.stream().map(ImageVersionStore::toMap).toList());
        try {
            Files.writeString(fileOf(projectId, env), new Yaml().dump(data));
        } catch (IOException ignored) {
            // 账本写入失败不阻断发布（下一版会再写）
        }
    }

    private static Map<String, Object> toMap(ImageVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", v.tag());
        m.put("digest", v.digest() == null ? "" : v.digest());
        m.put("sizeBytes", v.sizeBytes());
        m.put("version", v.version() == null ? "" : v.version());
        m.put("gitSha", v.gitSha() == null ? "" : v.gitSha());
        m.put("runId", v.runId() == null ? "" : v.runId());
        m.put("status", v.status() == null ? "" : v.status());
        m.put("createdAt", v.createdAt());
        m.put("deployedAt", v.deployedAt());
        m.put("deployedBy", v.deployedBy() == null ? "" : v.deployedBy());
        m.put("seq", v.seq());
        return m;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
