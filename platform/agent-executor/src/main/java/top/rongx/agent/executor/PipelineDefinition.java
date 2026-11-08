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
 * <p>v3（方案 C）新增 {@code deploy:} 段：声明"部署到哪、哪个端口"，
 * 由<b>平台部署器</b>执行；仓库不再提供部署脚本。
 *
 * @param name      流水线名
 * @param stages    有序 stage
 * @param onFailure 失败时执行的补救动作（通常是回滚）
 * @param deploy    部署声明（可为 null：纯构建流水线）
 */
public record PipelineDefinition(String name, List<StageDef> stages,
                                 List<StageDef> onFailure, DeploySpec deploy) {

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

    /**
     * 部署声明。
     *
     * @param target        默认部署目标：docker / host / server（运行时可被触发参数覆盖）
     * @param port          对外端口（docker 映射 / host 监听）
     * @param containerPort 容器内端口（docker）
     * @param healthPath    健康检查路径，如 {@code /healthz}
     * @param artifact      HOST 目标的产物（相对路径，如 {@code backend/target/app.jar}）；空则自动找 jar
     * @param serverId      SERVER 目标使用的服务器 id（设置页里配置）；空则用默认服务器
     */
    public record DeploySpec(String target, int port, int containerPort, String healthPath,
                             String artifact, String serverId) {

        public static DeploySpec defaults() {
            return new DeploySpec("docker", 8080, 8080, "/healthz", null, null);
        }
    }

    /** 从仓库根读 {@code ci/pipeline.yml}。 */
    public static PipelineDefinition load(Path repoRoot) {
        Path file = repoRoot.resolve("ci/pipeline.yml");
        String content;
        try {
            content = java.nio.file.Files.readString(file);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法读取 " + file + "：" + e.getMessage());
        }
        return parse(content, file.toString());
    }

    /**
     * 解析 YAML 文本（{@code origin} 只用于报错，便于定位是哪个文件/分支）。
     *
     * <p>读不到文件与解析失败分开报：前者是"没有定义"，后者是"定义写错了"，
     * 两种失败的处置方式完全不同（生成 vs 修 YAML）。
     */
    public static PipelineDefinition parse(String content, String origin) {
        Object loaded;
        try {
            loaded = new Yaml().load(content);
        } catch (Exception e) {
            throw new IllegalArgumentException(origin + " 不是合法 YAML：" + e.getMessage(), e);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException(origin + " 不是合法的 YAML 映射");
        }
        String name = str(root, "name", "pipeline");
        List<StageDef> stages = parseStages(root.get("stages"), "stages");
        List<StageDef> onFailure = parseStages(root.get("on-failure"), "on-failure");
        if (stages.isEmpty()) {
            throw new IllegalArgumentException(origin + " 没有定义任何 stage");
        }
        return new PipelineDefinition(name, List.copyOf(stages), List.copyOf(onFailure),
                parseDeploy(root.get("deploy")));
    }

    private static DeploySpec parseDeploy(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        int port = map.get("port") instanceof Number p ? p.intValue() : 8080;
        int containerPort = map.get("containerPort") instanceof Number c ? c.intValue() : port;
        String artifact = map.get("artifact") == null ? null : String.valueOf(map.get("artifact"));
        String serverId = map.get("serverId") == null ? null : String.valueOf(map.get("serverId"));
        return new DeploySpec(
                str(map, "target", "docker"),
                port,
                containerPort,
                str(map, "healthPath", "/healthz"),
                artifact == null || artifact.isBlank() ? null : artifact,
                serverId == null || serverId.isBlank() ? null : serverId);
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
