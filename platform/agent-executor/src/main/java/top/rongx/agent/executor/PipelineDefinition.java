package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * 流水线定义（来自项目仓库 {@code ci/pipeline.yml}，随代码版本化）。
 *
 * <p>这是「数据」不是「代码」：stage 只能从白名单里选，不能写 Groovy / shell 片段。
 *
 * @param name      流水线名
 * @param stages    有序 stage
 * @param onFailure 失败时执行的补救动作（通常是回滚）
 */
public record PipelineDefinition(String name, List<StageDef> stages, List<StageDef> onFailure) {

    /**
     * stage 定义。
     *
     * @param workdir 可选：相对仓库根的工作目录（monorepo 必备，如 {@code backend} / {@code frontend}）
     * @param image   可选：该 stage 专用工具链镜像
     * @param url     type=health 时
     * @param expected type=health 时期望状态码
     */
    public record StageDef(
            String id,
            String type,
            String action,
            String runner,
            String workdir,
            String image,
            String url,
            int expected) {
    }

    /** 从仓库根读 {@code ci/pipeline.yml}。 */
    public static PipelineDefinition load(Path repoRoot) {
        Path file = repoRoot.resolve("ci/pipeline.yml");
        Object loaded;
        try {
            loaded = new Yaml().load(java.nio.file.Files.readString(file));
        } catch (Exception e) {
            throw new IllegalArgumentException("无法读取 " + file + "：" + e.getMessage());
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException(file + " 不是合法的 YAML 映射");
        }
        String name = str(root, "name", "pipeline");
        List<StageDef> stages = parseStages(root.get("stages"), "stages");
        List<StageDef> onFailure = parseStages(root.get("on-failure"), "on-failure");
        if (stages.isEmpty()) {
            throw new IllegalArgumentException(file + " 没有定义任何 stage");
        }
        return new PipelineDefinition(name, List.copyOf(stages), List.copyOf(onFailure));
    }

    private static List<StageDef> parseStages(Object raw, String field) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<StageDef> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String type = str(m, "type", "action");
            String action = m.get("action") == null ? null : str(m, "action", "");
            if ("action".equals(type) && (action == null || action.isBlank())) {
                throw new IllegalArgumentException(field + " 中 action 类型必须声明 action 名");
            }
            out.add(new StageDef(
                    str(m, "id", "stage-" + (out.size() + 1)),
                    type,
                    action,
                    str(m, "runner", "container"),
                    m.get("workdir") == null ? null : str(m, "workdir", ""),
                    m.get("image") == null ? null : str(m, "image", ""),
                    m.get("url") == null ? null : str(m, "url", ""),
                    m.get("expected") instanceof Number n ? n.intValue() : 200));
        }
        return out;
    }

    /** 从 {@code Map<?,?>} 里安全取字符串（避开通配符捕获的泛型问题）。 */
    private static String str(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
