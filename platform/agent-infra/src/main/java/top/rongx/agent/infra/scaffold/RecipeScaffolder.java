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

        boolean rootJava = isFile(repoRoot.resolve("pom.xml"))
                || isFile(repoRoot.resolve("build.gradle")) || isFile(repoRoot.resolve("build.gradle.kts"));
        boolean rootNode = isFile(repoRoot.resolve("package.json"));
        boolean subJava = isFile(repoRoot.resolve("backend/pom.xml"));
        boolean subNode = isFile(repoRoot.resolve("frontend/package.json"));
        boolean dockerfile = isFile(repoRoot.resolve("Dockerfile"));

        String stack;
        List<StageSpec> stages = new ArrayList<>();
        Map<String, String> actions = new LinkedHashMap<>();

        if (subJava || subNode) {
            stack = subJava && subNode ? "JAVA_NODE" : subJava ? "JAVA" : "NODE";
            if (subJava) {
                boolean gradle = isFile(repoRoot.resolve("backend/build.gradle"))
                        || isFile(repoRoot.resolve("backend/build.gradle.kts"));
                stages.add(new StageSpec("build", "backend_build", "container", "backend", null));
                stages.add(new StageSpec("test", "backend_test", "container", "backend", null));
                actions.put("backend_build", gradle
                        ? "gradle -q build -x test" : "mvn -B -q -DskipTests package");
                actions.put("backend_test", gradle ? "gradle -q test" : "mvn -B -q test");
            }
            if (subNode) {
                String pm = detectPackageManager(repoRoot.resolve("frontend"));
                notes.add("前端包管理器识别为 " + pm);
                stages.add(new StageSpec("frontend", "frontend_build", "container", "frontend", null));
                actions.put("frontend_build", pm.equals("npm")
                        ? "npm ci --ignore-scripts && npm run build"
                        : pm + " install --frozen-lockfile --ignore-scripts && " + pm + " build");
            }
        } else if (rootJava) {
            stack = "JAVA";
            boolean gradle = isFile(repoRoot.resolve("build.gradle")) || isFile(repoRoot.resolve("build.gradle.kts"));
            stages.add(new StageSpec("build", "backend_build", "container", null, null));
            stages.add(new StageSpec("test", "backend_test", "container", null, null));
            actions.put("backend_build", gradle ? "gradle -q build -x test" : "mvn -B -q -DskipTests package");
            actions.put("backend_test", gradle ? "gradle -q test" : "mvn -B -q test");
        } else if (rootNode) {
            stack = "NODE";
            String pm = detectPackageManager(repoRoot);
            notes.add("包管理器识别为 " + pm);
            stages.add(new StageSpec("frontend", "frontend_build", "container", null, null));
            actions.put("frontend_build", pm.equals("npm")
                    ? "npm ci --ignore-scripts && npm run build"
                    : pm + " install --frozen-lockfile --ignore-scripts && " + pm + " build");
        } else {
            stack = "UNKNOWN";
            notes.add("未识别出 Java/Node 工程：已生成骨架，请按需修改 stages/actions");
        }

        if (dockerfile && target == DeployTarget.DOCKER) {
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

        // 所有目标都走平台部署器 + 健康门禁（SERVER 亦同；未配置时 fail-closed）
        List<StageSpec> pipelineStages = new ArrayList<>(stages);
        pipelineStages.add(new StageSpec("deploy", "deploy", null, null, target));
        pipelineStages.add(new StageSpec("health", "health", null, null, target));

        String pipelineYaml = renderPipeline(pipelineStages, target);
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

    private String renderPipeline(List<StageSpec> stages, DeployTarget target) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 由平台脚手架生成（可自由修改，改动随代码 review）\n");
        sb.append("name: release\n\n");
        sb.append("deploy:\n")
                .append("  target: ").append(target.wireName()).append('\n')
                .append("  port: 8080\n")
                .append("  containerPort: 8080\n")
                .append("  healthPath: /healthz\n\n");
        sb.append("stages:\n");
        for (StageSpec stage : stages) {
            sb.append("  - id: ").append(stage.id()).append('\n');
            if ("deploy".equals(stage.id())) {
                sb.append("    type: deploy\n");
            } else if ("health".equals(stage.id())) {
                sb.append("    type: health\n")
                        .append("    url: http://127.0.0.1:8080/healthz\n")
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
            sb.append("    timeout: 15m\n");
        }
        return sb.toString();
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

    private String detectPackageManager(Path dir) {
        if (isFile(dir.resolve("pnpm-lock.yaml"))) {
            return "pnpm";
        }
        if (isFile(dir.resolve("yarn.lock"))) {
            return "yarn";
        }
        return "npm";
    }

    private boolean isFile(Path path) {
        return Files.isRegularFile(path);
    }
}
