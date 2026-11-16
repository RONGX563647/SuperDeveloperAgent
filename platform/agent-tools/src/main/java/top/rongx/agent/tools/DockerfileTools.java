package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.patch.CodePatch;
import top.rongx.agent.domain.port.ChangeStore;
import top.rongx.agent.domain.port.CodePatcher;
import top.rongx.agent.domain.port.PatchStore;
import top.rongx.agent.domain.port.PipelineDefStore;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.domain.port.RepoInspector;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * Docker 发布自愈工具：当 DOCKER 目标因「仓库没有 Dockerfile / 缺 image_package」被
 * E-501 / E-708 挡住时，按仓库真实结构生成一份可用的 Dockerfile，并补齐镜像阶段与动作配方。
 *
 * <p>为什么单独做成工具而不是让模型手写：Dockerfile 依赖仓库结构（哪个模块产出可执行 jar、
 * 前端产物在哪个 dist、容器端口是多少），模型很容易写错路径或漏掉静态资源。这里用确定性的
 * 探测规则生成，并把结果作为变更单交给人工确认——写仓库仍然必须经过确认卡，Agent 不能自应用。
 *
 * <p>生成策略（只读探测，不执行构建）：
 * <ul>
 *   <li>Java：找带 {@code *Application.java} 的 Maven 模块，运行阶段用 JRE 镜像 +
 *       {@code target/*.jar}；若同时存在前端 dist，则一并拷入 {@code /app/web}
 *       并设置 {@code AGENT_WEB_DIST}；</li>
 *   <li>纯前端：nginx 镜像托管 dist；</li>
 *   <li>识别不出：返回可执行的错误说明，不生成半成品。</li>
 * </ul>
 */
@Component
public class DockerfileTools {

    private static final int MAX_SCAN_DEPTH = 4;
    private static final int MAX_DIFF_CHARS = 6000;
    private static final Set<String> SKIP_DIRS = Set.of(
            "target", "node_modules", ".git", ".agent", ".idea", ".vscode", ".gradle", "data", "build");

    private static final Pattern SERVER_PORT_YML =
            Pattern.compile("(?m)^\\s*server:\\s*\\R\\s+port:\\s*(\\d{2,5})\\b");
    private static final Pattern SERVER_PORT_PROPS =
            Pattern.compile("(?m)^\\s*server\\.port\\s*=\\s*(\\d{2,5})\\b");
    private static final Pattern JAVA_VERSION =
            Pattern.compile("<java\\.version>\\s*(\\d{1,2})");
    private static final Pattern IMAGE_ACTION =
            Pattern.compile("action:\\s*['\"]?image_package\\b");

    private final CodePatcher patcher;
    private final PatchStore patches;
    private final ChangeStore changes;
    private final PipelineDefStore defStore;
    private final RecipeStore recipes;
    private final ProjectRepoLocator locator;
    private final RepoInspector inspector;

    public DockerfileTools(CodePatcher patcher, PatchStore patches, ChangeStore changes,
                           PipelineDefStore defStore, RecipeStore recipes,
                           ProjectRepoLocator locator, RepoInspector inspector) {
        this.patcher = patcher;
        this.patches = patches;
        this.changes = changes;
        this.defStore = defStore;
        this.recipes = recipes;
        this.locator = locator;
        this.inspector = inspector;
    }

    @Tool(name = "ensure_dockerfile",
            description = """
                    为 DOCKER 目标自动补齐镜像发布链路（写操作，仅 dev/test）。
                    适用场景：deploy_run / ci_dry_run 返回 E-501「DOCKER 目标但仓库没有 Dockerfile」
                    或「缺少 image_package 阶段」时，直接调用本工具，不要让用户手写 Dockerfile。
                    它会做三件事：
                    1) 按仓库结构生成 Dockerfile（Java 模块 → JRE + target/*.jar；纯前端 → nginx + dist；
                       Java + 前端 → 同一镜像内托管页面并设置 AGENT_WEB_DIST）；
                    2) 在真正生效的流水线定义里补 image_package 阶段（runner=host，放在 deploy 前）；
                    3) 在对应的 ci/actions.yml 里补 image_package 配方（docker build -f Dockerfile -t ${tag} .）。
                    Dockerfile 通过受限补丁登记变更单：确认卡会出现在当前对话里，必须由人确认后才应用；
                    平台侧定义（仓库没有 ci/pipeline.yml 时）会直接写入并让旧固化脚本失效。
                    返回 changeId 后请等用户确认，再针对新提交重新 deploy_run。""",
            readOnly = false, concurrencySafe = false)
    public String ensure(@ToolParam(name = "base_sha",
                              description = "失败运行的提交 sha（7-40 位十六进制），用作补丁基线")
                              String baseSha,
                      @ToolParam(name = "container_port", required = false,
                              description = "容器端口；缺省按 application.yml 的 server.port，再缺省 8090")
                              Integer containerPort,
                      ProjectContext ctx) {
        if (ctx.isProduction()) {
            return "err: 生产环境不允许修改仓库或流水线定义；请先在 dev/test 验证";
        }
        if (baseSha == null || !baseSha.trim().matches("[0-9a-f]{7,40}")) {
            return "err: base_sha 需 7-40 位小写十六进制（失败运行的 gitSha）";
        }
        if (containerPort != null && (containerPort <= 0 || containerPort > 65535)) {
            return "err: container_port 必须是 1-65535";
        }
        Path repo = locator.repoDir(ctx.projectId()).orElse(null);
        if (repo == null || !Files.isDirectory(repo)) {
            return "err: 找不到项目仓库目录，无法探测技术栈：" + ctx.projectId();
        }

        String dockerfilePath;
        try {
            dockerfilePath = findDockerfile(repo);
        } catch (IOException e) {
            return "err: 读取仓库失败：" + e.getMessage();
        }
        Plan plan = null;
        List<CodePatch.FileEdit> edits = new ArrayList<>();
        if (dockerfilePath == null) {
            try {
                plan = detect(repo, containerPort);
            } catch (IOException e) {
                return "err: 读取仓库失败：" + e.getMessage();
            } catch (IllegalArgumentException e) {
                return "err: " + e.getMessage();
            }
            dockerfilePath = "Dockerfile";
            edits.add(new CodePatch.FileEdit(dockerfilePath, plan.content()));
        } else if (!inspector.fileExistsAt(ctx.projectId(), baseSha.trim(), dockerfilePath)) {
            // 工作区里有、但基线提交里没有：流水线检出的是提交，必须把它一起带进补丁，
            // 否则确认卡应用后仍然"仓库没有 Dockerfile"。
            try {
                edits.add(new CodePatch.FileEdit(dockerfilePath, read(repo.resolve(dockerfilePath))));
            } catch (IOException e) {
                return "err: 读取 " + dockerfilePath + " 失败：" + e.getMessage();
            }
        }

        boolean repoDefined = Files.isRegularFile(repo.resolve("ci").resolve("pipeline.yml"));
        List<String> notes = new ArrayList<>();
        String stageState;
        String recipeState;

        if (repoDefined) {
            stageState = "仓库 ci/pipeline.yml";
            recipeState = "仓库 ci/actions.yml";
            try {
                Path pipeline = repo.resolve("ci").resolve("pipeline.yml");
                String updated = addImageStage(read(pipeline));
                if (updated != null) {
                    edits.add(new CodePatch.FileEdit("ci/pipeline.yml", updated));
                } else {
                    stageState += "（已包含 image_package）";
                }
                Path actions = repo.resolve("ci").resolve("actions.yml");
                String actionsYaml = Files.isRegularFile(actions) ? read(actions) : "";
                String newActions = addImageRecipe(actionsYaml, dockerfilePath);
                if (newActions != null) {
                    edits.add(new CodePatch.FileEdit("ci/actions.yml", newActions));
                } else {
                    recipeState += "（已包含 image_package 配方）";
                }
            } catch (IOException | IllegalArgumentException e) {
                return "err: 修复仓库 ci/ 定义失败：" + e.getMessage();
            }
        } else {
            String pipelineYaml = defStore.read(ctx.projectId()).orElse("");
            if (pipelineYaml.isBlank()) {
                return "err: 平台侧也没有 ci/pipeline.yml；请先生成流水线定义（项目接入/脚手架），再重试";
            }
            try {
                String updated = addImageStage(pipelineYaml);
                Path actions = defStore.rootOf(ctx.projectId()).resolve("ci").resolve("actions.yml");
                String actionsYaml = Files.isRegularFile(actions) ? read(actions) : "";
                String newActions = addImageRecipe(actionsYaml, dockerfilePath);
                if (updated == null) {
                    stageState = "平台侧已包含 image_package";
                } else {
                    stageState = "平台侧已补齐 image_package";
                }
                if (newActions == null) {
                    recipeState = "平台侧已包含 image_package 配方";
                } else {
                    recipeState = "平台侧已补齐 image_package 配方";
                }
                if (updated != null || newActions != null) {
                    String merged = updated != null ? updated : pipelineYaml;
                    if (!defStore.write(ctx.projectId(), merged, newActions)) {
                        return "err: 流水线定义写入失败，请检查工作区目录权限："
                                + defStore.rootOf(ctx.projectId());
                    }
                    notes.add("平台侧定义已更新");
                }
            } catch (IOException e) {
                return "err: 读取平台侧流水线定义失败：" + e.getMessage();
            } catch (IllegalArgumentException e) {
                return "err: 平台侧定义无法补齐镜像阶段：" + e.getMessage();
            }
        }

        if (!edits.isEmpty() && edits.stream().noneMatch(e -> e.path().equals("Dockerfile"))) {
            notes.add("仓库已有 " + dockerfilePath + "，只补流水线定义");
        }

        String patchInfo = "none";
        String changeId = null;
        String diff = null;
        if (!edits.isEmpty()) {
            CodePatcher.Staged staged;
            try {
                staged = patcher.stage(ctx.projectId(), baseSha.trim(), edits);
            } catch (IllegalArgumentException e) {
                return "err: " + e.getMessage();
            }
            String patchId = "pt-" + UUID.randomUUID().toString().substring(0, 8);
            String branch = "agent/fix-" + patchId;
            String summary = "自动补齐 Docker 镜像发布链路（Dockerfile + image_package）";
            CodePatch patch = new CodePatch(patchId, ctx.projectId(), ctx.env(),
                    baseSha.trim(), branch, edits, staged.diff(), staged.diffHash(),
                    staged.added(), staged.removed(), 0L);
            patches.save(patch);
            ChangePlan change = changes.registerWithHash(ctx.projectId(), ctx.env(),
                    "patch:" + patchId, summary, staged.diffHash(), false);
            changeId = change.changeId();
            diff = staged.diff();
            if (diff.length() > MAX_DIFF_CHARS) {
                diff = diff.substring(0, MAX_DIFF_CHARS) + "\n…（diff 已截断，完整内容见确认卡片）";
            }
            patchInfo = patchId + "（" + String.join(", ", staged.files()) + "）";
        }

        // 旧固化脚本可能是 host/无镜像阶段的产物，继续生效会让本次修复"看起来没生效"；
        // 让它失效（历史版本仍可 activate 回来），下次运行按最新定义解析。
        for (String env : List.of("dev", "test", "prod")) {
            recipes.invalidate(ctx.projectId(), env);
        }
        notes.add("已让旧固化脚本失效，下一次运行按最新定义解析");

        StringBuilder out = new StringBuilder();
        out.append("status: ").append(diff == null ? "already_ready" : "patch_required").append('\n');
        out.append("stack: ").append(plan == null ? "existing" : plan.stack()).append('\n');
        out.append("dockerfile: ").append(dockerfilePath).append('\n');
        out.append("imageStage: ").append(stageState).append('\n');
        out.append("recipe: ").append(recipeState).append('\n');
        out.append("patchId: ").append(patchInfo).append('\n');
        if (changeId != null) {
            out.append("changeId: ").append(changeId).append('\n');
            out.append("apply: 确认卡已推到当前对话，需用户确认后才写仓库\n");
            out.append("next: 用户确认并应用后，用新提交重新 ci_dry_run → deploy_run；"
                    + "不要假设已确认\n");
        } else {
            out.append("next: 直接重新 ci_dry_run → deploy_run\n");
        }
        for (String note : notes) {
            out.append("note: ").append(note).append('\n');
        }
        if (diff != null) {
            out.append("---\n").append(diff).append('\n');
        }
        out.append("evidence: project:").append(ctx.projectId());
        return out.toString();
    }

    // ---------------------------------------------------------------- 探测

    record Plan(String stack, String content) {
    }

    /** 按仓库结构生成 Dockerfile；识别不出时抛出可读错误。 */
    static Plan detect(Path repo, Integer requestedPort) throws IOException {
        Path javaModule = findSpringBootModule(repo);
        Path dist = findFrontendDist(repo);
        if (javaModule != null) {
            int java = javaVersion(repo, javaModule);
            int port = requestedPort != null ? requestedPort : serverPort(javaModule).orElse(8090);
            String modulePath = relative(repo, javaModule);
            StringBuilder df = new StringBuilder();
            df.append("# 由平台按仓库结构自动生成（ensure_dockerfile）\n");
            df.append("# Java 模块：").append(modulePath.isEmpty() ? "." : modulePath).append('\n');
            df.append("FROM eclipse-temurin:").append(java).append("-jre\n");
            df.append("WORKDIR /app\n");
            df.append("ENV TZ=Asia/Shanghai\n");
            df.append("COPY ").append(modulePath.isEmpty() ? "" : modulePath + "/")
                    .append("target/*.jar /app/app.jar\n");
            if (dist != null) {
                String distPath = relative(repo, dist);
                df.append("# 前端产物：").append(distPath).append('\n');
                df.append("COPY ").append(distPath).append("/ /app/web/\n");
                df.append("ENV AGENT_WEB_DIST=/app/web\n");
            }
            df.append("ENV SERVER_PORT=").append(port).append('\n');
            df.append("EXPOSE ").append(port).append('\n');
            df.append("ENTRYPOINT [\"java\",\"-jar\",\"/app/app.jar\"]\n");
            return new Plan("java", df.toString());
        }
        if (dist != null) {
            String distPath = relative(repo, dist);
            String content = """
                    # 由平台按仓库结构自动生成（ensure_dockerfile）
                    # 静态产物：%s
                    FROM nginx:1.27-alpine
                    ENV TZ=Asia/Shanghai
                    COPY %s/ /usr/share/nginx/html/
                    EXPOSE 80
                    """.formatted(distPath, distPath);
            return new Plan("frontend", content);
        }
        throw new IllegalArgumentException(
                "未能识别技术栈：没有找到带 *Application.java 的 Maven 模块，也没有前端 dist。"
                        + "请先在仓库根提供 Dockerfile，或确认构建阶段会产出 target/*.jar / dist");
    }

    /** 已有的 Dockerfile（相对仓库根）；没有返回 null。 */
    static String findDockerfile(Path repo) throws IOException {
        return list(repo).stream()
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.equals("Dockerfile") || name.endsWith(".Dockerfile");
                })
                .sorted(Comparator.comparingInt((Path p) -> p.getNameCount()).thenComparing(Path::toString))
                .map(p -> relative(repo, p))
                .findFirst()
                .orElse(null);
    }

    static Path findSpringBootModule(Path repo) throws IOException {
        List<Path> poms = list(repo).stream()
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals("pom.xml"))
                .sorted(Comparator.comparingInt((Path p) -> p.getNameCount()).thenComparing(Path::toString))
                .toList();
        for (Path pom : poms) {
            Path module = pom.getParent();
            if (hasApplicationClass(module)) {
                return module;
            }
        }
        return null;
    }

    static Path findFrontendDist(Path repo) throws IOException {
        return list(repo).stream()
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().equals("dist"))
                .filter(p -> Files.isRegularFile(p.resolve("index.html")))
                .sorted(Comparator.comparingInt((Path p) -> p.getNameCount()).thenComparing(Path::toString))
                .findFirst()
                .orElse(null);
    }

    /**
     * 限深遍历，并在进入 node_modules / target / .git 等目录时直接剪枝。
     *
     * <p>不能用 {@link Files#walk} 再过滤：它仍会遍历这些目录，前端依赖树可达数万文件，
     * 每次 dry-run 都扫一遍会明显拖慢。
     */
    private static List<Path> list(Path repo) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(repo, Set.of(), MAX_SCAN_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(repo) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                out.add(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    private static boolean hasApplicationClass(Path module) throws IOException {
        Path src = module.resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(src)) {
            return false;
        }
        try (Stream<Path> stream = Files.walk(src, 12)) {
            return stream.anyMatch(p -> p.getFileName().toString().endsWith("Application.java"));
        }
    }

    static int javaVersion(Path repo, Path module) throws IOException {
        Path dir = module;
        for (int i = 0; i < 4 && dir != null && dir.startsWith(repo); i++, dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                Matcher m = JAVA_VERSION.matcher(read(pom));
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
        }
        return 21;
    }

    static Optional<Integer> serverPort(Path module) throws IOException {
        Path resources = module.resolve("src").resolve("main").resolve("resources");
        if (!Files.isDirectory(resources)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(resources)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith("application")) {
                    continue;
                }
                String text = read(file);
                Matcher m = name.endsWith(".properties")
                        ? SERVER_PORT_PROPS.matcher(text) : SERVER_PORT_YML.matcher(text);
                if (m.find()) {
                    return Optional.of(Integer.parseInt(m.group(1)));
                }
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- YAML 补丁

    /**
     * 在 stages 里 deploy 之前插入 image_package 阶段；已存在返回 null。
     *
     * <p>用文本插入而不是 YAML 反序列化再 dump：平台定义可能带注释/固定缩进，
     * 整体重写会把它们冲掉。只接受「- id: deploy」或 flow 风格两种既有写法。
     */
    static String addImageStage(String yaml) {
        if (yaml == null || yaml.isBlank() || IMAGE_ACTION.matcher(yaml).find()) {
            return null;
        }
        String[] lines = yaml.split("\\R", -1);
        int stages = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("stages:")) {
                stages = i;
                break;
            }
        }
        if (stages < 0) {
            throw new IllegalArgumentException("流水线定义缺少顶层 stages:");
        }
        int end = lines.length;
        for (int i = stages + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            if (!Character.isWhitespace(line.charAt(0)) && !line.trim().startsWith("-")) {
                end = i;
                break;
            }
        }
        int deploy = -1;
        for (int i = stages + 1; i < end; i++) {
            String text = lines[i].trim();
            if (text.startsWith("-") && (text.matches("^-\\s*\\{?.*\\bid:\\s*deploy\\b.*")
                    || text.matches("^-\\s*type:\\s*deploy\\b.*"))) {
                deploy = i;
                break;
            }
        }
        if (deploy < 0) {
            throw new IllegalArgumentException("stages 里找不到 deploy 阶段，无法确定镜像阶段的插入位置");
        }
        int dash = lines[deploy].indexOf('-');
        String indent = dash > 0 ? lines[deploy].substring(0, dash) : "";
        List<String> out = new ArrayList<>(Arrays.asList(lines));
        out.add(deploy, indent + "- {id: image, type: action, action: image_package, runner: host}");
        return String.join("\n", out);
    }

    /** 在 actions 里补 image_package 配方；已存在返回 null。 */
    static String addImageRecipe(String yaml, String dockerfilePath) {
        if (yaml != null && yaml.contains("image_package:")) {
            return null;
        }
        String file = dockerfilePath == null || dockerfilePath.isBlank() ? "Dockerfile" : dockerfilePath;
        String block = "  image_package:\n"
                + "    # 平台自动补齐：镜像在宿主机构建，${tag} 由引擎注入\n"
                + "    run:\n"
                + "      - [\"docker\", \"build\", \"-f\", \"" + file + "\", \"-t\", \"${tag}\", \".\"]\n"
                + "    timeout: 20m\n";
        if (yaml == null || yaml.isBlank()) {
            return "actions:\n" + block;
        }
        String base = yaml.endsWith("\n") ? yaml : yaml + "\n";
        if (!base.contains("actions:")) {
            base = base + "\nactions:\n";
        }
        return base + block;
    }

    // ---------------------------------------------------------------- 小工具

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String relative(Path repo, Path file) {
        return repo.relativize(file).toString().replace('\\', '/');
    }
}
