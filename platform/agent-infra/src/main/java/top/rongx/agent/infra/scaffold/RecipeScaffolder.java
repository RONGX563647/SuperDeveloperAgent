package top.rongx.agent.infra.scaffold;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
 *   <li>{@code package.json} → Node 构建（按锁文件选 pnpm / npm / yarn，缺锁文件自动降级 install）</li>
 *   <li>{@code Dockerfile} → 镜像打包 stage；没有时按技术栈自动选择可用目标，选不了就明确报阻塞</li>
 *   <li>{@code application*.yml/properties} → 端口与健康路径（优先于猜默认值）</li>
 * </ul>
 *
 * <p>生成结果必须"开箱可跑"：stage 只引用真实存在的动作，部署目标必须与仓库能力匹配。
 * 不覆盖已有文件（除非 overwrite=true），避免误吞人工调过的配置。
 */
@Component
public class RecipeScaffolder implements ProjectScaffolder {

    private static final Logger log = LoggerFactory.getLogger(RecipeScaffolder.class);

    /** 生成目标：只允许 ci/ 下的固定文件名。 */
    private static final String PIPELINE_FILE = "ci/pipeline.yml";
    private static final String ACTIONS_FILE = "ci/actions.yml";

    private static final String DEFAULT_PORT_NOTE = "默认值（未探测到 server.port，请确认）";
    private static final String DEFAULT_HEALTH_NOTE = "默认值（未探测到健康端点，请确认）";

    @Override
    public ScaffoldPlan plan(String projectId, Path repoRoot, DeployTarget preferredTarget) {
        List<Diag> diags = new ArrayList<>();

        // 识别工程根：根目录 → 约定子目录（backend/frontend）→ 一层子目录（如 RuoYi-Vue/）。
        String javaDir = detectJavaDir(repoRoot);
        String nodeDir = detectNodeDir(repoRoot, javaDir);
        boolean hasDockerfile = isFile(repoRoot.resolve("Dockerfile"));
        boolean dockerBuilds = hasDockerfile && buildsInsideDockerfile(repoRoot.resolve("Dockerfile"));
        ServerConfig server = detectServerConfig(repoRoot, javaDir, hasDockerfile);

        DeployTarget requested = preferredTarget == null ? DeployTarget.DOCKER : preferredTarget;
        TargetChoice choice = decideTarget(requested, hasDockerfile, javaDir, nodeDir);
        DeployTarget target = choice.target();
        diags.addAll(choice.diags());

        String stack;
        List<StageSpec> stages = new ArrayList<>();
        Map<String, String> actions = new LinkedHashMap<>();

        if (javaDir != null || nodeDir != null) {
            stack = javaDir != null && nodeDir != null ? "JAVA_NODE" : javaDir != null ? "JAVA" : "NODE";
            if (dockerBuilds) {
                diags.add(Diag.info("DOCKERFILE_BUILDS",
                        "Dockerfile 内已完成编译/打包（检测到 mvn / gradle / npm 等），不重复生成构建 stage"));
            } else {
                if (javaDir != null) {
                    Path javaRoot = javaDir.isEmpty() ? repoRoot : repoRoot.resolve(javaDir);
                    boolean gradle = isFile(javaRoot.resolve("build.gradle"))
                            || isFile(javaRoot.resolve("build.gradle.kts"));
                    stages.add(new StageSpec("build", "backend_build", "container",
                            workdirOf(javaDir), null));
                    actions.put("backend_build", gradle ? "gradle -q build" : "mvn -B -q verify");
                }
                if (nodeDir != null) {
                    Path nodeRoot = nodeDir.isEmpty() ? repoRoot : repoRoot.resolve(nodeDir);
                    String pm = detectPackageManager(nodeRoot);
                    boolean lock = hasLockFile(nodeRoot, pm);
                    diags.add(Diag.info("PACKAGE_MANAGER", "前端包管理器识别为 " + pm
                            + (lock ? "（已找到锁文件）" : "（未找到锁文件）")));
                    if (!lock) {
                        diags.add(Diag.info("NO_LOCKFILE",
                                "未找到 " + pm + " 锁文件：安装命令已降级为 " + pm + " install（否则 --frozen-lockfile 会失败）",
                                "建议提交锁文件，保证构建可复现"));
                    }
                    stages.add(new StageSpec("frontend", "frontend_build", "container",
                            workdirOf(nodeDir), null));
                    actions.put("frontend_build", installCommand(pm, lock) + " && " + pm + " build");
                    if (hasRealTestScript(nodeRoot)) {
                        stages.add(new StageSpec("frontend-test", "frontend_test", "container",
                                workdirOf(nodeDir), null));
                        actions.put("frontend_test", pm + " test");
                    } else {
                        diags.add(Diag.info("NO_FRONTEND_TEST",
                                "package.json 没有可用的 test 脚本：已跳过前端测试 stage"));
                    }
                }
            }
        } else {
            stack = "UNKNOWN";
            diags.add(Diag.suggestion("UNKNOWN_STACK",
                    "未识别出 Java/Node 工程：已生成骨架，请按需修改 stages/actions",
                    "确认仓库根是否含 pom.xml / package.json，或把对应的工程目录提交上来"));
        }

        if (hasDockerfile && target == DeployTarget.DOCKER) {
            stages.add(new StageSpec("image", "image_package", "host", null, null));
            actions.put("image_package", "docker build -q -t ${tag} .");
        } else if (target == DeployTarget.HOST) {
            diags.add(Diag.info("HOST_TARGET",
                    "HOST 目标直接运行 Java jar；前端静态产物不会由平台托管"));
        } else if (target == DeployTarget.SERVER) {
            diags.add(Diag.info("SERVER_TARGET",
                    "SERVER 目标需要配置 agent.deploy.server.host 与 key-path（见部署文档 §4）"));
        }
        if (server.portDefaulted()) {
            diags.add(Diag.suggestion("PORT_DEFAULTED",
                    "未探测到应用端口，暂用 " + server.port() + "（健康检查也按它拼）",
                    "在 application.yml 里确认 server.port，或在平台侧 ci/pipeline.yml 里改 deploy.port"));
        }
        if (server.healthDefaulted()) {
            diags.add(Diag.suggestion("HEALTH_PATH_DEFAULTED",
                    "未探测到健康端点，暂用 " + server.healthPath() + " 做健康门禁",
                    "补 /actuator/health（暴露 health）或把 deploy.healthPath 改成实际端点"));
        }

        // 应用自己声明的必填 env（docker/.env.example 等）→ 写进 deploy.env-required，
        // 部署前由引擎做 E-509 预检；值本身仍归「设置 → 项目环境」，定义里只留名字。
        List<String> envRequired = detectRequiredEnv(repoRoot);
        if (!envRequired.isEmpty()) {
            diags.add(Diag.info("ENV_REQUIRED",
                    "按 .env.example 检测到 " + envRequired.size() + " 个必填环境变量："
                            + String.join("、", envRequired)
                            + "（已写入 deploy.env-required，部署前预检；请到「设置 → 项目环境」配置）"));
        }

        List<Diag> deduped = dedupe(diags);

        // 所有目标都走平台部署器 + 健康门禁（SERVER 亦同；未配置时 fail-closed）
        List<StageSpec> pipelineStages = new ArrayList<>(stages);
        pipelineStages.add(new StageSpec("deploy", "deploy", null, null, target));
        pipelineStages.add(new StageSpec("health", "health", null, null, target));

        String pipelineYaml = renderPipeline(pipelineStages, target, server, envRequired);
        String actionsYaml = renderActions(actions);

        List<ScaffoldFile> files = List.of(
                new ScaffoldFile(PIPELINE_FILE, pipelineYaml),
                new ScaffoldFile(ACTIONS_FILE, actionsYaml));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("stack", stack);
        meta.put("javaDir", javaDir);
        meta.put("nodeDir", nodeDir);
        meta.put("workdirs", workdirs(javaDir, nodeDir));
        meta.put("packageManager", nodeDir == null ? null : detectPackageManager(nodeOf(repoRoot, nodeDir)));
        meta.put("dockerfile", hasDockerfile);
        meta.put("deployTarget", target.wireName());
        meta.put("targetReason", choice.reason());
        meta.put("deployable", deduped.stream().noneMatch(d -> d.level() == Level.BLOCKER));
        meta.put("port", server.port());
        meta.put("portSource", server.portSource());
        meta.put("healthPath", server.healthPath());
        meta.put("healthSource", server.healthSource());
        meta.put("diagnostics", deduped.stream().map(Diag::toMap).toList());

        List<String> notes = deduped.stream().map(Diag::message).toList();
        return new ScaffoldPlan(projectId, stack, files, notes, meta);
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

    // ---------------------------------------------------------------- 目标选择

    private TargetChoice decideTarget(DeployTarget requested, boolean hasDockerfile,
                                      String javaDir, String nodeDir) {
        List<Diag> diags = new ArrayList<>();
        if (hasDockerfile) {
            return new TargetChoice(requested, "仓库提供 Dockerfile：按请求目标 " + requested.wireName(), diags);
        }
        if (requested != DeployTarget.DOCKER) {
            return new TargetChoice(requested, "按请求目标 " + requested.wireName() + "（不需要 Dockerfile）", diags);
        }
        if (javaDir != null && nodeDir == null) {
            // 纯 Java 用 jar 直接跑：与其生成一个必然 E-501 的 docker 目标，不如按能力降级
            diags.add(Diag.info("TARGET_DOWNGRADED",
                    "未找到 Dockerfile：纯 Java 项目已自动改用 HOST（直接运行 jar）",
                    "需要镜像部署时补 Dockerfile，再在项目页重新生成定义"));
            return new TargetChoice(DeployTarget.HOST,
                    "未找到 Dockerfile，纯 Java 项目自动降级为 HOST", diags);
        }
        String why = javaDir == null && nodeDir == null
                ? "未识别出可构建工程且未找到 Dockerfile"
                : "未找到 Dockerfile，但仓库包含前端静态产物";
        diags.add(Diag.blocker("DOCKERFILE_MISSING",
                why + "：DOCKER 目标无法打镜像，部署会失败（E-501）",
                "补一个 Dockerfile 后重新生成定义，或改用 SERVER 目标（需先配置服务器）"));
        return new TargetChoice(DeployTarget.DOCKER, why, diags);
    }

    /** stage 描述（生成期中间结构）。 */
    private record StageSpec(String id, String action, String runner, String workdir, DeployTarget deployTarget) {
    }

    /** 目标选择结果 + 选择理由。 */
    private record TargetChoice(DeployTarget target, String reason, List<Diag> diags) {
    }

    // ---------------------------------------------------------------- 渲染

    private String renderPipeline(List<StageSpec> stages, DeployTarget target, ServerConfig server,
                                  List<String> envRequired) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 由平台脚手架生成，可直接修改。\n");
        sb.append("# 生效顺序：环境固化脚本 > 仓库 ci/pipeline.yml > 平台侧定义（本文件）> 自动生成\n");
        sb.append("name: release\n\n");
        sb.append("deploy:\n")
                .append("  target: ").append(target.wireName()).append('\n')
                .append("  port: ").append(server.port()).append('\n')
                .append("  containerPort: ").append(server.port()).append('\n')
                .append("  healthPath: ").append(server.healthPath()).append('\n');
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
                        .append("    url: http://127.0.0.1:").append(server.port())
                        .append(server.healthPath()).append('\n')
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

    // ---------------------------------------------------------------- 前端脚本

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

    private boolean hasLockFile(Path dir, String pm) {
        return switch (pm) {
            case "pnpm" -> isFile(dir.resolve("pnpm-lock.yaml"));
            case "yarn" -> isFile(dir.resolve("yarn.lock"));
            default -> isFile(dir.resolve("package-lock.json"))
                    || isFile(dir.resolve("npm-shrinkwrap.json"));
        };
    }

    private String installCommand(String pm, boolean lock) {
        return switch (pm) {
            case "pnpm" -> lock
                    ? "pnpm install --frozen-lockfile --ignore-scripts"
                    : "pnpm install --ignore-scripts";
            case "yarn" -> lock
                    ? "yarn install --frozen-lockfile --ignore-scripts"
                    : "yarn install --ignore-scripts";
            default -> lock ? "npm ci --ignore-scripts" : "npm install --ignore-scripts";
        };
    }

    // ---------------------------------------------------------------- 工程识别

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
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .limit(20)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String relative(Path root, Path dir) {
        return root.relativize(dir).toString().replace('\\', '/');
    }

    private Path nodeOf(Path repoRoot, String nodeDir) {
        return nodeDir == null || nodeDir.isEmpty() ? repoRoot : repoRoot.resolve(nodeDir);
    }

    private String workdirOf(String dir) {
        return dir == null || dir.isEmpty() ? null : dir;
    }

    private List<String> workdirs(String javaDir, String nodeDir) {
        Set<String> out = new LinkedHashSet<>();
        if (javaDir != null && !javaDir.isEmpty()) {
            out.add(javaDir);
        }
        if (nodeDir != null && !nodeDir.isEmpty()) {
            out.add(nodeDir);
        }
        return List.copyOf(out);
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

    // ---------------------------------------------------------------- 端口与健康路径

    /**
     * 探测对外端口与健康路径。
     *
     * <p>优先级：Dockerfile HEALTHCHECK 的 URL → application 配置的 server.port →
     * Dockerfile EXPOSE → 默认 8080。健康路径：Dockerfile HEALTHCHECK → actuator health →
     * 代码里的 health 映射 → 默认 {@code /}。每个结果都带来源，报告里如实展示。
     */
    private ServerConfig detectServerConfig(Path repoRoot, String javaDir, boolean hasDockerfile) {
        Integer dockerHealthPort = null;
        String dockerHealthPath = null;
        Integer dockerExpose = null;
        if (hasDockerfile) {
            Path dockerfile = repoRoot.resolve("Dockerfile");
            String text = readText(dockerfile);
            Matcher health = Pattern
                    .compile("https?://(?:localhost|127\\.0\\.0\\.1):(\\d+)(/[^\\s\"'\\\\]*)?").matcher(text);
            if (health.find()) {
                dockerHealthPort = Integer.valueOf(health.group(1));
                String path = health.group(2);
                dockerHealthPath = path == null || path.isBlank() ? "/" : path;
            }
            Matcher expose = Pattern.compile("(?im)^\\s*EXPOSE\\s+(.+)$").matcher(text);
            while (expose.find() && dockerExpose == null) {
                for (String token : expose.group(1).trim().split("\\s+")) {
                    try {
                        dockerExpose = Integer.parseInt(token);
                        break;
                    } catch (NumberFormatException ignored) {
                        // EXPOSE 里可能有变量，忽略
                    }
                }
            }
        }

        Integer appPort = null;
        String appPortSource = null;
        String appHealth = null;
        String appHealthSource = null;
        if (javaDir != null) {
            Path javaRoot = javaDir.isEmpty() ? repoRoot : repoRoot.resolve(javaDir);
            for (Path cfg : findAppConfigs(javaRoot)) {
                Map<String, String> kv = readFlatConfig(cfg);
                if (appPort == null) {
                    Integer parsed = intOrDefault(kv.get("server.port"));
                    if (parsed != null) {
                        appPort = parsed;
                        appPortSource = relative(repoRoot, cfg) + " · server.port";
                    }
                }
                if (appHealth == null) {
                    String exposure = kv.getOrDefault("management.endpoints.web.exposure.include", "")
                            .toLowerCase(java.util.Locale.ROOT);
                    boolean healthExposed = exposure.contains("health") || exposure.contains("*");
                    if (healthExposed) {
                        String ctx = kv.getOrDefault("server.servlet.context-path", "");
                        String base = kv.getOrDefault("management.endpoints.web.base-path", "/actuator");
                        appHealth = normalizePath(ctx + "/" + base + "/health");
                        appHealthSource = relative(repoRoot, cfg) + " · actuator health";
                    }
                }
            }
            if (appHealth == null) {
                String mapped = scanHealthMapping(javaRoot);
                if (mapped != null) {
                    appHealth = mapped;
                    appHealthSource = relative(repoRoot, javaRoot) + " · 代码中的 health 映射";
                }
            }
        }

        if (dockerHealthPort != null) {
            return new ServerConfig(dockerHealthPort, "Dockerfile HEALTHCHECK",
                    dockerHealthPath, "Dockerfile HEALTHCHECK", false, false);
        }
        if (appPort != null) {
            boolean healthDefaulted = appHealth == null;
            return new ServerConfig(appPort, appPortSource,
                    healthDefaulted ? "/" : appHealth,
                    healthDefaulted ? DEFAULT_HEALTH_NOTE : appHealthSource,
                    false, healthDefaulted);
        }
        if (dockerExpose != null) {
            boolean healthDefaulted = dockerHealthPath == null;
            return new ServerConfig(dockerExpose, "Dockerfile EXPOSE",
                    healthDefaulted ? "/" : dockerHealthPath,
                    healthDefaulted ? DEFAULT_HEALTH_NOTE : "Dockerfile HEALTHCHECK",
                    false, healthDefaulted);
        }
        return new ServerConfig(8080, DEFAULT_PORT_NOTE, "/", DEFAULT_HEALTH_NOTE, true, true);
    }

    /** application 配置候选：{@code resources/} 下的 application*.yml/yaml/properties，按层级浅→深排序。 */
    private List<Path> findAppConfigs(Path javaRoot) {
        if (!Files.isDirectory(javaRoot)) {
            return List.of();
        }
        long maxDepth = 8;
        try (Stream<Path> stream = Files.walk(javaRoot, (int) maxDepth)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isAppConfigName)
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().contains("/target/") && !p.toString().contains("\\target\\"))
                    .filter(p -> !p.toString().contains("/dist/") && !p.toString().contains("\\dist\\"))
                    .sorted(Comparator.comparingInt((Path p) -> p.getNameCount())
                            .thenComparing(p -> p.getFileName().toString()))
                    .limit(40)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private boolean isAppConfigName(Path p) {
        String name = p.getFileName().toString();
        if (!name.startsWith("application")) {
            return false;
        }
        return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties");
    }

    /**
     * 极简扁平化：把 YAML 的缩进层级拼成 {@code a.b.c}，只取标量；
     * properties 直接按 {@code =} 拆。列表值（如 exposure.include）用逗号拼接。
     */
    private Map<String, String> readFlatConfig(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        String name = file.getFileName().toString();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return out;
        }
        if (name.endsWith(".properties")) {
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    out.put(trimmed.substring(0, eq).trim(), cleanValue(trimmed.substring(eq + 1)));
                }
            }
            return out;
        }
        Deque<String> keys = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        String lastListKey = null;
        for (String line : lines) {
            String withoutComment = stripComment(line);
            if (withoutComment.isBlank() || withoutComment.trim().startsWith("-")
                    || withoutComment.trim().equals("---") || withoutComment.trim().equals("...")) {
                if (withoutComment.trim().startsWith("-") && lastListKey != null) {
                    String item = cleanValue(withoutComment.trim().substring(1));
                    out.merge(lastListKey, item, (a, b) -> a + "," + b);
                }
                continue;
            }
            int indent = indentOf(withoutComment);
            while (!indents.isEmpty() && indent <= indents.peek()) {
                indents.pop();
                keys.pop();
            }
            String trimmed = withoutComment.trim();
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            String path = flatKey(keys, key);
            if (value.isEmpty()) {
                keys.push(key);
                indents.push(indent);
                lastListKey = path;
            } else {
                out.put(path, cleanValue(value));
                lastListKey = null;
            }
        }
        return out;
    }

    /** 把栈里的父级 key（push 顺序是从内到外）还原成 {@code a.b.c}。 */
    private String flatKey(Deque<String> keys, String key) {
        List<String> parts = new ArrayList<>(keys);
        java.util.Collections.reverse(parts);
        parts.add(key);
        return String.join(".", parts);
    }

    private String stripComment(String line) {
        // 只处理"整行注释"和"值前无引号"的常见情况，够用且不会误伤 URL 里的 #
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }

    private int indentOf(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i += line.charAt(i) == '\t' ? 2 : 1;
        }
        return i;
    }

    private String cleanValue(String raw) {
        String value = raw.trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 解析整数；支持 {@code ${PORT:8090}} 这种占位符（取默认值）。解析不了返回 null。 */
    private Integer intOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = cleanValue(raw);
        if (value.contains("${")) {
            int colon = value.lastIndexOf(':');
            int close = value.lastIndexOf('}');
            if (colon < 0 || close < colon) {
                return null;
            }
            value = value.substring(colon + 1, close).trim();
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 && parsed < 65536 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 代码里显式声明的健康端点（{@code @GetMapping("/health")} 之类），找不到返回 null。 */
    private String scanHealthMapping(Path javaRoot) {
        if (!Files.isDirectory(javaRoot)) {
            return null;
        }
        Pattern mapping = Pattern.compile(
                "@(?:Get|Request)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"(/[^\"]*health[^\"]*)\"");
        try (Stream<Path> stream = Files.walk(javaRoot, 12)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).limit(400).toList()) {
                String text = readText(file);
                Matcher m = mapping.matcher(text);
                if (m.find()) {
                    return normalizePath(m.group(1));
                }
            }
        } catch (IOException ignored) {
            // 扫描失败就当没有：健康路径会退回默认值并在报告里标来源
        }
        return null;
    }

    private String normalizePath(String raw) {
        String path = raw == null ? "" : raw.trim().replaceAll("/{2,}", "/");
        if (path.isEmpty() || !path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** 端口/健康路径探测结果；*Defaulted 标记"没探测到，用了兜底值"。 */
    private record ServerConfig(int port, String portSource, String healthPath, String healthSource,
                                boolean portDefaulted, boolean healthDefaulted) {
    }

    // ---------------------------------------------------------------- 诊断

    private enum Level { BLOCKER, SUGGESTION, INFO }

    /** 一条面向人的诊断：同一根因只保留一条。 */
    private record Diag(Level level, String code, String message, String fix) {

        static Diag blocker(String code, String message, String fix) {
            return new Diag(Level.BLOCKER, code, message, fix);
        }

        static Diag blocker(String code, String message) {
            return new Diag(Level.BLOCKER, code, message, null);
        }

        static Diag suggestion(String code, String message, String fix) {
            return new Diag(Level.SUGGESTION, code, message, fix);
        }

        static Diag suggestion(String code, String message) {
            return new Diag(Level.SUGGESTION, code, message, null);
        }

        static Diag info(String code, String message) {
            return new Diag(Level.INFO, code, message, null);
        }

        static Diag info(String code, String message, String fix) {
            return new Diag(Level.INFO, code, message, fix);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("level", level.name().toLowerCase(java.util.Locale.ROOT));
            out.put("code", code);
            out.put("message", message);
            if (fix != null && !fix.isBlank()) {
                out.put("fix", fix);
            }
            return out;
        }
    }

    /** 按 code 去重（同一根因只说一次），保持出现顺序。 */
    private List<Diag> dedupe(List<Diag> diags) {
        Map<String, Diag> unique = new LinkedHashMap<>();
        for (Diag diag : diags) {
            unique.putIfAbsent(diag.code(), diag);
        }
        return List.copyOf(unique.values());
    }

    // ---------------------------------------------------------------- 必填环境变量

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
