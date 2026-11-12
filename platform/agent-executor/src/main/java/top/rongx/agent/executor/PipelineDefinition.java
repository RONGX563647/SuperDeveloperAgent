package top.rongx.agent.executor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;
import top.rongx.agent.domain.action.ActionId;

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
     * @param envRequired   部署必填的环境变量名清单（E-509 预检依据；只声明名字，值在「设置 → 项目环境」维护）
     * @param services      多容器部署（test：前端 + 后端各一个容器）；空 = 单容器模型
     */
    public record DeploySpec(String target, int port, int containerPort, String healthPath,
                             String artifact, String serverId, List<String> envRequired,
                             List<ServiceSpec> services) {

        public DeploySpec(String target, int port, int containerPort, String healthPath,
                          String artifact, String serverId, List<String> envRequired) {
            this(target, port, containerPort, healthPath, artifact, serverId, envRequired, List.of());
        }

        public static DeploySpec defaults() {
            return new DeploySpec("docker", 8080, 8080, "/healthz", null, null, List.of(), List.of());
        }
    }

    /**
     * 单个服务容器（test 环境前后端分离部署）。
     *
     * @param name          服务名：backend / frontend（用于容器名、卷名与反向代理别名）
     * @param image         运行镜像（由前面的构建 stage 产出）
     * @param containerPort 容器内端口
     * @param hostPort      期望宿主端口（0 = 自动在端口段内分配）
     * @param volumePath    需要持久化的容器内路径（空 = 不挂卷）
     * @param envRequired   该服务必填变量（空 = 沿用 deploy.env-required）
     */
    public record ServiceSpec(String name, String image, int containerPort, int hostPort,
                              String volumePath, List<String> envRequired) {
    }

    /**
     * 「检查构建」用的切片：去掉部署/健康检查/回滚与镜像打包，只留构建与测试。
     *
     * <p>预览与执行共用这一个方法，避免两处 filter 各自漂移（预览≠实跑）。
     * 镜像打包默认去掉：它是最贵的一步，而检查构建只回答"能不能编过"；
     * Dockerfile 的问题交给测试环境暴露，或在触发时显式要求保留镜像。
     *
     * @param keepImage 是否保留 {@code image_package}
     */
    public PipelineDefinition forVerify(boolean keepImage) {
        return new PipelineDefinition(name,
                stages.stream()
                        .filter(s -> !"health".equals(s.type()))
                        .filter(s -> !"deploy".equals(s.type()))
                        .filter(s -> !"rollback".equals(s.type()))
                        .filter(s -> !"stack_deploy".equals(s.action()))
                        .filter(s -> keepImage || !"image_package".equals(s.action()))
                        .toList(),
                List.of(), null);
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
                serverId == null || serverId.isBlank() ? null : serverId,
                parseEnvRequired(map),
                parseServices(map));
    }

    /**
     * 解析 {@code deploy.services}（多容器部署）：
     *
     * <pre>
     * deploy:
     *   services:
     *     - name: backend
     *       image: my-backend:latest
     *       containerPort: 8080
     *     - name: frontend
     *       image: my-frontend:latest
     *       containerPort: 80
     * </pre>
     */
    private static List<ServiceSpec> parseServices(Map<?, ?> deployMap) {
        Object raw = deployMap.get("services");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ServiceSpec> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String name = str(m, "name", "").trim();
            String image = m.get("image") == null ? "" : String.valueOf(m.get("image")).trim();
            if (name.isEmpty() || image.isEmpty()) {
                throw new IllegalArgumentException("deploy.services 每项必须声明 name 与 image");
            }
            if (!name.matches("[a-z0-9][a-z0-9-]{0,31}")) {
                throw new IllegalArgumentException(
                        "deploy.services.name 只能用小写字母/数字/短横线：" + name);
            }
            for (ServiceSpec existing : out) {
                if (existing.name().equals(name)) {
                    throw new IllegalArgumentException("deploy.services 服务名重复：" + name);
                }
            }
            int containerPort = m.get("containerPort") instanceof Number c ? c.intValue() : 0;
            if (containerPort <= 0 || containerPort > 65535) {
                throw new IllegalArgumentException(
                        "deploy.services[" + name + "].containerPort 必须是 1–65535");
            }
            int hostPort = m.get("hostPort") instanceof Number h ? h.intValue() : 0;
            String volumePath = m.get("volume") == null ? null : String.valueOf(m.get("volume"));
            out.add(new ServiceSpec(name, image, containerPort, hostPort,
                    volumePath == null || volumePath.isBlank() ? null : volumePath,
                    parseEnvRequired(m)));
        }
        return List.copyOf(out);
    }

    /**
     * 解析 {@code env-required} 清单：支持两种写法——
     * 纯字符串列表（{@code - DB_HOST}）或带属性的映射（{@code - name: DB_HOST}）。
     * 只保留变量名：值属于「设置 → 项目环境」，绝不进流水线定义（定义会落盘/入库）。
     */
    private static List<String> parseEnvRequired(Map<?, ?> deployMap) {
        Object raw = deployMap.get("env-required");
        if (raw == null) {
            raw = deployMap.get("envRequired");
        }
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String name = null;
            if (item instanceof String s) {
                name = s;
            } else if (item instanceof Map<?, ?> m) {
                Object n = m.get("name");
                name = n == null ? null : String.valueOf(n);
            }
            if (name != null && !name.isBlank()) {
                String key = name.trim();
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("env-required 变量名非法：" + key);
                }
                if (!out.contains(key)) {
                    out.add(key);
                }
            }
        }
        return List.copyOf(out);
    }

    /** 唯一允许的 stage 类型；写错类型在解析期就报错（而不是运行期才变成 E-2xx）。 */
    private static final Set<String> STAGE_TYPES =
            Set.of("action", "deploy", "health", "rollback", "git-checkout");

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
            if (type.isBlank()) {
                type = "action";
            }
            String action = m.get("action") == null ? null : str(m, "action", "");
            if (!STAGE_TYPES.contains(type)) {
                throw new IllegalArgumentException(field + " 中 stage 类型非法：" + type
                        + "（可选：" + String.join(" / ", STAGE_TYPES) + "）");
            }
            if ("action".equals(type)
                    && (action == null || action.isBlank() || ActionId.fromWire(action).isEmpty())) {
                throw new IllegalArgumentException(field + " 中 action 类型必须声明白名单动作名，实际为："
                        + (action == null || action.isBlank() ? "（空）" : action));
            }
            out.add(new StageDef(
                    str(m, "id", "stage-" + (out.size() + 1)),
                    type,
                    action,
                    str(m, "runner", "container"),
                    m.get("workdir") == null ? null : str(m, "workdir", ""),
                    m.get("image") == null ? null : str(m, "image", ""),
                    m.get("url") == null ? null : str(m, "url", ""),
                    expectedOf(m.get("expected"))));
        }
        return out;
    }

    /**
     * 解析健康检查期望码：数字直接取；{@code 2xx}（大小写不敏感）用 0 表示"任意 2xx"。
     * 其他写法视为非法定义，避免"看起来配了其实没配"。
     */
    private static int expectedOf(Object raw) {
        if (raw == null) {
            return 200;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.equalsIgnoreCase("2xx")) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "health.expected 只能是状态码或 2xx，实际为：" + text);
        }
    }

    /** 从 {@code Map<?,?>} 里安全取字符串（避开通配符捕获的泛型问题）。 */
    private static String str(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
