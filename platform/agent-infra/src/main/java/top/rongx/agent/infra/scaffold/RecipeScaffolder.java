package top.rongx.agent.infra.scaffold;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.ProjectScaffolder;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;
import top.rongx.agent.domain.scaffold.ScaffoldPlan.ScaffoldFile;

/**
 * 平台专用流水线脚手架：导入项目后，按仓库结构自动生成 {@code ci/} 脚本。
 *
 * <p>识别规则（只读扫描，不执行任何命令）：
 * <ul>
 *   <li>{@code pom.xml} / {@code build.gradle} → Java 构建（mvn / gradle 命令）</li>
 *   <li>{@code package.json} → Node 构建（按锁文件选 pnpm / npm）</li>
 *   <li>{@code Dockerfile} → 镜像打包 stage</li>
 *   <li>{@code backend/} + {@code frontend/} → monorepo，按子目录写 workdir</li>
 * </ul>
 *
 * <p>生成结果必须"开箱可跑"：stage 只引用白名单动作，部署走平台 deploy 段。
 * 不覆盖已有文件（除非 overwrite=true），避免误吞人工调过的配置。
 */
@Component
public class RecipeScaffolder implements ProjectScaffolder {

    private static final Logger log = LoggerFactory.getLogger(RecipeScaffolder.class);

    /** 生成目标：只允许 ci/ 下的固定文件名。 */
    private static final String PIPELINE_FILE = "ci/pipeline.yml";
    private static final String ACTIONS_FILE = "ci/actions.yml";

    @Override
    public ScaffoldPlan plan(String projectId, Path repoRoot, DeployTarget preferredTarget) {
        List<String> notes = new ArrayList<>();
        DeployTarget target = preferredTarget == null ? DeployTarget.DOCKER : preferredTarget;

        // 识别工程根：根目录 → 约定子目录（backend/frontend）→ 一层子目录（如 RuoYi-Vue/）。
        // 很多真实仓库是"仓库根 + 一个工程目录"，旧规则只认根/backend/frontend，会把它们误判成 UNKNOWN。
        String javaDir = detectJavaDir(repoRoot);
        String nodeDir = detectNodeDir(repoRoot, javaDir);
        boolean hasDockerfile = isFile(repoRoot.resolve("Dockerfile"));
        boolean dockerBuilds = hasDockerfile && buildsInsideDockerfile(repoRoot.resolve("Dockerfile"));
        Ports ports = detectPorts(repoRoot.resolve("Dockerfile"), hasDockerfile);

        String stack;
        List<StageSpec> stages = new ArrayList<>();
        Map<String, String> actions = new LinkedHashMap<>();

        if (javaDir != null || nodeDir != null) {
            stack = javaDir != null && nodeDir != null ? "JAVA_NODE" : javaDir != null ? "JAVA" : "NODE";
            if (dockerBuilds) {
                notes.add("Dockerfile 内已完成编译/打包（检测到 mvn / gradle / npm 等），"
                        + "不再重复生成构建 stage");
            } else {
                if (javaDir != null) {
                    Path javaRoot = javaDir.isEmpty() ? repoRoot : repoRoot.resolve(javaDir);
                    boolean gradle = isFile(javaRoot.resolve("build.gradle"))
                            || isFile(javaRoot.resolve("build.gradle.kts"));
                    String workdir = javaDir.isEmpty() ? null : javaDir;
                    // 一个 stage 完成"编译 + 测试 + 打包"：拆成 build/test 两个 stage 会让
                    // Maven 把同一批源码编译两遍（先 -DskipTests package，再 test），纯浪费。
                    stages.add(new StageSpec("build", "backend_build", "container", workdir, null));
                    actions.put("backend_build", gradle ? "gradle -q build" : "mvn -B -q verify");
                }
                if (nodeDir != null) {
                    Path nodeRoot = nodeDir.isEmpty() ? repoRoot : repoRoot.resolve(nodeDir);
                    String pm = detectPackageManager(nodeRoot);
                    notes.add("前端包管理器识别为 " + pm);
                    String workdir = nodeDir.isEmpty() ? null : nodeDir;
                    stages.add(new StageSpec("frontend", "frontend_build", "container", workdir, null));
                    actions.put("frontend_build", pm.equals("npm")
                            ? "npm ci --ignore-scripts && npm run build"
                            : pm + " install --frozen-lockfile --ignore-scripts && " + pm + " build");
                    // 有真实 test 脚本才生成测试 stage：npm init 的占位脚本会直接失败
                    if (hasRealTestScript(nodeRoot)) {
                        stages.add(new StageSpec("frontend-test", "frontend_test", "container", workdir, null));
                        actions.put("frontend_test", pm + " test");
                    } else {
                        notes.add("package.json 没有可用的 test 脚本：已跳过前端测试 stage");
                    }
                }
            }
        } else {
            stack = "UNKNOWN";
            notes.add("未识别出 Java/Node 工程：已生成骨架，请按需修改 stages/actions");
        }

        if (hasDockerfile && target == DeployTarget.DOCKER) {
            stages.add(new StageSpec("image", "image_package", "host", null, null));
            actions.put("image_package", "docker build -q -t ${tag} .");
        } else if (target == DeployTarget.DOCKER) {
            notes.add("未找到 Dockerfile：已跳过 image stage；DOCKER 目标需要镜像，"
                    + "部署会失败（E-501）。请补 Dockerfile，或改用 HOST / SERVER");
        }
        if (target == DeployTarget.HOST) {
            notes.add("HOST 目标只在 Java jar 场景可用；前端静态产物请用 DOCKER");
        }
        if (target == DeployTarget.SERVER) {
            notes.add("SERVER 目标需要配置 agent.deploy.server.host 与 key-path（见部署文档 §4）");
        }
        if (hasDockerfile) {
            notes.add("按 Dockerfile 检测到端口 " + ports.port() + "、健康路径 " + ports.healthPath()
                    + "（可用平台侧 ci/pipeline.yml 覆盖）");
        }

        // 应用自己声明的必填 env（docker/.env.example 等）→ 写进 deploy.env-required，
        // 部署前由引擎做 E-509 预检；值本身仍归「设置 → 项目环境」，定义里只留名字。
        List<String> envRequired = detectRequiredEnv(repoRoot);
        if (!envRequired.isEmpty()) {
            notes.add("按 .env.example 检测到 " + envRequired.size() + " 个必填环境变量："
                    + String.join("、", envRequired)
                    + "（已写入 deploy.env-required，部署前预检；请到「设置 → 项目环境」配置）");
        }

        // 所有目标都走平台部署器 + 健康门禁（SERVER 亦同；未配置时 fail-closed）
        List<StageSpec> pipelineStages = new ArrayList<>(stages);
        pipelineStages.add(new StageSpec("deploy", "deploy", null, null, target));
        pipelineStages.add(new StageSpec("health", "health", null, null, target));

        String pipelineYaml = renderPipeline(pipelineStages, target, ports, envRequired);
        String actionsYaml = renderActions(actions);

        List<ScaffoldFile> files = List.of(
                new ScaffoldFile(PIPELINE_FILE, pipelineYaml),
                new ScaffoldFile(ACTIONS_FILE, actionsYaml));

        return new ScaffoldPlan(projectId, stack, files, List.copyOf(notes));
    }

    @Override
    public List<String> apply(String projectId, Path repoRoot, ScaffoldPlan plan, boolean overwrite) {
        List<String> written = new ArrayList<>();
        for (ScaffoldFile file : plan.files()) {
            Path target = repoRoot.resolve(file.path()).normalize();
            if (!target.startsWith(repoRoot.normalize())) {
                log.warn("拒绝写入仓库之外的路径：{}", target);
                continue;
            }
            if (Files.isRegularFile(target) && !overwrite) {
                log.info("跳过已存在文件（overwrite=false）：{}", file.path());
                continue;
            }
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.content(), StandardCharsets.UTF_8);
                written.add(file.path());
            } catch (IOException e) {
                throw new IllegalStateException("写入失败 " + file.path() + "：" + e.getMessage(), e);
            }
        }
        return written;
    }

    /** stage 描述（生成期中间结构）。 */
    private record StageSpec(String id, String action, String runner, String workdir, DeployTarget deployTarget) {
    }

    private String renderPipeline(List<StageSpec> stages, DeployTarget target, Ports ports,
                                  List<String> envRequired) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 由平台脚手架生成（可自由修改，改动随代码 review）\n");
        sb.append("name: release\n\n");
        sb.append("deploy:\n")
                .append("  target: ").append(target.wireName()).append('\n')
                .append("  port: ").append(ports.port()).append('\n')
                .append("  containerPort: ").append(ports.port()).append('\n')
                .append("  healthPath: ").append(ports.healthPath()).append('\n');
        if (envRequired != null && !envRequired.isEmpty()) {
            sb.append("  env-required:\n");
            for (String key : envRequired) {
                sb.append("    - ").append(key).append('\n');
            }
        }
        sb.append('\n');
        sb.append("stages:\n");
        for (StageSpec stage : stages) {
            sb.append("  - id: ").append(stage.id()).append('\n');
            if ("deploy".equals(stage.id())) {
                sb.append("    type: deploy\n");
            } else if ("health".equals(stage.id())) {
                sb.append("    type: health\n")
                        .append("    url: http://127.0.0.1:").append(ports.port())
                        .append(ports.healthPath()).append('\n')
                        .append("    expected: 200\n");
            } else {
                sb.append("    type: action\n")
                        .append("    action: ").append(stage.action()).append('\n')
                        .append("    runner: ").append(stage.runner() == null ? "container" : stage.runner())
                        .append('\n');
                if (stage.workdir() != null) {
                    sb.append("    workdir: ").append(stage.workdir()).append('\n');
                }
            }
        }
        sb.append("\non-failure:\n")
                .append("  - id: rollback\n")
                .append("    type: rollback\n");
        return sb.toString();
    }

    private String renderActions(Map<String, String> actions) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 由平台脚手架生成：动作 → 命令（argv 形式，不经 shell）\n");
        sb.append("actions:\n");
        for (Map.Entry<String, String> e : actions.entrySet()) {
            sb.append("  ").append(e.getKey()).append(":\n");
            sb.append("    run:\n");
            for (String argv : splitArgv(e.getValue())) {
                sb.append("      - ").append(argvBlock(argv)).append('\n');
            }
            sb.append("    timeout: ").append(timeoutFor(e.getKey())).append('\n');
        }
        return sb.toString();
    }

    /** 动作级超时：测试类给足（20m），构建/镜像按经验值，其余用 15m 兜底。 */
    private String timeoutFor(String action) {
        return switch (action) {
            case "backend_build", "frontend_build" -> "10m";
            case "backend_test", "frontend_test" -> "20m";
            case "image_package" -> "20m";
            default -> "15m";
        };
    }

    /** 极简 argv 渲染：单条命令（含 && 的安装+构建）拆成两行更容易读。 */
    private List<String> splitArgv(String command) {
        String[] parts = command.split("&&");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            out.add(part.trim());
        }
        return out;
    }

    private String argvBlock(String argvLine) {
        String[] tokens = argvLine.split("\\s+");
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(tokens[i].replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * package.json 里是否有"真正的" test 脚本。
     *
     * <p>只做文本匹配、不引入 JSON 解析：脚手架只需要"有/没有"这个判断。
     * `npm init` 生成的占位脚本（echo "Error: no test specified" && exit 1）视为没有。
     */
    private boolean hasRealTestScript(Path nodeRoot) {
        Path pkg = nodeRoot.resolve("package.json");
        if (!Files.isRegularFile(pkg)) {
            return false;
        }
        try {
            String content = Files.readString(pkg);
            if (!content.matches("(?s).*\"test\"\\s*:.*")) {
                return false;
            }
            return !content.contains("no test specified");
        } catch (IOException e) {
            return false;
        }
    }

    private String detectPackageManager(Path dir) {
        if (isFile(dir.resolve("pnpm-lock.yaml"))) {
            return "pnpm";
        }
        if (isFile(dir.resolve("yarn.lock"))) {
            return "yarn";
        }
        return "npm";
    }

    /** 常见前端子目录名（RuoYi 这类"Java 工程目录下再放一个 UI"的布局）。 */
    private static final List<String> UI_DIRS = List.of("ruoyi-ui", "ui", "web", "frontend", "admin-ui", "vue");

    /**
     * 检测 Java 工程根（返回相对仓库根的路径；{@code ""} 表示仓库根，{@code null} 表示没找到）。
     *
     * <p>顺序：仓库根 → 约定的 {@code backend/} → 一层子目录（按名称排序，取第一个带构建文件的）。
     */
    private String detectJavaDir(Path repoRoot) {
        if (javaMarkers(repoRoot)) {
            return "";
        }
        if (javaMarkers(repoRoot.resolve("backend"))) {
            return "backend";
        }
        for (Path dir : childDirs(repoRoot)) {
            if (javaMarkers(dir)) {
                return relative(repoRoot, dir);
            }
        }
        return null;
    }

    /** 检测 Node 工程根（优先 Java 工程内的 UI 子目录，其次根/前端约定目录/一层子目录）。 */
    private String detectNodeDir(Path repoRoot, String javaDir) {
        if (isFile(repoRoot.resolve("package.json"))) {
            return "";
        }
        if (isFile(repoRoot.resolve("frontend/package.json"))) {
            return "frontend";
        }
        if (javaDir != null && !javaDir.isEmpty()) {
            Path javaRoot = repoRoot.resolve(javaDir);
            for (String ui : UI_DIRS) {
                if (isFile(javaRoot.resolve(ui).resolve("package.json"))) {
                    return javaDir + "/" + ui;
                }
            }
            for (Path dir : childDirs(javaRoot)) {
                if (isFile(dir.resolve("package.json"))) {
                    return relative(repoRoot, dir);
                }
            }
        }
        for (Path dir : childDirs(repoRoot)) {
            String rel = relative(repoRoot, dir);
            if (!rel.equals(javaDir) && isFile(dir.resolve("package.json"))) {
                return rel;
            }
        }
        return null;
    }

    private boolean javaMarkers(Path dir) {
        return isFile(dir.resolve("pom.xml"))
                || isFile(dir.resolve("build.gradle"))
                || isFile(dir.resolve("build.gradle.kts"));
    }

    private List<Path> childDirs(Path parent) {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        try (var stream = Files.list(parent)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.startsWith(".") && !"node_modules".equals(name)
                                && !"target".equals(name) && !"dist".equals(name);
                    })
                    .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .limit(20)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String relative(Path root, Path dir) {
        return root.relativize(dir).toString().replace('\\', '/');
    }

    /**
     * Dockerfile 是否自己完成了编译/打包。
     *
     * <p>是的话不再生成 build/test stage：重复编译既慢，也容易和镜像内的工具链版本不一致。
     */
    private boolean buildsInsideDockerfile(Path dockerfile) {
        try {
            for (String line : Files.readAllLines(dockerfile, StandardCharsets.UTF_8)) {
                String text = line.trim().toLowerCase(java.util.Locale.ROOT);
                if (text.startsWith("run ")
                        && text.matches(".*\\b(mvn|mvnw|gradlew?|npm|pnpm|yarn)\\b.*")) {
                    return true;
                }
            }
        } catch (IOException e) {
            // 读不了就当它不构建，交给 build stage 提供确定性检查
        }
        return false;
    }

    /** 从 Dockerfile 推断对外端口与健康检查路径（EXPOSE + HEALTHCHECK URL）。 */
    private Ports detectPorts(Path dockerfile, boolean hasDockerfile) {
        if (!hasDockerfile) {
            return new Ports(8080, "/healthz");
        }
        String text;
        try {
            text = Files.readString(dockerfile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Ports(8080, "/");
        }
        Integer healthPort = null;
        String healthPath = null;
        java.util.regex.Matcher health = java.util.regex.Pattern
                .compile("https?://(?:localhost|127\\.0\\.0\\.1):(\\d+)(/[^\\s\"'\\\\]*)?").matcher(text);
        if (health.find()) {
            healthPort = Integer.valueOf(health.group(1));
            String path = health.group(2);
            healthPath = path == null || path.isBlank() ? "/" : path;
        }
        List<Integer> exposed = new ArrayList<>();
        java.util.regex.Matcher expose = java.util.regex.Pattern
                .compile("(?im)^\\s*EXPOSE\\s+(.+)$").matcher(text);
        while (expose.find()) {
            for (String token : expose.group(1).trim().split("\\s+")) {
                try {
                    exposed.add(Integer.parseInt(token));
                } catch (NumberFormatException ignored) {
                    // EXPOSE 里可能有变量，忽略
                }
            }
        }
        int port = healthPort != null ? healthPort : exposed.isEmpty() ? 8080 : exposed.get(0);
        return new Ports(port, healthPath == null ? "/" : healthPath);
    }

    /** 端口/健康路径（脚手架内部结构）。 */
    private record Ports(int port, String healthPath) {
    }

    /**
     * 探测应用声明的必填环境变量：扫描 {@code docker/.env.example} 与 {@code .env.example}
     * （含一层子目录，如 {@code RuoYi-Vue/docker/.env.example}），取 {@code KEY=} 行的名字清单。
     *
     * <p>只取名字、不取值：示例值多为占位符，真实值归「设置 → 项目环境」加密维护。
     */
    private List<String> detectRequiredEnv(Path repoRoot) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(repoRoot.resolve("docker").resolve(".env.example"));
        candidates.add(repoRoot.resolve(".env.example"));
        try (var dirs = Files.list(repoRoot)) {
            dirs.filter(Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().startsWith("."))
                    .forEach(d -> {
                        candidates.add(d.resolve("docker").resolve(".env.example"));
                        candidates.add(d.resolve(".env.example"));
                    });
        } catch (IOException ignored) {
            // 列不出子目录：只查根级候选
        }
        List<String> out = new ArrayList<>();
        for (Path file : candidates) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, eq).trim();
                    if (key.matches("[A-Za-z_][A-Za-z0-9_]*") && !out.contains(key)) {
                        out.add(key);
                    }
                }
            } catch (IOException e) {
                log.warn("读取 {} 失败：{}", file, e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    private boolean isFile(Path path) {
        return Files.isRegularFile(path);
    }
}
