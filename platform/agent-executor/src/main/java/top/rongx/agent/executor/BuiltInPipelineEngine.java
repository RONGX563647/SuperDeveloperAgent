package top.rongx.agent.executor;

import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvResource;
import top.rongx.agent.domain.env.PortLease;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.PipelineEngine.LogSlice;
import top.rongx.agent.domain.port.PipelineEngine.StagePreview;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.executor.RunHistoryStore.RunSnapshot;

/**
 * 内置流水线引擎：把「检出 → 构建 → 测试 → 镜像 → 部署 → 健康检查」串成一次可追溯的运行。
 *
 * <p><b>编排职责边界（P3 澄清）</b>：平台<b>不</b>拥有多 stage 编排逻辑本身——
 * 编排由仓库 {@code ci/pipeline.yml}（数据，人写、versioned）声明，本引擎只是其确定性执行器。
 * 这与"模型写命令"严格隔离：模型只能 {@code ci_run} 触发一条已定义流水线，不能拼 stage。
 *
 * <p>与 Jenkins 的对应关系：
 * <ul>
 *   <li>{@code ci/pipeline.yml} ↔ Jenkinsfile（但只是数据，不能写任意代码）；</li>
 *   <li>{@code trigger(gitSha)} ↔ 指定提交构建；</li>
 *   <li>{@code RunState} ↔ 构建历史（stage 结果、耗时、产物）；</li>
 *   <li>失败即停 + on-failure ↔ {@code post { failure { ... } } }；</li>
 *   <li>{@code health} stage ↔ 发布门禁。</li>
 * </ul>
 *
 * <p>执行面是确定性代码，不调用任何模型（规约 A3）。每个 stage 底层都分解为
 * {@code ActionSpec}（动作层），与 {@code run_action} 共用同一套 ActionRunner 与门禁。
 */
public class BuiltInPipelineEngine implements PipelineEngine, AutoCloseable {

    private static final Duration STAGE_TIMEOUT = Duration.ofMinutes(5);
    /** 命令里的 ${var} 占位符（预览展示模板，运行时替换）。 */
    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");
    /** 敏感开关形式的参数，如 --password xxx / --token=xxx。 */
    private static final java.util.regex.Pattern SECRET_FLAG = java.util.regex.Pattern.compile(
            "(?i)^--?(password|passwd|pwd|token|secret|api-?key|access-?key)(=.*)?$");
    /** KEY=VALUE 形式且 KEY 像密钥（DB_PASSWORD=xxx / TOKEN_SECRET=xxx）。 */
    private static final java.util.regex.Pattern SECRET_ASSIGN = java.util.regex.Pattern.compile(
            "(?i)^([A-Za-z0-9_]*(SECRET|TOKEN|PASSWORD|PASSWD|API_?KEY|ACCESS_?KEY)[A-Za-z0-9_]*)=(.+)$");
    /** URL 内嵌凭据：scheme://user:pass@host → 掩码 pass。 */
    private static final java.util.regex.Pattern URL_CREDENTIAL = java.util.regex.Pattern.compile(
            "(://[^/@:]+:)[^@]+(@)");

    private final ActionResolver recipes;
    private final ActionRunner sandboxed;
    private final ActionRunner host;
    private final Path workspaceRoot;

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    /** 每个项目最近一次成功发布的镜像 tag，供回滚使用。 */
    private final Map<String, String> lastGood = new ConcurrentHashMap<>();
    /** 有界队列：全局并发上限 4；同一项目串行由 TriggerScheduler 保证。 */
    private final ExecutorService workers = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory());
    private final TriggerScheduler scheduler = new TriggerScheduler();
    /** 构建历史持久化（重启可恢复）。 */
    private final RunHistoryStore history;
    /** 发布镜像 tag 前缀（可配置；避免测试字样进入正式发布链路）。 */
    private final String imagePrefix;
    /** 仓库定位器（v2 直接模式；可空 = 旧布局 workspace/<id>/git）。 */
    private final top.rongx.agent.domain.port.ProjectRepoLocator repoLocator;
    /** 平台部署器（方案 C：部署语义归平台，不再交给仓库脚本）。 */
    private final top.rongx.agent.domain.port.Deployer deployers;
    /** 定义解析器（仓库覆盖 → 环境脚本 → 平台定义 → 规则生成 → agent 兜底）；空 = 旧装配。 */
    private final PipelineDefinitionResolver resolver;
    /** 开发环境本机托管（后端 jar + 前端 dist 静态服务器）；空 = 不支持本机模式。 */
    private final LocalDevHostDeployer localDevHost;
    /** 环境级可复现脚本（空 = 关闭"固化/重放/晋升校验"）。 */
    private final top.rongx.agent.domain.port.RecipeStore recipeStore;
    /** 项目环境变量（旧模型；envStore 非空时不使用）。 */
    private final top.rongx.agent.domain.settings.ProjectEnvVarRepository envVars;
    /** 环境模板/资源/覆盖/端口租约（新模型）。 */
    private final top.rongx.agent.domain.env.EnvironmentStore envStore;
    /** 中间件运行时（部署前自动启动项目引用的 docker 中间件）；空 = 不启停。 */
    private final top.rongx.agent.domain.env.EnvRuntime envRuntime;
    /** 环境容器自愈（缺失的中间件容器按需创建）；空 = 不创建。 */
    private final top.rongx.agent.domain.env.EnvProvisioner envProvisioner;
    /** 失败后自动诊断（部署 Agent）；空 = 不介入。 */
    private final top.rongx.agent.domain.run.RunRecoveryAdvisor recoveryAdvisor;
    /** 多服务部署（test 前后端分离）直接使用 docker 实现。 */
    private final DockerDeployer dockerDeployer = new DockerDeployer();

    /** 应用关闭时停掉执行线程：不再接新任务，正在跑的构建被中断（避免悬挂进程）。 */
    @Override
    public void close() {
        workers.shutdownNow();
    }

    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot) {
        this(recipes, sandboxed, host, workspaceRoot, RunHistoryStore.disabled(), "ci");
    }

    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history) {
        this(recipes, sandboxed, host, workspaceRoot, history, "ci");
    }

    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix) {
        this(recipes, sandboxed, host, workspaceRoot, history, imagePrefix, null);
    }

    /** v2：带仓库定位器的构造（DIRECT 模式：仓库在用户目录）。 */
    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix,
                                 top.rongx.agent.domain.port.ProjectRepoLocator repoLocator) {
        this(recipes, sandboxed, host, workspaceRoot, history, imagePrefix, repoLocator, Deployers.builtin());
    }

    /** 完整构造：额外注入平台部署器（DOCKER/HOST/SERVER）。 */
    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix,
                                 top.rongx.agent.domain.port.ProjectRepoLocator repoLocator,
                                 top.rongx.agent.domain.port.Deployer deployers) {
        this(recipes, sandboxed, host, workspaceRoot, history, imagePrefix, repoLocator, deployers, null);
    }

    /**
     * 最完整构造：再注入定义解析器（平台侧定义 + 自动生成 + agent 兜底）。
     *
     * <p>{@code resolver} 为空时退化为"只认检出目录里的 ci/pipeline.yml"，
     * 仅用于单测；生产装配必须提供。
     */
    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix,
                                 top.rongx.agent.domain.port.ProjectRepoLocator repoLocator,
                                 top.rongx.agent.domain.port.Deployer deployers,
                                 PipelineDefinitionResolver resolver) {
        this(recipes, sandboxed, host, workspaceRoot, history, imagePrefix, repoLocator, deployers,
                resolver, null);
    }

    /**
     * 最完整构造：再注入环境级可复现脚本存储（recipe）。
     *
     * <p>给了 recipeStore 就有了三件事：运行成功自动固化、重放优先用固化脚本、
     * 服务器环境前置"测试机成功案例"校验。
     */
    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix,
                                 top.rongx.agent.domain.port.ProjectRepoLocator repoLocator,
                                 top.rongx.agent.domain.port.Deployer deployers,
                                 PipelineDefinitionResolver resolver,
                                 top.rongx.agent.domain.port.RecipeStore recipeStore) {
        this(recipes, sandboxed, host, workspaceRoot, history, imagePrefix, repoLocator,
                deployers, resolver, recipeStore, null);
    }

    /**
     * 最完整构造：再注入项目环境变量仓储（部署时注入 KEY=VALUE）。
     *
     * <p>环境变量按 (projectId, env) 读取，来自「设置 → 项目环境」；为空时部署行为与之前一致。
     */
    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix,
                                 top.rongx.agent.domain.port.ProjectRepoLocator repoLocator,
                                 top.rongx.agent.domain.port.Deployer deployers,
                                 PipelineDefinitionResolver resolver,
                                 top.rongx.agent.domain.port.RecipeStore recipeStore,
                                 top.rongx.agent.domain.settings.ProjectEnvVarRepository envVars) {
        this(recipes, sandboxed, host, workspaceRoot, history, imagePrefix, repoLocator,
                deployers, resolver, recipeStore, envVars, null);
    }

    /**
     * 最完整构造：再注入环境模板仓储（三套环境 + 中间件 + 项目覆盖 + 端口租约）。
     *
     * <p>非空时部署走新链路：有效变量来自环境模板 + 项目覆盖、注入网络、端口租约；
     * 为空时退化为旧的 per-project 变量，方便单测与渐进迁移。
     */
    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix,
                                 top.rongx.agent.domain.port.ProjectRepoLocator repoLocator,
                                 top.rongx.agent.domain.port.Deployer deployers,
                                 PipelineDefinitionResolver resolver,
                                 top.rongx.agent.domain.port.RecipeStore recipeStore,
                                 top.rongx.agent.domain.settings.ProjectEnvVarRepository envVars,
                                 top.rongx.agent.domain.env.EnvironmentStore envStore) {
        this(recipes, sandboxed, host, workspaceRoot, history, imagePrefix, repoLocator,
                deployers, resolver, recipeStore, envVars, envStore, null);
    }

    /**
     * 最完整构造：再注入中间件运行时（部署前自动启动项目引用的 docker 中间件）。
     *
     * <p>为空时退化为"只读检查"，不会启停任何容器，方便单测。
     */
    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix,
                                 top.rongx.agent.domain.port.ProjectRepoLocator repoLocator,
                                 top.rongx.agent.domain.port.Deployer deployers,
                                 PipelineDefinitionResolver resolver,
                                 top.rongx.agent.domain.port.RecipeStore recipeStore,
                                 top.rongx.agent.domain.settings.ProjectEnvVarRepository envVars,
                                 top.rongx.agent.domain.env.EnvironmentStore envStore,
                                 top.rongx.agent.domain.env.EnvRuntime envRuntime) {
        this(recipes, sandboxed, host, workspaceRoot, history, imagePrefix, repoLocator, deployers,
                resolver, recipeStore, envVars, envStore, envRuntime, null);
    }

    /**
     * 最完整构造：再注入环境容器自愈（部署前按需创建项目隔离的中间件容器）。
     *
     * <p>为空时只启动已登记的资源，不会创建新容器，方便单测。
     */
    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix,
                                 top.rongx.agent.domain.port.ProjectRepoLocator repoLocator,
                                 top.rongx.agent.domain.port.Deployer deployers,
                                 PipelineDefinitionResolver resolver,
                                 top.rongx.agent.domain.port.RecipeStore recipeStore,
                                 top.rongx.agent.domain.settings.ProjectEnvVarRepository envVars,
                                 top.rongx.agent.domain.env.EnvironmentStore envStore,
                                 top.rongx.agent.domain.env.EnvRuntime envRuntime,
                                 top.rongx.agent.domain.env.EnvProvisioner envProvisioner) {
        this(recipes, sandboxed, host, workspaceRoot, history, imagePrefix, repoLocator,
                deployers, resolver, recipeStore, envVars, envStore, envRuntime, envProvisioner, null);
    }

    /**
     * 最完整构造：再注入失败自动诊断（部署 Agent）。
     *
     * <p>为空时失败即终态，不调用任何模型，方便单测与无模型部署。
     */
    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix,
                                 top.rongx.agent.domain.port.ProjectRepoLocator repoLocator,
                                 top.rongx.agent.domain.port.Deployer deployers,
                                 PipelineDefinitionResolver resolver,
                                 top.rongx.agent.domain.port.RecipeStore recipeStore,
                                 top.rongx.agent.domain.settings.ProjectEnvVarRepository envVars,
                                 top.rongx.agent.domain.env.EnvironmentStore envStore,
                                 top.rongx.agent.domain.env.EnvRuntime envRuntime,
                                 top.rongx.agent.domain.env.EnvProvisioner envProvisioner,
                                 top.rongx.agent.domain.run.RunRecoveryAdvisor recoveryAdvisor) {
        this(recipes, sandboxed, host, workspaceRoot, history, imagePrefix, repoLocator, deployers,
                resolver, recipeStore, envVars, envStore, envRuntime, envProvisioner,
                recoveryAdvisor, null);
    }

    /**
     * 最完整构造：再注入开发环境本机托管（后端 jar + 前端 dist）。
     *
     * <p>为空时 dev 只能走 Docker；单测与不支持本机模式的装配保持旧行为。
     */
    public BuiltInPipelineEngine(ActionResolver recipes, ActionRunner sandboxed,
                                 ActionRunner host, Path workspaceRoot, RunHistoryStore history,
                                 String imagePrefix,
                                 top.rongx.agent.domain.port.ProjectRepoLocator repoLocator,
                                 top.rongx.agent.domain.port.Deployer deployers,
                                 PipelineDefinitionResolver resolver,
                                 top.rongx.agent.domain.port.RecipeStore recipeStore,
                                 top.rongx.agent.domain.settings.ProjectEnvVarRepository envVars,
                                 top.rongx.agent.domain.env.EnvironmentStore envStore,
                                 top.rongx.agent.domain.env.EnvRuntime envRuntime,
                                 top.rongx.agent.domain.env.EnvProvisioner envProvisioner,
                                 top.rongx.agent.domain.run.RunRecoveryAdvisor recoveryAdvisor,
                                 LocalDevHostDeployer localDevHost) {
        this.recipes = recipes;
        this.sandboxed = sandboxed;
        this.host = host;
        this.workspaceRoot = workspaceRoot;
        this.history = history == null ? RunHistoryStore.disabled() : history;
        this.imagePrefix = imagePrefix == null || imagePrefix.isBlank() ? "ci" : imagePrefix;
        this.repoLocator = repoLocator;
        this.deployers = deployers == null ? Deployers.builtin() : deployers;
        this.resolver = resolver;
        this.recipeStore = recipeStore;
        this.envVars = envVars;
        this.envStore = envStore;
        this.envRuntime = envRuntime;
        this.envProvisioner = envProvisioner;
        this.recoveryAdvisor = recoveryAdvisor;
        this.localDevHost = localDevHost;
        restoreHistory();
    }

    // ---------------------------------------------------------------- PipelineEngine

    @Override
    public String id() {
        return "builtin-pipeline";
    }

    /** 流水线定义路径：仓库里可选提交这份覆盖；没有就用平台侧的自动生成定义。 */
    private static final String PIPELINE_FILE = "ci/pipeline.yml";

    /** 极旧布局（平台也定位不到仓库）时的兜底名。 */
    private static final List<String> BUILTIN_PIPELINES = List.of("release", "verify");

    /** 连平台侧定义都生成不出来（脚手架失败/仓库不可读）。 */
    private static final String E_NO_PIPELINE = "E-220";
    /** 定义存在但解析不出来（人工写坏了平台定义或仓库覆盖文件）。 */
    private static final String E_BAD_PIPELINE = "E-221";

    @Override
    public List<String> pipelines(String projectId) {
        // 名字优先取"这次真正会用的那份定义"：仓库提交覆盖 > 平台定义 > 规则生成
        Path repoDir = resolveRepoForRead(projectId);
        try {
            PipelineDefinition def;
            if (resolver != null) {
                def = resolver.resolveForRead(projectId, repoDir).definition();
            } else if (repoDir != null && Files.isRegularFile(repoDir.resolve(PIPELINE_FILE))) {
                def = PipelineDefinition.load(repoDir);
            } else {
                return BUILTIN_PIPELINES;
            }
            String name = def.name();
            return List.of(name == null || name.isBlank() ? "release" : name, "verify");
        } catch (RuntimeException e) {
            // 生成/解析失败不阻塞下拉：仍给默认名，触发期会给出可读错误
            return repoDir == null ? BUILTIN_PIPELINES : List.of("release", "verify");
        }
    }

    /**
     * 阶段预览：与执行路径共用同一份定义解析，保证"看到的"就是"会跑的"。
     *
     * <p>verify 与执行期一样裁掉部署/健康检查/回滚 stage（以及没有 Dockerfile 时的镜像 stage）。
     */
    @Override
    public List<StagePreview> preview(String projectId, String pipelineId, String env) {
        return preview(projectId, pipelineId, env, null);
    }

    @Override
    public List<StagePreview> preview(String projectId, String pipelineId, String env, String gitSha) {
        PipelineDefinition definition;
        Path recipeRoot;
        Path repoDir = resolveRepoForRead(projectId);
        String normalizedEnv = env == null || env.isBlank() ? null : normalizeEnv(env);
        Path shaRoot = null;
        try {
            if (resolver != null) {
                PipelineDefinitionResolver.Resolved resolved =
                        resolver.resolveForRead(projectId, repoDir, normalizedEnv);
                // dev 的语义是"用本地工作区"（sha 仅作记录）；其余环境执行会检出该提交，
                // 因此"仓库定义"要按该提交解析，否则预览与实跑可能是两份 pipeline.yml。
                if (gitSha != null && !gitSha.isBlank() && repoDir != null
                        && !"dev".equals(normalizedEnv)
                        && resolved.source() != PipelineDefinitionResolver.Source.RECIPE) {
                    shaRoot = materializeCommitDefinition(repoDir, gitSha);
                    if (shaRoot != null) {
                        resolved = resolver.resolveForRead(projectId, shaRoot, normalizedEnv);
                    }
                }
                definition = resolved.definition();
                recipeRoot = resolved.recipeRoot() != null ? resolved.recipeRoot() : repoDir;
            } else if (repoDir != null && Files.isRegularFile(repoDir.resolve(PIPELINE_FILE))) {
                Path definitionRoot = repoDir;
                if (gitSha != null && !gitSha.isBlank() && !"dev".equals(normalizedEnv)) {
                    shaRoot = materializeCommitDefinition(repoDir, gitSha);
                    if (shaRoot != null) {
                        definitionRoot = shaRoot;
                    }
                }
                definition = PipelineDefinition.load(definitionRoot);
                recipeRoot = definitionRoot;
            } else {
                return List.of();
            }
        } catch (RuntimeException e) {
            // 定义还没生成/刚被改坏：预览为空，由触发期给出可读错误
            return List.of();
        }
        try {
            return renderPreviews(definition, pipelineId, recipeRoot);
        } catch (RuntimeException e) {
            // 配方解析失败同样不阻塞触发：预览为空，触发期会给可读错误
            return List.of();
        } finally {
            deleteTree(shaRoot);
        }
    }

    /**
     * 只读预览：把指定提交里的定义文件取到临时目录（不检出 worktree、不改工作区），
     * 交给同一套解析逻辑。取不到（该提交没有 {@code ci/pipeline.yml}）返回 null，回退工作区。
     */
    private Path materializeCommitDefinition(Path repoDir, String gitSha) {
        Path temp = null;
        try {
            temp = Files.createTempDirectory("agent-preview-");
            boolean any = false;
            for (String rel : List.of("ci/pipeline.yml", "ci/actions.yml", "Taskfile.yml", "Makefile")) {
                String content = gitShow(repoDir, gitSha, rel);
                if (content == null) {
                    continue;
                }
                Path target = temp.resolve(rel);
                Files.createDirectories(target.getParent());
                Files.writeString(target, content);
                any = true;
            }
            if (any && Files.isRegularFile(temp.resolve(PIPELINE_FILE))) {
                return temp;
            }
        } catch (IOException | RuntimeException e) {
            // 取不到就按工作区预览：预览失败不能阻断触发
        }
        deleteTree(temp);
        return null;
    }

    /** git show 单个文件；该提交里不存在时返回 null。 */
    private static String gitShow(Path repoDir, String gitSha, String relPath) {
        try {
            Process process = new ProcessBuilder("git", "-C", repoDir.toString(),
                    "show", gitSha + ":" + relPath)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 删除临时目录（预览用；失败不影响结果）。 */
    private static void deleteTree(Path root) {
        if (root == null) {
            return;
        }
        try (var files = Files.walk(root)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响预览
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private List<StagePreview> renderPreviews(PipelineDefinition definition, String pipelineId,
                                              Path recipeRoot) {
        List<PipelineDefinition.StageDef> stages = definition.stages();
        java.util.Set<String> kept = new java.util.HashSet<>();
        if ("verify".equals(pipelineId)) {
            // 与执行期共用同一份裁剪逻辑（PipelineDefinition.forVerify），预览即实跑
            definition.forVerify(false).stages().forEach(s -> kept.add(s.id()));
        } else {
            stages.forEach(s -> kept.add(s.id()));
        }
        return stages.stream()
                .map(s -> {
                    StageCommand cmd = describeStage(recipeRoot, s);
                    return new StagePreview(s.id(), s.type(), s.action(), s.runner(), s.workdir(),
                            kept.contains(s.id()) ? null : verifySkipReason(s),
                            cmd.shell(), cmd.command(), cmd.script(), cmd.sha(), cmd.builtin());
                })
                .toList();
    }

    /** 预览里给前端展示的"这一阶段实际会跑什么"。 */
    private record StageCommand(String shell, String command, String script, String sha,
                                boolean builtin) {
    }

    /**
     * 把阶段翻译成命令级预览：action 阶段解析配方（与执行期同一个 {@code ActionResolver}），
     * 按目标 shell 渲染成可复制/可下载的脚本；部署/健康检查/回滚是平台内置步骤。
     *
     * <p>渲染只发生在展示层：真正执行仍按 argv（不经 shell），因此脚本内容与安全边界一致。
     * 脚本头写明运行目录、变量、超时与产物，下载后按说明即可执行。
     */
    private StageCommand describeStage(Path recipeRoot, PipelineDefinition.StageDef stage) {
        if (!"action".equals(stage.type())) {
            return new StageCommand("", builtinLabel(stage), "", "", true);
        }
        ActionId action = ActionId.fromWire(stage.action()).orElse(null);
        if (action == null || recipeRoot == null) {
            return new StageCommand("", stage.action(), "", "", true);
        }
        Optional<ActionSpec> resolved = recipes.resolve(recipeRoot, action);
        if (resolved.isEmpty()) {
            return new StageCommand("", "（没有配方）" + stage.action(), "", "", true);
        }
        ActionSpec spec = resolved.get();
        String shell = shellFor(stage);
        // 变量名先收集：脚本头要写清"需要哪些变量"
        java.util.Set<String> placeholders = new java.util.LinkedHashSet<>();
        for (List<String> argv : spec.steps()) {
            for (String token : argv) {
                collectPlaceholders(token, placeholders);
            }
        }
        List<String> lines = new ArrayList<>();
        if ("pwsh".equals(shell)) {
            lines.addAll(headerLines(stage, spec, placeholders));
            lines.add("$ErrorActionPreference = 'Stop'");
            if (hasWorkdir(stage)) {
                lines.add("Set-Location '" + stage.workdir().replace("'", "''") + "'");
            }
        } else {
            lines.add("#!/usr/bin/env sh");
            lines.addAll(headerLines(stage, spec, placeholders));
            lines.add("set -eu");
            if (hasWorkdir(stage)) {
                lines.add("cd '" + stage.workdir().replace("'", "'\\''") + "'");
            }
        }
        List<String> commands = new ArrayList<>();
        for (List<String> argv : spec.steps()) {
            String command = renderCommand(argv, shell);
            commands.add(command);
            lines.add(command);
        }
        String text = String.join("\n", lines) + "\n";
        return new StageCommand(shell, String.join(" && ", commands), text, sha256Short(text), false);
    }

    /** 脚本头注释：在哪跑、要哪些变量、超时多久、产物是什么。 */
    private static List<String> headerLines(PipelineDefinition.StageDef stage, ActionSpec spec,
                                            java.util.Set<String> placeholders) {
        List<String> lines = new ArrayList<>();
        lines.add("# stage: " + stage.id() + " · " + stage.action());
        lines.add("# 运行目录: " + (hasWorkdir(stage)
                ? stage.workdir() + "（脚本内含 cd，请在仓库根执行）"
                : "仓库根"));
        if (!placeholders.isEmpty()) {
            lines.add("# 变量: " + String.join(", ", placeholders) + "（预览为模板，运行时由平台替换）");
        }
        String produce = spec.produce().isEmpty() ? ""
                : "；产物: " + String.join(", ", spec.produce()) + "（未生成视为失败）";
        lines.add("# 超时: " + spec.timeout().toMinutes() + "m" + produce);
        lines.add("# 平台执行时注入 CI=true");
        return lines;
    }

    /** 收集 argv 里的 ${var} 变量名（预览展示模板，运行时才替换）。 */
    private static void collectPlaceholders(String token, java.util.Set<String> out) {
        if (token == null) {
            return;
        }
        java.util.regex.Matcher m = PLACEHOLDER.matcher(token);
        while (m.find()) {
            out.add(m.group(1));
        }
    }

    /** 目标 shell：容器内一律 sh；宿主机按操作系统（Windows → pwsh）。 */
    private static String shellFor(PipelineDefinition.StageDef stage) {
        boolean inContainer = stage.runner() == null || stage.runner().isBlank()
                || !"host".equals(stage.runner());
        return inContainer ? "sh" : (isWindows() ? "pwsh" : "sh");
    }

    private static boolean hasWorkdir(PipelineDefinition.StageDef stage) {
        return stage.workdir() != null && !stage.workdir().isBlank() && !".".equals(stage.workdir());
    }

    /** 内置阶段的人话说明（没有可展示的脚本）。 */
    private static String builtinLabel(PipelineDefinition.StageDef stage) {
        if ("deploy".equals(stage.type())) return "（平台内置）部署 " + safe(stage.action());
        if ("health".equals(stage.type())) return "（平台内置）健康检查";
        if ("rollback".equals(stage.type())) return "（平台内置）回滚上一版";
        if ("git-checkout".equals(stage.type())) return "（平台内置）检出提交";
        return "（平台内置）" + safe(stage.type());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** argv → 一行可读命令：先掩码敏感值，再按目标 shell 的引号规则转义。 */
    private static String renderCommand(List<String> argv, String shell) {
        return maskSecrets(argv).stream()
                .map(token -> shellToken(token, shell))
                .collect(Collectors.joining(" "));
    }

    /**
     * 单个参数按目标 shell 的规则渲染：安全字符原样，其余用单引号包裹。
     *
     * <p>单引号是两种 shell 里唯一"什么都不展开"的形式（避免 $、反引号、反斜杠被再次解释）；
     * 内部单引号的转义规则不同：sh 用 {@code '\''}，pwsh 用 {@code ''}。
     * {@code ${var}} 占位符刻意不加引号：它就是留给运行时替换的变量引用。
     */
    private static String shellToken(String token, String shell) {
        if (token == null) return "";
        if (token.isEmpty()) return "''";
        if (token.matches("[A-Za-z0-9_./:=@%+${}-]+")) return token;
        if ("pwsh".equals(shell)) {
            return "'" + token.replace("'", "''") + "'";
        }
        return "'" + token.replace("'", "'\\''") + "'";
    }

    /**
     * 掩码敏感参数：预览与运行日志都走这里，避免配方里硬编码的密钥被展示或留档。
     * 只改展示文本，不影响真正执行的 argv。
     */
    private static List<String> maskSecrets(List<String> argv) {
        List<String> out = new ArrayList<>(argv.size());
        boolean maskNext = false;
        for (String token : argv) {
            if (maskNext) {
                out.add("******");
                maskNext = false;
                continue;
            }
            if (token != null && SECRET_FLAG.matcher(token).matches()) {
                int eq = token.indexOf('=');
                if (eq >= 0) {
                    // --token=xxx：值在同一参数内，只掩码等号后面
                    out.add(token.substring(0, eq + 1) + "******");
                } else {
                    // --token xxx：掩码下一个参数
                    out.add(token);
                    maskNext = true;
                }
                continue;
            }
            String masked = token == null ? ""
                    : SECRET_ASSIGN.matcher(token).replaceAll("$1=******");
            out.add(URL_CREDENTIAL.matcher(masked).replaceAll("$1******$2"));
        }
        return out;
    }

    private static String sha256Short(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 12);
        } catch (Exception e) {
            return "";
        }
    }

    /** verify 预览里被裁掉的那一步为什么跳过（执行期同样不跑）。 */
    private static String verifySkipReason(PipelineDefinition.StageDef stage) {
        if ("deploy".equals(stage.type()) || "health".equals(stage.type())
                || "rollback".equals(stage.type()) || "stack_deploy".equals(stage.action())) {
            return "只检查构建：不部署、不做健康检查";
        }
        if ("image_package".equals(stage.action())) {
            return "只检查构建：不产镜像";
        }
        return "只检查构建：跳过";
    }

    /** 默认分支候选：bare 的 HEAD 指向的分支，再退回 main / master。 */
    private List<String> defaultBranches(Path repo) {
        List<String> branches = new ArrayList<>();
        ActionResult head = host.run(repo, spec("pipeline-check", List.of(
                List.of("git", "symbolic-ref", "--short", "HEAD"))), () -> false);
        if (head.result().status() == StageStatus.SUCCEEDED) {
            String name = head.logTail().trim();
            if (!name.isEmpty()) {
                branches.add(name);
            }
        }
        for (String fallback : List.of("main", "master")) {
            if (!branches.contains(fallback)) {
                branches.add(fallback);
            }
        }
        return branches;
    }

    /** 读仓库（白名单/配方自检用）：locator 优先（DIRECT=用户目录），否则旧布局。 */
    private Path resolveRepoForRead(String projectId) {
        if (repoLocator != null) {
            return repoLocator.repoDir(projectId).orElse(null);
        }
        Path projectDir = workspaceRoot.resolve(projectId).toAbsolutePath().normalize();
        Path working = projectDir.resolve("repo");
        if (Files.isDirectory(working)) {
            return working;
        }
        // 旧布局没有工作副本：登记后平台侧只有 bare 对象库（doExecute 也从这里检出），
        // 能定位到它就说明仓库可用；定义读不到时回落到平台侧定义。
        Path bare = projectDir.resolve("git");
        return Files.isDirectory(bare.resolve("objects")) ? bare : null;
    }

    /**
     * 解析本次运行要用的定义。
     *
     * <p>顺序：提交里的 {@code ci/pipeline.yml}（可选覆盖）→ 平台侧定义 → 平台自动生成
     * （规则优先，识别不出技术栈时 agent 兜底）。所以"仓库没提交 ci/"只是走平台定义，
     * 不再是一个错误。
     *
     * @return 解析结果；失败时返回 {@code null}（已登记对应的失败 stage）
     */
    private PipelineDefinitionResolver.Resolved resolveDefinition(Path runDir, RunState state) {
        if (resolver == null) {
            // 兼容旧装配（单测直接 new 引擎）：只认检出目录里的定义
            if (!Files.isRegularFile(runDir.resolve(PIPELINE_FILE))) {
                String message = "本次提交与平台都没有可用的 " + PIPELINE_FILE;
                state.log.append('[').append(E_NO_PIPELINE).append("] ").append(message).append('\n');
                state.stages.add(StageResult.failed("pipeline-def", 1, E_NO_PIPELINE, message, 0));
                state.status = StageStatus.FAILED;
                return null;
            }
            try {
                return new PipelineDefinitionResolver.Resolved(runDir,
                        PipelineDefinition.load(runDir),
                        PipelineDefinitionResolver.Source.REPO, "定义来自本次提交");
            } catch (IllegalArgumentException e) {
                return badDefinition(state, e);
            }
        }
        try {
            Path repoRoot = repoLocator == null ? null : repoLocator.repoDir(state.projectId).orElse(null);
            return resolver.resolveForRun(state.projectId, runDir, repoRoot, state.env);
        } catch (IllegalArgumentException e) {
            return badDefinition(state, e);
        } catch (RuntimeException e) {
            String message = "无法准备流水线定义（自动生成失败）：" + e.getMessage()
                    + "。可在「项目」页点「生成流水线脚本」重试，或直接编辑平台侧定义。";
            state.log.append('[').append(E_NO_PIPELINE).append("] ").append(message).append('\n');
            state.stages.add(StageResult.failed("pipeline-def", 1, E_NO_PIPELINE, message, 0));
            state.status = StageStatus.FAILED;
            return null;
        }
    }

    /**
     * 把本次成功的定义固化成该环境的可复现脚本。
     *
     * <p>只做一件事：把"刚刚证明能跑通的那份 YAML"连同证据运行 id 存下来。
     * 已经有完全相同的脚本时不重复固化（避免每次成功都堆版本）。
     */
    private void freezeRecipe(RunState state, Path runDir) {
        if (recipeStore == null) {
            return;
        }
        try {
            // 定义来源是 recipe 时无需再固化；否则把当前生效的定义存成新版本
            Path source = state.recipeRoot != null ? state.recipeRoot : runDir;
            Path pipelineFile = source.resolve(PIPELINE_FILE);
            if (!Files.isRegularFile(pipelineFile)) {
                return;
            }
            String yaml = Files.readString(pipelineFile);
            Path actionsFile = source.resolve("ci/actions.yml");
            String actions = Files.isRegularFile(actionsFile) ? Files.readString(actionsFile) : null;

            var current = recipeStore.current(state.projectId, state.env);
            if (current.isPresent() && current.get().pipelineYaml().equals(yaml)
                    && java.util.Objects.equals(current.get().actionsYaml(), actions)) {
                return; // 脚本没变：沿用现有版本
            }
            var saved = recipeStore.save(new top.rongx.agent.domain.deploy.DeployRecipe(
                    state.projectId, state.env, 0, yaml, actions, state.runId, state.gitSha,
                    System.currentTimeMillis(), "agent"));
            state.log.append("[recipe] 已固化环境脚本 ").append(state.env)
                    .append(" v").append(saved.version())
                    .append("（证据 ").append(saved.evidenceRunId()).append("）\n");
        } catch (Exception e) {
            // 固化失败不能把已成功的运行判成失败：只记录，供人补
            state.log.append("[recipe] 固化失败（运行仍算成功）：").append(e.getMessage()).append('\n');
        }
    }

    /**
     * 本次定义里是否有"真正部署"的 stage（deploy / stack_deploy）。
     *
     * <p>用于区分「检查构建」与「环境跑通」：只有后者能固化脚本、成为晋升证据。
     */
    private boolean deployedIn(RunState state) {
        if (state.definition == null) {
            return false;
        }
        return state.definition.stages().stream()
                .anyMatch(s -> "deploy".equals(s.type()) || "stack_deploy".equals(s.action()));
    }

    private PipelineDefinitionResolver.Resolved badDefinition(RunState state, IllegalArgumentException e) {
        String message = "ci/pipeline.yml 有语法/结构错误，无法执行：" + e.getMessage()
                + "（stage 只能引用白名单动作，见《流水线执行器契约》）。";
        state.log.append('[').append(E_BAD_PIPELINE).append("] ").append(message).append('\n');
        state.stages.add(StageResult.failed("pipeline-def", 1, E_BAD_PIPELINE, message, 0));
        state.status = StageStatus.FAILED;
        return null;
    }

    @Override
    public String trigger(String projectId, String pipelineId, Map<String, String> params) {
        List<String> available = pipelines(projectId);
        if (!available.contains(pipelineId)) {
            throw new IllegalArgumentException("流水线不在白名单：" + pipelineId
                    + "（可用：" + String.join(", ", available) + "）");
        }
        String sha = params.get("gitSha");
        if (sha == null || !sha.matches("[0-9a-f]{7,40}")) {
            throw new IllegalArgumentException("gitSha 非法（需 7-40 位小写十六进制）");
        }
        String env = normalizeEnv(params.get("env"));
        String deployTarget = params.get("deployTarget");
        // 服务器操作的前置：必须已有"测试机成功案例"（测试环境跑通并固化的脚本 + 证据运行成功）
        String problem = promotionProblem(projectId, env, sha);
        if (problem != null) {
            throw new IllegalArgumentException("E-601 " + problem);
        }
        // 不再校验"该提交里有没有 ci/pipeline.yml"：定义可以放在平台侧（默认），
        // 仓库里那份只是可选覆盖。真正缺定义时由执行期的 pipeline-def stage 说明。
        // 前置：仓库必须可定位，否则建出来的运行只会在历史里留下一条失败记录
        if (resolveRepoForRead(projectId) == null) {
            throw new IllegalArgumentException("E-101 项目仓库不可用：请先在「项目接入」页完成 clone / 本地登记，"
                    + "或在项目详情里执行「同步到平台」后重试");
        }
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        RunState state = new RunState(runId, projectId, env, pipelineId, sha);
        // 构建序号（项目 + 环境内从 1 开始）：既是历史列表的顺序，也是镜像版本 vN
        state.seq = history.nextSeq(projectId, env);
        if (deployTarget != null && !deployTarget.isBlank()) {
            state.deployTargetOverride = deployTarget.trim().toLowerCase();
        }
        // 开发环境固定本机运行：显式传 docker 也不生效（含 UI 误选 / 历史重跑 / Agent 触发）
        if ("dev".equals(env) && "docker".equals(state.deployTargetOverride)
                && localDevHost != null && !"verify".equals(pipelineId)) {
            state.log.append("[dev] 开发环境固定本机运行（后端 jar + 前端 dist），"
                    + "已忽略 docker 目标\n");
        }
        state.actor = params.get("actor");
        state.verifyImage = "true".equalsIgnoreCase(params.get("verifyImage"));
        state.bindingId = params.get("bindingId");
        state.confirmedBy = params.get("confirmedBy");
        if (state.bindingId != null && !state.bindingId.isBlank()) {
            state.log.append("[env] Agent 触发：binding=").append(state.bindingId)
                    .append("，人工确认人=").append(state.confirmedBy == null || state.confirmedBy.isBlank()
                            ? "（空）" : state.confirmedBy)
                    .append('\n');
        }
        runs.put(runId, state);
        history.save(state.snapshot());
        scheduler.submit(projectId, () -> workers.submit(() -> execute(state)));
        return runId;
    }

    /** 环境名规范化：只认 dev/test/prod，缺省按 test（绝不让"没传环境"落到生产）。 */
    private String normalizeEnv(String env) {
        if (env == null || env.isBlank()) {
            return "test";
        }
        String normalized = env.trim().toLowerCase();
        return List.of("dev", "test", "prod").contains(normalized) ? normalized : "test";
    }

    /**
     * 服务器环境（prod）的前置校验：**必须有测试机成功案例**（规则见
     * {@link top.rongx.agent.domain.deploy.EnvironmentPromotion}，与 HTTP 层共用同一份实现）。
     *
     * @return 不满足时的原因；满足（或非服务器环境）返回 null
     */
    private String promotionProblem(String projectId, String env, String sha) {
        return top.rongx.agent.domain.deploy.EnvironmentPromotion.problem(
                recipeStore, this::status, projectId, env, sha);
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() <= 7 ? String.valueOf(sha) : sha.substring(0, 7);
    }

    @Override
    public StageStatus status(String runId) {
        RunState s = runs.get(runId);
        return s == null ? StageStatus.PENDING : s.status;
    }

    /** 该 runId 是否真实存在（用于把"不存在"与"排队中"区分开）。 */
    public boolean hasRun(String runId) {
        return runId != null && runs.containsKey(runId);
    }

    @Override
    public List<StageResult> stages(String runId) {
        RunState s = runs.get(runId);
        return s == null ? List.of() : List.copyOf(s.stages);
    }

    @Override
    public void cancel(String runId) {
        RunState s = runs.get(runId);
        if (s != null && !s.status.isTerminal()) {
            s.cancelled = true;
            history.save(s.snapshot());
        }
    }

    @Override
    public List<ArtifactRef> artifacts(String runId) {
        RunState s = runs.get(runId);
        return s == null ? List.of() : List.copyOf(s.artifacts);
    }

    /**
     * 文件产物的本地路径（下载用）。只认归档目录内的文件，避免任意路径读取。
     */
    @Override
    public java.util.Optional<Path> artifactFile(String runId, int index) {
        List<ArtifactRef> list = artifacts(runId);
        if (index < 0 || index >= list.size()) {
            return java.util.Optional.empty();
        }
        ArtifactRef a = list.get(index);
        if (!"file".equals(a.kind())) {
            return java.util.Optional.empty();
        }
        try {
            Path base = workspaceRoot.resolve("artifacts").resolve(runId).toAbsolutePath().normalize();
            Path repo = repoOfRun(runId);
            Path file = Path.of(a.ref());
            if (!file.isAbsolute()) {
                // dev 本机部署的产物登记的是仓库相对路径（jar/dist），没有归档副本
                if (repo == null) {
                    return java.util.Optional.empty();
                }
                file = repo.resolve(a.ref());
            }
            file = file.toAbsolutePath().normalize();
            boolean allowed = file.startsWith(base)
                    || (repo != null && file.startsWith(repo.toAbsolutePath().normalize()));
            if (!allowed || !Files.isRegularFile(file)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(file);
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /** 运行所属项目的仓库目录（用于解析 dev 本机部署的相对产物路径）。 */
    private Path repoOfRun(String runId) {
        RunState s = runs.get(runId);
        String projectId = s != null
                ? s.projectId
                : history.list().stream()
                        .filter(h -> h.runId().equals(runId))
                        .map(RunHistoryStore.RunSnapshot::projectId)
                        .findFirst().orElse(null);
        return projectId == null ? null : resolveRepoForRead(projectId);
    }

    /** 供验收/排障：某次运行的全部日志尾部（经脱敏，避免凭证入链）。 */
    @Override
    public String logTail(String runId) {
        RunState s = runs.get(runId);
        if (s != null) {
            synchronized (s.log) {
                return ProcessExecutor.redact(s.log.toString());
            }
        }
        return history.list().stream()
                .filter(h -> h.runId().equals(runId))
                .findFirst().map(h -> ProcessExecutor.redact(h.log())).orElse("");
    }

    /**
     * 增量日志：在未脱敏缓冲上直接按 offset 切片，再对新增片段脱敏。
     *
     * <p>对单段做脱敏可保证 offset 语义稳定（每次全量脱敏后再切会因替换长度变化而错位）。
     */
    @Override
    public LogSlice logFrom(String runId, int offset) {
        RunState s = runs.get(runId);
        String raw;
        if (s != null) {
            synchronized (s.log) {
                raw = s.log.toString();
            }
        } else {
            raw = history.list().stream()
                    .filter(h -> h.runId().equals(runId))
                    .findFirst().map(RunSnapshot::log).orElse("");
        }
        int from = Math.min(Math.max(0, offset), raw.length());
        String added = ProcessExecutor.redact(raw.substring(from));
        // 片段脱敏后长度可能变化，end 用「原始已读位置」表达，前端只用 offset 续传
        return new LogSlice(from, raw.length(), added);
    }

    /** 构建历史列表（含重启前落盘的运行），转为 domain 视图。 */
    @Override
    public List<RunSummary> history() {
        return history.list().stream()
                .map(h -> new RunSummary(h.runId(), h.projectId(), h.env(), h.pipelineId(), h.gitSha(),
                        h.status(), h.createdAt(), h.seq(), h.stages().size(),
                        h.stages().stream().mapToLong(StageResult::durationMs).sum()))
                .toList();
    }

    @Override
    public boolean deleteHistory(String runId) {
        RunState state = runs.get(runId);
        if (state != null && !state.status.isTerminal()) {
            throw new IllegalArgumentException("运行进行中，不能删除");
        }
        // 失败运行可能保留了构建现场，删除历史时一并清掉，避免 worktree 越积越多
        if (state != null && state.keptRunDir != null && state.keptBare != null) {
            cleanupWorktree(state.keptBare, state.keptRunDir, state);
            state.keptRunDir = null;
            state.keptBare = null;
        }
        boolean deleted = history.delete(runId);
        runs.remove(runId);
        return deleted;
    }

    /** 排队数量（可观测性）。 */
    public int pendingCount() {
        return scheduler.pendingCount();
    }

    /** 运行元信息：触发者 / 环境绑定 / 定义来源（内存中有本次运行才可读）。 */
    @Override
    public RunMeta runMeta(String runId) {
        RunState s = runs.get(runId);
        if (s == null) {
            return null;
        }
        return new RunMeta(s.actor, s.bindingId, s.confirmedBy,
                s.definitionSource == null ? null : s.definitionSource.name(), s.definitionNote);
    }

    private void restoreHistory() {
        for (RunHistoryStore.RunSnapshot snap : history.list()) {
            if (runs.containsKey(snap.runId())) {
                continue;
            }
            RunState restored = new RunState(snap.runId(), snap.projectId(), snap.env(),
                    snap.pipelineId(), snap.gitSha());
            restored.status = snap.status();
            restored.seq = snap.seq();
            restored.stages.addAll(snap.stages());
            restored.artifacts.addAll(snap.artifacts());
            restored.log.append(snap.log());
            // 定义来源随历史落盘：重启后 ci_history 仍能说明"跑的是哪份定义"
            if (snap.definitionSource() != null) {
                try {
                    restored.definitionSource =
                            PipelineDefinitionResolver.Source.valueOf(snap.definitionSource());
                } catch (IllegalArgumentException ignored) {
                    // 未知来源（旧文件或枚举演进）：保持 null
                }
            }
            restored.definitionNote = snap.definitionNote();
            // 平台重启时还在 PENDING/RUNNING 的运行永远不会再推进：
            // 不修正就会在历史里留下一条"进行中"，让页头一直显示 1 个运行。
            if (!restored.status.isTerminal()) {
                restored.status = StageStatus.CANCELLED;
                restored.log.append("[recover] 平台重启前运行未完成，已标记为取消\n");
                history.save(restored.snapshot());
            }
            runs.put(snap.runId(), restored);
            if (restored.status == StageStatus.SUCCEEDED && "release".equals(restored.pipelineId)) {
                // 回滚锚点：取该次运行登记的镜像 tag
                snap.artifacts().stream()
                        .filter(a -> a.kind().equals("image"))
                        .findFirst()
                        .ifPresent(a -> lastGood.put(snap.projectId(), a.ref()));
            }
        }
    }

    // ---------------------------------------------------------------- 执行

    private void execute(RunState state) {
        try {
            state.status = StageStatus.RUNNING;
            doExecute(state);
            recoverIfNeeded(state);
        } catch (Throwable e) {
            // 定位仓库 / 同步远端等前置步骤在 doExecute 的 try 之外，异常原先会被
            // workers.submit 的 Future 吞掉：运行永远停在 RUNNING、0 阶段，成为"跑不完"的幽灵任务。
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            state.log.append("[engine] 运行前置失败：").append(why).append('\n');
            state.stages.add(StageResult.failed("engine", 1, "E-2xx", why, 0));
            state.status = StageStatus.FAILED;
        } finally {
            // 每次运行结束（含异常）都落盘，保证重启后可查询历史
            history.save(state.snapshot());
            scheduler.complete(state.projectId);
        }
    }

    /**
     * 失败自动诊断：仅 UI 触发的非生产运行进入，由部署 Agent 读日志、自愈环境并给出修复结论。
     *
     * <p>诊断期间把状态放回 RUNNING：前端按"终态"停止轮询日志，若先置 FAILED 再让 Agent
     * 工作，用户就看不到 {@code [agent]} 步骤。Agent 自己触发的运行（actor=agent）不进入，
     * 避免"触发 → 失败 → 再触发"的递归。
     */
    private void recoverIfNeeded(RunState state) {
        if (recoveryAdvisor == null || !recoveryAdvisor.available()
                || state.status != StageStatus.FAILED) {
            return;
        }
        if ("prod".equalsIgnoreCase(state.env)
                || state.actor == null || !state.actor.startsWith("ui:")) {
            return;
        }
        state.status = StageStatus.RUNNING;
        state.log.append("[agent] 运行失败，自动调用发布 Agent 诊断与修复…\n");
        try {
            String log = state.log.toString();
            if (log.length() > 20000) {
                log = log.substring(log.length() - 20000);
            }
            List<String> lines = recoveryAdvisor.advise(new top.rongx.agent.domain.run.RunRecoveryAdvisor.Context(
                    state.runId, state.projectId, state.env, state.pipelineId, state.gitSha,
                    state.actor, log));
            if (lines == null || lines.isEmpty()) {
                state.log.append("[agent] 本次未产生诊断步骤\n");
            } else {
                for (String line : lines) {
                    state.log.append("[agent] ").append(line).append('\n');
                }
            }
        } catch (Throwable e) {
            state.log.append("[agent] 自动诊断异常：")
                    .append(e.getMessage() == null ? e.toString() : e.getMessage()).append('\n');
        } finally {
            state.status = StageStatus.FAILED;
        }
    }

    private void doExecute(RunState state) {
        // 开发环境「直接部署」：前后端跑在本机，不建 worktree、不打镜像。
        // 这是开发环境的语义（部署当前工作区），也让"停掉后改用 IDE 启动"成为可能。
        if (isLocalDev(state)) {
            executeLocalDev(state);
            return;
        }
        // v2 直接模式：仓库位置与 worktree 父目录由 locator 决定
        // （DIRECT = 用户目录/.agent/worktrees；MANAGED/旧布局 = 平台 workspaces/<id>）
        Path bare;
        Path wtParent;
        if (repoLocator != null) {
            bare = repoLocator.repoDir(state.projectId).orElseThrow(
                    () -> new IllegalStateException("项目仓库不存在（projectId=" + state.projectId
                            + "），请先通过「接入项目」完成登记"));
            wtParent = repoLocator.worktreeRoot(state.projectId).orElseThrow();
            if (repoLocator.modeOf(state.projectId) == top.rongx.agent.domain.port.ProjectRepoLocator.StorageMode.DIRECT) {
                // DIRECT：构建前同步（REMOTE fetch 远端；LOCAL 无远端则内部 no-op）
                try {
                    repoLocator.sync(state.projectId);
                } catch (Exception e) {
                    state.log.append("[sync] 同步失败（继续用本地现状）：").append(e.getMessage()).append('\n');
                }
            }
        } else {
            Path projectDir = workspaceRoot.resolve(state.projectId).toAbsolutePath().normalize();
            bare = projectDir.resolve("git");
            wtParent = projectDir.resolve("worktrees");
        }
        Path runDir = wtParent.resolve("wt-build-" + state.runId);
        PipelineDefinition definition = null;
        boolean worktreeCreated = false;

        try {
            if (!Files.isDirectory(bare.resolve(".git"))
                    && !Files.isDirectory(bare.resolve("objects"))) {
                throw new IllegalStateException("项目仓库不存在：" + bare
                        + "（请先通过「接入项目」完成登记）");
            }
            // stage 1：从 bare 检出指定提交到独立 worktree
            checkout(bare, runDir, state);
            worktreeCreated = true;
            if (state.cancelled) {
                state.status = StageStatus.CANCELLED;
                return;
            }
            PipelineDefinitionResolver.Resolved resolved = resolveDefinition(runDir, state);
            if (resolved == null) {
                return; // 定义准备失败：已登记失败 stage（E-220 / E-221），无需再跑任何动作
            }
            definition = resolved.definition();
            state.recipeRoot = resolved.recipeRoot();
            state.definitionSource = resolved.source();
            state.definitionNote = resolved.note();
            state.log.append("[definition] ").append(resolved.source()).append(" · ")
                    .append(resolved.note()).append('\n');
            state.log.append("[env] ").append(state.env)
                    .append(" · 提交 ").append(shortSha(state.gitSha)).append('\n');
            if ("verify".equals(state.pipelineId)) {
                // verify = 只做构建与测试（相当于 Jenkins 的 PR 检查），不部署、不打镜像。
                // 裁剪逻辑与预览共用 PipelineDefinition.forVerify；镜像默认跳过（最贵且对"能不能编过"无价值），
                // 需要验证 Dockerfile 时用触发参数 verifyImage=true 显式保留。
                definition = definition.forVerify(state.verifyImage);
            }

            String tag = imagePrefix + "-" + state.env + "-v" + state.seq
                    + "-" + state.gitSha.substring(0, 7);
            state.log.append("[image] 镜像版本 ").append(versionOf(state))
                    .append(" · 本地镜像 ").append(tag).append('\n');
            Map<String, String> vars = Map.of(
                    "gitSha", state.gitSha,
                    "gitSha7", state.gitSha.substring(0, 7),
                    "tag", tag);
            state.definition = definition;

            // E-508：部署配置必须与默认分支一致（防"改 YAML 即发布"绕过 review）
            if (!deployGuard(bare, runDir, definition, state)) {
                state.stages.add(StageResult.failed("deploy-guard", 1, "E-508",
                        "部署配置与默认分支不一致：请先把 ci/pipeline.yml 的 deploy 段合并到默认分支",
                        elapsed(0)));
                runOnFailure(state, definition, runDir, vars);
                state.status = StageStatus.FAILED;
                return;
            }

            // 阶段循环与「从失败阶段继续」共用同一份实现，保证两条路径行为一致
            runStagesFrom(state, definition, runDir, vars, 0);
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            state.log.append("引擎异常: ").append(why).append('\n');
            state.status = StageStatus.FAILED;
            state.stages.add(StageResult.failed("engine", 1, "E-2xx", why, 0));
            if (definition != null) {
                runOnFailure(state, definition, runDir, Map.of());
            }
        } finally {
            // 产物已经写进 history.artifacts（镜像 tag / 访问地址），worktree 只是构建现场：
            // 成功、取消立即清理，否则 wt-build-* 会随每次构建无限堆积。
            // 失败则保留现场，让工作台可以「从失败阶段继续」，不必整条重跑。
            if (worktreeCreated) {
                history.save(state.snapshot());
                if (state.status == StageStatus.FAILED && !state.cancelled) {
                    state.keptRunDir = runDir;
                    state.keptBare = bare;
                    state.log.append("[engine] 已保留构建现场，可从失败阶段继续\n");
                } else {
                    cleanupWorktree(bare, runDir, state);
                }
                history.save(state.snapshot());
            }
        }
    }

    /**
     * 按定义顺序执行阶段（触发与阶段重试共用）。
     *
     * @param from 从第几个阶段开始（重试时跳过已成功的阶段）
     */
    private void runStagesFrom(RunState state, PipelineDefinition definition, Path runDir,
                               Map<String, String> vars, int from) {
        List<PipelineDefinition.StageDef> all = definition.stages();
        for (int i = from; i < all.size(); i++) {
            if (state.cancelled) {
                state.status = StageStatus.CANCELLED;
                return;
            }
            PipelineDefinition.StageDef stage = all.get(i);
            // 记下该阶段开始前的产物数量：重试时把本阶段及之后产生的产物一起回收，避免重复
            state.artifactMark.put(stage.id(), state.artifacts.size());
            boolean ok = runStage(state, stage, runDir, vars);
            if (!ok) {
                // 先跑完 on-failure，再置终态：否则查询方会在补救动作结束前看到"已完成"
                runOnFailure(state, definition, runDir, vars);
                state.status = StageStatus.FAILED;
                return;
            }
        }

        // 只有"真的部署过"的运行才更新回滚锚点、固化成环境脚本。
        // verify（只构建）成功不代表这个环境被跑通过——过去它也会冻结脚本，
        // 导致环境状态带显示"已跑通"、甚至让生产晋升拿到假证据。
        boolean deployed = deployedIn(state);
        if (deployed) {
            lastGood.put(state.projectId, vars.get("tag"));
            freezeRecipe(state, runDir);
        } else {
            state.log.append("[recipe] 本次是检查构建（未部署），不固化环境脚本、不更新回滚锚点\n");
        }
        state.status = StageStatus.SUCCEEDED;
        // worktree 马上会被清理：先把文件产物搬出来，否则历史里的 ref 会立刻失效
        preserveArtifacts(state, runDir);
    }

    /**
     * 从失败阶段继续：复用保留的构建现场，只重跑该阶段及其后续阶段。
     *
     * <p>本机部署（dev）没有 worktree，直接整条重跑（会重新分配端口/重启进程）。
     */
    @Override
    public String retryStage(String runId, String stageId) {
        RunState state = runs.get(runId);
        if (state == null) {
            throw new IllegalArgumentException("运行不存在：" + runId);
        }
        if (!state.status.isTerminal()) {
            throw new IllegalArgumentException("运行仍在进行中，不能重试");
        }
        if (state.status != StageStatus.FAILED) {
            throw new IllegalArgumentException("只有失败的运行可以重试");
        }
        if (isLocalDev(state)) {
            state.stages.clear();
            state.artifacts.clear();
            state.log.append("[retry] 重新执行本机部署\n");
            state.status = StageStatus.RUNNING;
            state.cancelled = false;
            history.save(state.snapshot());
            scheduler.submit(state.projectId, () -> workers.submit(() -> execute(state)));
            return runId;
        }
        PipelineDefinition definition = state.definition;
        Path runDir = state.keptRunDir;
        if (definition == null || runDir == null || !Files.isDirectory(runDir)) {
            throw new IllegalArgumentException("构建现场已清理，无法从该阶段继续，请整条重跑");
        }
        List<PipelineDefinition.StageDef> all = definition.stages();
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(stageId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new IllegalArgumentException("阶段不可重试：" + stageId + "（请整条重跑）");
        }
        // 保留该阶段之前的结果，并回收该阶段及之后产生的产物（避免重试后产物重复）
        java.util.Set<String> before = new java.util.HashSet<>();
        for (int i = 0; i < idx; i++) {
            before.add(all.get(i).id());
        }
        List<StageResult> prefix = new ArrayList<>();
        for (StageResult r : state.stages) {
            if (before.contains(r.stageId()) && prefix.size() < idx) {
                prefix.add(r);
            }
        }
        state.stages.clear();
        state.stages.addAll(prefix);
        Integer mark = state.artifactMark.get(stageId);
        int cut = mark == null ? state.artifacts.size() : Math.min(mark, state.artifacts.size());
        if (cut < state.artifacts.size()) {
            state.artifacts.subList(cut, state.artifacts.size()).clear();
        }

        String tag = imagePrefix + "-" + state.env + "-v" + state.seq
                + "-" + state.gitSha.substring(0, 7);
        Map<String, String> vars = Map.of(
                "gitSha", state.gitSha,
                "gitSha7", state.gitSha.substring(0, 7),
                "tag", tag);
        state.status = StageStatus.RUNNING;
        state.cancelled = false;
        state.log.append("[retry] 从阶段 ").append(stageId).append(" 继续（复用构建现场）\n");
        history.save(state.snapshot());
        int from = idx;
        scheduler.submit(state.projectId, () -> workers.submit(
                () -> executeRetry(state, definition, runDir, vars, from)));
        return runId;
    }

    private void executeRetry(RunState state, PipelineDefinition definition, Path runDir,
                              Map<String, String> vars, int from) {
        try {
            runStagesFrom(state, definition, runDir, vars, from);
            recoverIfNeeded(state);
        } catch (Throwable e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            state.log.append("[engine] 重试异常：").append(why).append('\n');
            state.stages.add(StageResult.failed("engine", 1, "E-2xx", why, 0));
            state.status = StageStatus.FAILED;
        } finally {
            if (state.status == StageStatus.SUCCEEDED) {
                if (state.keptBare != null) {
                    cleanupWorktree(state.keptBare, runDir, state);
                }
                state.keptRunDir = null;
                state.keptBare = null;
            }
            history.save(state.snapshot());
            scheduler.complete(state.projectId);
        }
    }

    // ------------------------------------------------------------ 开发环境本机部署

    /** 镜像版本（展示用）：{@code test-v3}；旧运行没有序号时回落为提交短 sha。 */
    private static String versionOf(RunState state) {
        return state.seq > 0
                ? state.env + "-v" + state.seq
                : state.gitSha.substring(0, Math.min(7, state.gitSha.length()));
    }

    /**
     * 开发环境固定本机直跑（不允许 Docker）。
     *
     * <p>开发环境语义就是「当前工作区的前后端跑在本机」，这样平台可以随时停止、把运行权交还 IDE；
     * 因此即便调用方传了 {@code deployTarget=docker} 也不生效（{@link #trigger} 会记一条忽略说明）。
     */
    private boolean isLocalDev(RunState state) {
        return localDevBranch(state.env, state.pipelineId, localDevHost != null);
    }

    /**
     * dev 分支判定（包内可见便于测试）。
     *
     * <p>只由环境与流水线决定：<b>没有</b> {@code deployTarget} 入参，因此任何调用方都无法把 dev 变成 Docker。
     *
     * @param hostDeployerAvailable 平台是否装配了本机托管能力
     */
    static boolean localDevBranch(String env, String pipelineId, boolean hostDeployerAvailable) {
        return hostDeployerAvailable
                && "dev".equalsIgnoreCase(env)
                && !"verify".equals(pipelineId);
    }

    /**
     * 停止平台托管的开发环境本机前后端（页面「停止本机服务」）。
     *
     * @return true = 至少停掉一个仍在运行的进程
     */
    public boolean stopLocalDev(String projectId, String env) {
        if (localDevHost == null || repoLocator == null) {
            return false;
        }
        Path repo = repoLocator.repoDir(projectId).orElse(null);
        if (repo == null) {
            return false;
        }
        return localDevHost.stop(repo, projectId, env == null || env.isBlank() ? "dev" : env);
    }

    /**
     * 开发环境本机编排：workspace → build → frontend → deploy → health。
     *
     * <p>与 Docker 目标的差别：直接在当前工作区构建（不检出、不建 worktree），
     * 进程由 {@link LocalDevHostDeployer} 托管，随时可停止后改用 IDE 启动。
     */
    private void executeLocalDev(RunState state) {
        Path repo = repoLocator == null ? null : repoLocator.repoDir(state.projectId).orElse(null);
        if (repo == null) {
            state.log.append("[dev] 定位不到项目本地目录（projectId=")
                    .append(state.projectId).append("）\n");
            state.stages.add(StageResult.failed("workspace", 1, "E-601",
                    "本机部署需要项目本地工作区：请先在「接入项目」选择本地目录", 0));
            state.status = StageStatus.FAILED;
            return;
        }
        state.deployTargetUsed = DeployTarget.HOST.wireName();
        long start = System.nanoTime();
        state.log.append("[dev] 本机模式：直接使用工作区 ").append(repo)
                .append("（不检出提交）\n");
        state.log.append("[env] ").append(state.env)
                .append(" · 提交 ").append(shortSha(state.gitSha)).append('\n');
        state.stages.add(StageResult.success("workspace", 0, List.of(), elapsed(start)));

        // dev 固定本机运行：先清掉历史遗留的 dev 应用容器，避免占端口（打开的还是容器里的旧页面）
        StringBuilder cleanupLog = new StringBuilder();
        dockerDeployer.stopDevContainers(repo, state.projectId, state.env, cleanupLog);
        state.log.append(cleanupLog);
        // 还必须停掉上一轮平台托管的本机 java/node：Windows 下进程持有 target/*.jar，
        // spring-boot repackage 无法把它重命名为 .original，mvn 会直接失败（E-601 jar 锁）
        if (localDevHost.stop(repo, state.projectId, state.env)) {
            state.log.append("[dev] 已停止上一轮本机进程（释放 jar 文件锁）\n");
        }

        Path work = LocalDevHostDeployer.stateDir(repo, state.projectId, state.env);
        try {
            Files.createDirectories(work);
        } catch (IOException e) {
            failLocalDev(state, "workspace", "E-601", "无法创建部署目录：" + e.getMessage(), null);
            return;
        }

        // 1. 后端构建
        Path backendRoot = detectBackendRoot(repo);
        if (backendRoot == null) {
            failLocalDev(state, "build", "E-601",
                    "未找到后端模块（仓库根或一级子目录需要 pom.xml）", null);
            return;
        }
        long buildStart = System.nanoTime();
        Path mavenLog = work.resolve("maven.log");
        int exit = localDevHost.mvnPackage(backendRoot, mavenLog);
        if (exit != 0) {
            String tail = localDevHost.logTail(mavenLog);
            // mvn 带 -q，明细全在日志里；不把首条 [ERROR] 捞进摘要，面板上就只剩一个退出码
            failLocalDev(state, "build", "E-601",
                    "后端构建失败：" + commandReason(exit, "mvn") + firstErrorSuffix(tail), tail);
            return;
        }
        Path jar = findRunnableJar(backendRoot);
        if (jar == null) {
            String tail = localDevHost.logTail(mavenLog);
            failLocalDev(state, "build", "E-601",
                    "构建完成但没有可运行 jar（target/*.jar）：" + backendRoot + firstErrorSuffix(tail),
                    tail);
            return;
        }
        state.log.append("[dev] 后端产物 ").append(jar).append('\n');
        try {
            // 与 recipe 路径一致：本机部署也把 jar 登记为文件产物，产物面板可见/可追溯
            state.artifacts.add(new ArtifactRef("file",
                    repo.relativize(jar).toString().replace('\\', '/'), null,
                    Files.size(jar), Map.of("source", "local-dev")));
        } catch (IOException e) {
            state.log.append("[dev] 产物登记失败（忽略）：").append(e.getMessage()).append('\n');
        }
        state.stages.add(StageResult.success("build", 0, List.of(), elapsed(buildStart)));

        // 2. 前端构建（有前端模块才做）
        Path frontendRoot = detectFrontendRoot(repo);
        Path dist = null;
        if (frontendRoot == null) {
            state.log.append("[dev] 未检测到前端模块，跳过前端构建与托管\n");
            state.stages.add(StageResult.success("frontend", 0, List.of(), 0));
        } else {
            dist = frontendRoot.resolve("dist");
            long feStart = System.nanoTime();
            if (isFrontendUpToDate(frontendRoot, dist)) {
                state.log.append("[dev] 前端产物较新，跳过构建：").append(dist).append('\n');
            } else {
                Path npmLog = work.resolve("npm.log");
                int feExit = localDevHost.buildFrontend(frontendRoot, npmLog);
                if (feExit != 0) {
                    String tail = localDevHost.logTail(npmLog);
                    failLocalDev(state, "frontend", "E-602",
                            "前端构建失败：" + commandReason(feExit, "npm") + firstErrorSuffix(tail),
                            tail);
                    return;
                }
                state.log.append("[dev] 前端产物 ").append(dist).append('\n');
            }
            state.stages.add(StageResult.success("frontend", 0, List.of(), elapsed(feStart)));
        }

        // 3. 中间件（尽力而为）：本机后端同样要连数据库/缓存；失败只告警，不阻塞本机部署
        if (envRuntime != null) {
            try {
                top.rongx.agent.domain.env.EnvRuntime.Result runtime =
                        envRuntime.ensureRunning(state.projectId, state.env);
                state.log.append("[middleware] ").append(runtime.message()).append('\n');
            } catch (Exception e) {
                state.log.append("[middleware] 检查异常（继续本机部署）：")
                        .append(e.getMessage() == null ? e.toString() : e.getMessage()).append('\n');
            }
        }

        // 3.1 把仓库 .env 里已有的非空变量写入项目覆盖：dev 不建容器，但也必须注入源码里的连接信息
        if (envProvisioner != null) {
            try {
                List<String> synced = envProvisioner.syncEndpoints(state.projectId, state.env);
                if (!synced.isEmpty()) {
                    state.log.append("[env] 中间件地址已按本机模式校正（127.0.0.1 + 发布端口）：")
                            .append(String.join("、", synced)).append('\n');
                }
                List<String> seeded = envProvisioner.seedDeclared(state.projectId, state.env);
                if (!seeded.isEmpty()) {
                    state.log.append("[env] 已按项目 .env 注入变量：")
                            .append(String.join("、", seeded)).append("（值不回显）\n");
                }
            } catch (Exception e) {
                state.log.append("[env] 读取项目 .env 失败（继续本机部署）：")
                        .append(e.getMessage() == null ? e.toString() : e.getMessage()).append('\n');
            }
        }

        Map<String, String> deployEnv = envStore == null
                ? loadDeployEnv(state)
                : localHostEnv(envStore.effective(state.projectId, state.env).values(), state.env);

        int backendPort = allocateHostPort(state, DeployTarget.HOST, 8688, 8688);
        int frontendPort = dist == null ? 0
                : allocateDistinctHostPort(state, 8689, 8689, backendPort);

        long deployStart = System.nanoTime();
        LocalDevHostDeployer.Result result = localDevHost.deploy(repo, state.projectId, state.env,
                jar, dist, backendPort, frontendPort, deployEnv);
        state.log.append(result.log());
        if (!result.ok()) {
            failLocalDev(state, "deploy", result.errorCode(), result.summary(), null);
            return;
        }
        List<ArtifactRef> artifacts = List.of(new ArtifactRef("deployment",
                result.endpoint(), null, 0,
                Map.of("target", "host",
                        "backendPort", String.valueOf(result.backendPort()),
                        "frontendPort", String.valueOf(result.frontendPort()))));
        state.stages.add(StageResult.success("deploy", 0, artifacts, elapsed(deployStart)));
        state.artifacts.addAll(artifacts);
        state.deployHostPort = result.frontendPort() > 0
                ? result.frontendPort() : result.backendPort();

        // 4. 健康检查：必须先证后端进程可用（前端静态服务器起来不代表后端活着，否则就是假绿）
        long healthStart = System.nanoTime();
        String backendBase = "http://127.0.0.1:" + result.backendPort();
        String liveness = backendBase + "/actuator/health/liveness";
        if (!probeLocalDev(state, liveness, 20)) {
            // 非 Spring Boot 项目没有 actuator：退化为根路径探活，并明确记录，避免误判成功
            if (!probeLocalDev(state, backendBase + "/", 10)) {
                failLocalDev(state, "health", "E-604", "后端探活未通过：" + liveness, null);
                return;
            }
            state.log.append("[health] 后端未提供 /actuator/health/liveness，已退化为 "
                    + backendBase + "/ 探活（未校验依赖）\n");
        }
        if (result.frontendPort() > 0 && !probeLocalDev(state, result.endpoint(), 20)) {
            failLocalDev(state, "health", "E-604",
                    "前端静态服务健康检查未通过：" + result.endpoint(), null);
            return;
        }
        state.stages.add(StageResult.success("health", 0, List.of(), elapsed(healthStart)));
        state.log.append("[recipe] 本机模式不固化环境脚本（直接使用工作区）\n");
        state.status = StageStatus.SUCCEEDED;
    }

    /**
     * 本机模式的环境变量处理：平台中间件用容器内网名，宿主机解析不到，
     * 这里换成「127.0.0.1 + 发布端口」；项目配置（DB_* / REDIS_*）本身不用改。
     */
    private Map<String, String> localHostEnv(Map<String, String> effective, String env) {
        Map<String, String> out = new LinkedHashMap<>(effective);
        resolveToHost(out, env, "DB", "mysql");
        resolveToHost(out, env, "REDIS", "redis");
        return out;
    }

    /** 变量里的地址是容器名/内网名时，替换成宿主机可达的 host:port（找不到同名资源就原样保留）。 */
    private void resolveToHost(Map<String, String> vars, String env, String prefix, String kind) {
        String host = vars.get(prefix + "_HOST");
        if (envStore == null || host == null || host.isBlank()) {
            return;
        }
        String name = host.trim();
        EnvResource matched = null;
        for (EnvResource resource : envStore.resources(env)) {
            if (resource == null || !kind.equals(resource.kind())) {
                continue;
            }
            if (!name.equalsIgnoreCase(resource.internalHost())
                    && !name.equalsIgnoreCase(resource.name())) {
                continue;
            }
            // 同名资源里优先取正在运行的那个，避免命中同名的历史/已停资源
            if (matched == null || (!matched.running() && resource.running())) {
                matched = resource;
            }
            if (resource.running()) {
                break;
            }
        }
        if (matched == null) {
            return;
        }
        if (matched.host() != null && !matched.host().isBlank()) {
            vars.put(prefix + "_HOST", matched.host());
        }
        if (matched.port() > 0) {
            vars.put(prefix + "_PORT", String.valueOf(matched.port()));
        }
        // 库名/账号/口令同样以匹配到的中间件资源为准：否则会残留别的资源（如 pg 的 postgres/5432）
        if ("DB".equals(prefix)) {
            if (matched.databaseName() != null && !matched.databaseName().isBlank()) {
                vars.put("DB_NAME", matched.databaseName());
            }
            if (matched.username() != null && !matched.username().isBlank()) {
                vars.put("DB_USERNAME", matched.username());
            }
        }
        if (matched.password() != null && !matched.password().isEmpty()) {
            vars.put(prefix + "_PASSWORD", matched.password());
        }
    }

    private void failLocalDev(RunState state, String stageId, String code, String summary,
                              String tail) {
        state.log.append('[').append(stageId).append("] ").append(summary).append('\n');
        if (tail != null && !tail.isBlank()) {
            state.log.append(tail).append('\n');
        }
        state.stages.add(StageResult.failed(stageId, 1, code == null ? "E-601" : code,
                summary, 0));
        state.status = StageStatus.FAILED;
    }

    /** 退出码 → 可读原因（构建超时/命令缺失比裸数字有用）。 */
    private static String commandReason(int exit, String tool) {
        if (exit == -1) {
            return tool + " 命令无法启动（检查 PATH）";
        }
        if (exit == -2) {
            return "构建超时或被中断";
        }
        return tool + " 退出码 " + exit;
    }

    /** 构建日志第一条 [ERROR] 行的摘要后缀（mvn/npm 带 -q 时面板只能看到退出码）。 */
    private static String firstErrorSuffix(String tail) {
        if (tail == null || tail.isBlank()) {
            return "";
        }
        for (String line : tail.split("\\R")) {
            String text = line.trim();
            if (text.startsWith("[ERROR]") && text.length() > "[ERROR]".length()) {
                return "：" + (text.length() > 300 ? text.substring(0, 300) + "…" : text);
            }
        }
        return "";
    }

    private boolean probeLocalDev(RunState state, String endpoint, int attempts) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        for (int i = 0; i < attempts; i++) {
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create(endpoint))
                                .timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 400) {
                    state.log.append("[health] ").append(response.statusCode()).append(' ')
                            .append(endpoint).append('\n');
                    return true;
                }
            } catch (Exception ignored) {
                // 静态服务器尚未起来，继续重试
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 后端根：仓库根有 pom.xml 用它；否则一层子目录（RuoYi 的 RuoYi-Vue/）。 */
    private static Path detectBackendRoot(Path repo) {
        if (Files.isRegularFile(repo.resolve("pom.xml"))) {
            return repo;
        }
        for (Path child : children(repo)) {
            if (Files.isRegularFile(child.resolve("pom.xml"))
                    || Files.isRegularFile(child.resolve("ruoyi-admin/pom.xml"))
                    || Files.isDirectory(child.resolve("src/main/java"))) {
                return child;
            }
        }
        return null;
    }

    /** 前端根：仓库根 package.json；否则一层子目录；再否则两层（RuoYi-Vue/ruoyi-ui）。 */
    private static Path detectFrontendRoot(Path repo) {
        if (Files.isRegularFile(repo.resolve("package.json"))) {
            return repo;
        }
        List<Path> first = children(repo);
        for (Path child : first) {
            if (Files.isRegularFile(child.resolve("package.json"))) {
                return child;
            }
        }
        List<String> names = List.of("ui", "web", "frontend", "client", "ruoyi-ui");
        for (Path child : first) {
            for (Path second : children(child)) {
                if (names.contains(second.getFileName().toString().toLowerCase())
                        && Files.isRegularFile(second.resolve("package.json"))) {
                    return second;
                }
            }
        }
        return null;
    }

    private static List<Path> children(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 可运行 jar：优先含 BOOT-INF 的 Spring Boot 包，多个取最新。 */
    private static Path findRunnableJar(Path backendRoot) {
        try (var stream = Files.walk(backendRoot, 4)) {
            List<Path> candidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-javadoc.jar"))
                    .filter(p -> p.getParent() != null
                            && "target".equals(p.getParent().getFileName().toString()))
                    .filter(BuiltInPipelineEngine::isExecutableJar)
                    .toList();
            return candidates.stream()
                    .max(java.util.Comparator.comparingLong(BuiltInPipelineEngine::lastModified))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isExecutableJar(Path jar) {
        try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar.toFile())) {
            return file.getEntry("BOOT-INF/") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** 构建日志尾部（供流水线展示）。 */
    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 前端 dist 是否比 src 新（避免每次发布都全量打包）。 */
    private static boolean isFrontendUpToDate(Path frontendRoot, Path dist) {
        Path index = dist.resolve("index.html");
        if (!Files.isRegularFile(index)) {
            return false;
        }
        Path src = frontendRoot.resolve("src");
        if (!Files.isDirectory(src)) {
            return true;
        }
        long distTime = lastModified(index);
        try (var stream = Files.walk(src)) {
            return stream.filter(Files::isRegularFile)
                    .noneMatch(p -> lastModified(p) > distTime);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 检出（v2）：不再 clone，用 git worktree add——毫秒级、零重复存储。
     * source 现在是 bare 仓库（{@code <workspace>/<id>/git}）；runDir 即本次构建的 worktree
     * （{@code worktrees/wt-build-<runId>}），构建终态后由 cleanupWorktree 移除。
     */
    private void checkout(Path bare, Path runDir, RunState state) {
        long start = System.nanoTime();
        ActionResult add = host.run(bare, spec("checkout", List.of(
                List.of("git", "worktree", "add", "--quiet", "--detach",
                        posix(runDir), state.gitSha))));
        if (add.result().status() != StageStatus.SUCCEEDED) {
            // 新推送的提交可能还没进 bare：提示前置同步
            throw new IllegalStateException("git worktree add " + state.gitSha
                    + " 失败：" + add.result().errorSummary()
                    + "（若为最新提交，请先在项目详情里执行「同步到平台」）");
        }
        state.log.append("[checkout] worktree ").append(state.gitSha).append('\n');
        state.stages.add(StageResult.success("checkout", 0, List.of(),
                (System.nanoTime() - start) / 1_000_000L));
    }

    /** 构建终态后清理本次 worktree（失败不影响主流程，prune 兜底）。 */
    private void cleanupWorktree(Path bare, Path runDir, RunState state) {
        ActionResult rm = host.run(bare, spec("checkout", List.of(
                List.of("git", "worktree", "remove", "--force", posix(runDir)))));
        if (rm.result().status() == StageStatus.SUCCEEDED) {
            state.log.append("[cleanup] worktree removed\n");
        } else {
            state.log.append("[cleanup] worktree 清理失败（prune 兜底）：")
                    .append(rm.result().errorSummary()).append('\n');
        }
    }

    private boolean runStage(RunState state, PipelineDefinition.StageDef stage,
                             Path runDir, Map<String, String> vars) {
        long start = System.nanoTime();
        try {
            if ("git-checkout".equals(stage.type())) {
                return true; // 已在 execute 里完成
            }
            if ("health".equals(stage.type())) {
                return health(state, stage, start);
            }
            if ("deploy".equals(stage.type())) {
                return deploy(state, runDir, start, stage.id(), false);
            }
            if ("rollback".equals(stage.type())) {
                return deploy(state, runDir, start, stage.id(), true);
            }

            ActionId action = ActionId.fromWire(stage.action()).orElseThrow(
                    () -> new IllegalArgumentException("未知动作：" + stage.action()));
            // 配方根 = 定义所在的根：仓库定义 → 检出目录；平台定义 → 平台目录。
            // 命令本身仍在 stageDir（检出目录 / 子目录）里执行。
            Optional<ActionSpec> resolved = recipes.resolve(state.recipeRoot, action);
            if (resolved.isEmpty()) {
                state.stages.add(StageResult.failed(stage.id(), 1, "E-201",
                        "动作 " + action.wireName() + " 没有配方", elapsed(start)));
                return false;
            }
            ActionSpec original = resolved.get();
            ActionSpec substituted = substitute(original, vars);
            ActionRunner runner = selectRunner(stage);
            // monorepo：stage 可在子目录执行（如 backend / frontend），
            // 但产物与镜像仍按仓库根记账，避免各模块配方各写一套路径。
            Path stageDir = (stage.workdir() == null || stage.workdir().isBlank())
                    ? runDir : runDir.resolve(stage.workdir()).normalize();
            // 与预览同一套渲染：日志里的命令就是触发前看到的命令（所见即所跑）
            String shell = shellFor(stage);
            state.log.append('[').append(stage.id()).append("] $ ")
                    .append(substituted.steps().stream()
                            .map(argv -> renderCommand(argv, shell))
                            .collect(Collectors.joining(" && ")))
                    .append('\n');
            // 边跑边写：动作输出实时进入运行日志，页面轮询即可逐行看到构建进度。
            StageLog live = new StageLog(state, stage.id());
            ActionResult result = runner.run(stageDir, substituted, () -> state.cancelled, live);
            live.flush();

            if (!live.streamed() || live.truncated()) {
                synchronized (state.log) {
                    state.log.append('[').append(stage.id()).append("] ")
                            .append(result.logTail()).append('\n');
                }
            }
            // 失败时必须把错误码与原因写进运行日志：像"构建超时"这种没有命令输出的失败，
            // 否则日志只剩一个空的 [build]，Agent 和用户都无法诊断。
            StageResult stageResult = rename(result.result(), stage.id());
            if (stageResult.status() == StageStatus.FAILED) {
                state.log.append('[').append(stage.id()).append("] ❌ ")
                        .append(stageResult.errorCode() == null ? "" : stageResult.errorCode() + " ")
                        .append(stageResult.errorSummary() == null ? "" : stageResult.errorSummary())
                        .append('\n');
            }
            state.stages.add(stageResult);
            state.artifacts.addAll(prefixArtifacts(result.result().artifacts(), stage.workdir()));

            if (result.result().status() == StageStatus.SUCCEEDED
                    && "image_package".equals(stage.action())) {
                String tag = vars.get("tag");
                state.artifacts.add(new ArtifactRef("image", tag,
                        imageDigest(runDir, tag), imageSize(runDir, tag),
                        Map.of("runId", state.runId,
                                "version", versionOf(state),
                                "localImage", tag)));
            }
            return result.result().status() == StageStatus.SUCCEEDED;
        } catch (Exception e) {
            state.stages.add(StageResult.failed(stage.id(), 1, "E-2xx", e.getMessage(), elapsed(start)));
            return false;
        }
    }

    /**
     * 把文件产物从 worktree 搬到 {@code <workspace>/artifacts/<runId>/}。
     *
     * <p>worktree 在运行结束后无论成败都会删除（见 finally），产物路径会立刻失效。
     * 这里在删除前复制一份，并把历史里的 ref 改写成归档后的绝对路径，保证
     * 「产物面板」上的路径在之后仍能打开；镜像与部署产物本来就是外部引用，原样保留。
     * 只归档成功的运行：失败运行的半成品没有追溯价值，也不该占盘。
     */
    private void preserveArtifacts(RunState state, Path runDir) {
        if (state.artifacts.isEmpty()) {
            return;
        }
        Path dir = workspaceRoot.resolve("artifacts").resolve(state.runId);
        List<ArtifactRef> archived = new ArrayList<>(state.artifacts.size());
        int saved = 0;
        for (ArtifactRef a : state.artifacts) {
            if (!"file".equals(a.kind())) {
                archived.add(a);
                continue;
            }
            Path src = runDir.resolve(a.ref()).normalize();
            if (!src.startsWith(runDir) || !Files.isRegularFile(src)) {
                archived.add(a);
                continue;
            }
            try {
                Path dst = dir.resolve(a.ref()).normalize();
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                archived.add(new ArtifactRef(a.kind(), dst.toAbsolutePath().toString(),
                        a.digest(), a.sizeBytes(), a.meta()));
                saved++;
            } catch (IOException e) {
                // 归档失败不影响构建结论：保留原 ref，让用户知道产物已随 worktree 清理
                state.log.append("[artifacts] 归档失败：").append(a.ref())
                        .append("（").append(e.getMessage()).append("）\n");
                archived.add(a);
            }
        }
        state.artifacts.clear();
        state.artifacts.addAll(archived);
        if (saved > 0) {
            state.log.append("[artifacts] ").append(saved).append(" 个文件产物已归档到 ")
                    .append(dir.toAbsolutePath()).append('\n');
        }
    }

    /** 把子目录 stage 的相对产物路径补成仓库根相对路径（便于统一记账/下载）。 */
    private List<ArtifactRef> prefixArtifacts(List<ArtifactRef> artifacts, String workdir) {
        if (workdir == null || workdir.isBlank()) {
            return artifacts;
        }
        String prefix = workdir.replace('\\', '/').replaceAll("^/+|/+$", "") + "/";
        return artifacts.stream()
                .map(a -> new ArtifactRef(a.kind(), prefix + a.ref(), a.digest(),
                        a.sizeBytes(), a.meta()))
                .toList();
    }

    /**
     * 选择执行器：host 走宿主；container 且声明了 {@code image} 时，
     * 用该 stage 专属工具链镜像新建一个沙箱（同一套隔离参数，只换镜像）。
     */
    private ActionRunner selectRunner(PipelineDefinition.StageDef stage) {
        if ("host".equals(stage.runner())) {
            return host;
        }
        if (stage.image() == null || stage.image().isBlank()) {
            return sandboxed;
        }
        if (sandboxed instanceof DockerActionRunner dockerRunner) {
            DockerSandbox base = dockerRunner.sandbox();
            return new DockerActionRunner(base.withImage(stage.image()));
        }
        // 宿主模式下忽略 image（工具链由宿主机提供）
        return host;
    }

    /**
     * 部署配置受审校验（E-508）：本次运行的 {@code ci/pipeline.yml} 必须与默认分支一致。
     *
     * <p>为什么用 blob 哈希而不是解析 YAML 比对：日志管道会截断尾部（tail），
     * 解析可能因截断得到 null，从而"看起来一致"——那比不校验更危险。
     * 哈希对比是确定性的：任何未合并的流水线改动都会被拦住。
     *
     * <p>无法读取默认分支（无 main/master、文件缺失）时**放行并记录告警**：
     * 这是可用性取舍（新仓库可能还没有默认分支），日志留痕便于审计。
     */
    private boolean deployGuard(Path bare, Path runDir, PipelineDefinition definition, RunState state) {
        if (definition.deploy() == null) {
            return true; // 纯构建流水线不受约束
        }
        if (state.definitionSource != null && state.definitionSource != PipelineDefinitionResolver.Source.REPO) {
            // 定义来自平台（自动生成/人工维护），仓库里没有可比对的版本：
            // 受审校验的对象是"仓库里的 deploy 配置"，对平台定义不适用。
            state.log.append("[guard] 定义来源=").append(state.definitionSource)
                    .append("，跳过'部署配置与默认分支一致'校验\n");
            return true;
        }
        // 默认分支：先问 bare 的 HEAD 指向哪条分支，再退回 main/master
        List<String> branches = defaultBranches(bare);
        for (String branch : branches) {
            ActionResult reviewed = host.run(bare, spec("deploy-guard", List.of(
                    List.of("git", "rev-parse", "--verify", branch + ":ci/pipeline.yml"))), () -> false);
            if (reviewed.result().status() != StageStatus.SUCCEEDED) {
                continue;
            }
            String reviewedHash = reviewed.logTail().trim();
            ActionResult current = host.run(runDir, spec("deploy-guard", List.of(
                    List.of("git", "hash-object", "ci/pipeline.yml"))), () -> false);
            if (current.result().status() != StageStatus.SUCCEEDED) {
                state.log.append("[guard] 无法计算本次 pipeline.yml 哈希，跳过校验\n");
                return true;
            }
            String currentHash = current.logTail().trim();
            if (reviewedHash.equals(currentHash)) {
                state.log.append("[guard] deploy 配置与默认分支(").append(branch).append(")一致\n");
                return true;
            }
            state.log.append("[guard] 默认分支(").append(branch).append(") blob=")
                    .append(reviewedHash).append(" 本次 blob=").append(currentHash).append('\n');
            return false;
        }
        state.log.append("[guard] 无法读取默认分支的 ci/pipeline.yml（尝试：")
                .append(String.join(",", branches)).append("），跳过部署配置校验\n");
        return true;
    }

    /**
     * 平台部署（方案 C）：读 pipeline.yml 的 {@code deploy:} 段 + 本次运行的 target 覆盖，
     * 交给部署器执行；成功时登记 {@code deployment} 产物（endpoint 即证据）。
     *
     * @param rollback true = 回滚到上一版镜像（lastGood）
     */
    private boolean deploy(RunState state, Path runDir, long start, String stageId, boolean rollback) {
        PipelineDefinition.DeploySpec spec = state.definition == null ? null : state.definition.deploy();
        if (spec == null) {
            state.stages.add(StageResult.failed(stageId, 1, "E-506",
                    "pipeline.yml 缺少 deploy 段（target/port），无法判断部署目标", elapsed(start)));
            return false;
        }
        String desired = state.deployTargetOverride != null && !state.deployTargetOverride.isBlank()
                ? state.deployTargetOverride : spec.target();
        DeployTarget target = DeployTarget.fromWire(desired).orElse(null);
        if (target == null) {
            state.stages.add(StageResult.failed(stageId, 1, "E-506",
                    "未知部署目标：" + desired + "（可选 docker / host / server）", elapsed(start)));
            return false;
        }

        String currentImage = state.artifacts.stream()
                .filter(a -> "image".equals(a.kind()))
                .reduce((first, second) -> second)
                .map(ArtifactRef::ref)
                .orElse(null);
        String previous = lastGood.get(state.projectId);
        String image = rollback ? previous : currentImage;
        if (rollback && (image == null || image.isBlank())) {
            state.stages.add(StageResult.failed(stageId, 1, "E-507",
                    "没有可回滚的上一版（本项目尚无成功发布记录）", elapsed(start)));
            return false;
        }

        EffectiveEnvironment effective = envStore == null
                ? null : envStore.effective(state.projectId, state.env);
        Map<String, String> deployEnv = effective != null
                ? effective.values() : loadDeployEnv(state);
        String network = effective == null ? null : effective.network();
        // 环境模板绑定：运行形态 / 命名空间 / 服务器，部署语义以模板为准。
        top.rongx.agent.domain.env.EnvTemplate template = envStore == null
                ? null : envStore.template(state.env).orElse(null);
        String serverId = spec.serverId() != null
                ? spec.serverId() : (template == null ? null : template.serverId());

        // 部署目标自愈：产物是镜像就不可能用 HOST 跑起来，产物是 jar 就没必要再起容器。
        // 环境策略/前端默认值都可能给出错的目标，这里按本次真实产物纠正，并在日志说明。
        DeployTarget reconciled = reconcileTarget(state, target, image, rollback, runDir, spec);
        if (reconciled == null) {
            state.stages.add(StageResult.failed(stageId, 1, "E-504",
                    "无法确定部署目标：本次既没有镜像也没有可运行 jar。"
                            + "Java 项目请确认构建产物 target/*.jar；前端静态产物请在 deploy 段选择 docker",
                    elapsed(start)));
            return false;
        }
        target = reconciled;

        // 环境容器自愈（E-509 之前）：必填变量指向的中间件缺失时按需创建，
        // 连接信息写入项目覆盖；密钥类必填变量缺失时自动生成。失败才 fail-closed。
        if (envProvisioner != null && envStore != null && spec.envRequired() != null
                && !spec.envRequired().isEmpty()) {
            top.rongx.agent.domain.env.EnvProvisioner.Result provision =
                    envProvisioner.ensure(state.projectId, state.env, spec.envRequired());
            if (!provision.injected().isEmpty()) {
                state.log.append("[env] 已按项目 .env 注入变量：")
                        .append(String.join("、", provision.injected()))
                        .append("（值不回显）\n");
            }
            if (!provision.created().isEmpty()) {
                state.log.append("[env] 已创建中间件容器：")
                        .append(String.join("、", provision.created())).append('\n');
            }
            if (!provision.started().isEmpty()) {
                state.log.append("[env] 已启动中间件容器：")
                        .append(String.join("、", provision.started())).append('\n');
            }
            if (!provision.generated().isEmpty()) {
                state.log.append("[env] 已自动生成变量：")
                        .append(String.join("、", provision.generated()))
                        .append("（写入项目环境覆盖）\n");
            }
            if (!provision.ok()) {
                state.stages.add(StageResult.failed(stageId, 1, "E-702",
                        provision.message() + "。请检查本机 Docker 是否可用，或在「设置 → 环境」手动处理",
                        elapsed(start)));
                return false;
            }
            effective = envStore.effective(state.projectId, state.env);
            deployEnv = effective.values();
            network = effective.network();
        }

        // 应用也是容器：必须和中间件同处一个环境网络，才能用「容器名:容器端口」连接。
        if (target == DeployTarget.DOCKER && (network == null || network.isBlank())) {
            String namespace = template == null || template.namespace() == null
                    || template.namespace().isBlank()
                    ? "agent-" + state.env : template.namespace().trim();
            String envNetwork = namespace + "-net";
            StringBuilder netLog = new StringBuilder();
            if (dockerDeployer.ensureNetwork(runDir, envNetwork, netLog)) {
                network = envNetwork;
                state.log.append("[deploy] 应用容器加入环境网络 ").append(envNetwork)
                        .append("（与中间件同网，连接信息使用容器名）\n");
            } else {
                state.log.append("[deploy] 环境网络 ").append(envNetwork)
                        .append(" 创建失败，按宿主机端口部署\n");
            }
        }

        // E-509 部署前预检：应用声明的必填环境变量必须在环境模板/项目覆盖里配齐。
        List<String> missingEnv = missingRequiredEnv(spec, deployEnv);
        if (!missingEnv.isEmpty()) {
            state.stages.add(StageResult.failed(stageId, 1, "E-509",
                    "部署前环境配置检查未通过：环境「" + state.env + "」缺少必填环境变量 "
                            + String.join("、", missingEnv)
                            + "。请到「设置 → 环境」或「项目覆盖」填写后重试（本次未启动容器）",
                    elapsed(start)));
            state.log.append("[deploy] E-509 预检失败：缺少 ").append(String.join("、", missingEnv))
                    .append("——请到「设置 → 环境」或「项目覆盖」填写（未启动容器）\n");
            return false;
        }
        if (!spec.envRequired().isEmpty()) {
            state.log.append("[deploy] 环境配置预检通过：必填 ")
                    .append(spec.envRequired().size()).append(" 项已配齐\n");
        }

        // 中间件预检（会尝试启动）：容器"没启动"不算异常，部署前把项目实际引用的
        // 中间件自动拉起来；只有启动失败 / 连接信息缺失才 fail-closed。
        if (effective != null && envRuntime != null) {
            top.rongx.agent.domain.env.EnvRuntime.Result runtime =
                    envRuntime.ensureRunning(state.projectId, state.env);
            state.log.append("[middleware] ").append(runtime.message()).append('\n');
            if (!runtime.ok()) {
                state.stages.add(StageResult.failed(stageId, 1, "E-701",
                        runtime.message() + "。请到「设置 → 环境」检查中间件连接信息或手动启动后重试",
                        elapsed(start)));
                return false;
            }
        }

        // test 形态：前后端各一个容器（nginx 反代到 <project>-backend:8080），
        // 网络与数据卷按命名空间隔离，走独立的多服务分支。
        if (target == DeployTarget.DOCKER && !spec.services().isEmpty()) {
            return deployServiceSet(state, runDir, start, stageId, spec, deployEnv, network,
                    template, rollback, image);
        }

        // 端口租约：冲突时自动在期望端口之后找空闲端口，保证同项目重复发布端点稳定。
        // DOCKER 单容器发布前先停同名旧容器：否则旧容器自己占着期望端口，会被判成"被占用"而漂移，
        // 每次发布端口 +1，健康检查 URL 也跟着换。
        if (target == DeployTarget.DOCKER) {
            String oldContainer = DockerDeployer.containerName(state.projectId, state.env);
            // 升级前的命名没有环境后缀：一并清理，否则它会一直占着期望端口。
            String legacyContainer = DockerDeployer.containerName(state.projectId);
            List<String> toRemove = legacyContainer.equals(oldContainer)
                    ? List.of(oldContainer) : List.of(oldContainer, legacyContainer);
            for (String old : toRemove) {
                ActionResult stopped = host.run(runDir, spec("deploy-stop-old",
                        List.of(List.of("docker", "rm", "-f", old))), () -> false);
                if (stopped.result().status() == StageStatus.SUCCEEDED) {
                    state.log.append("[deploy] 已停止上一版容器 ").append(old).append('\n');
                }
            }
            // docker rm 后宿主端口由 docker-proxy 异步释放：短暂等待可避免端口无谓漂移
            for (int i = 0; i < 20 && spec.port() > 0 && !portFree(spec.port()); i++) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        int hostPort = allocateHostPort(state, target, spec.port(), spec.containerPort());

        // 运行环境快照（放在端口分配后，快照里能带上本次租约端口）：
        // 只存来源、key 与值哈希，既可追溯"这次用了哪套变量"，又不泄露明文。
        if (effective != null) {
            long secretCount = effective.vars().stream().filter(v -> v.secret()).count();
            String digest = envDigest(deployEnv);
            state.log.append("[env] 环境=").append(state.env)
                    .append("，变量 ").append(deployEnv.size())
                    .append("（模板 ").append(effective.templateCount())
                    .append(" / 覆盖 ").append(effective.overrideCount())
                    .append("，敏感 ").append(secretCount)
                    .append("），网络=").append(network == null || network.isBlank() ? "（默认）" : network)
                    .append("，端口=").append(hostPort)
                    .append("，快照=").append(digest, 0, 12)
                    .append(state.actor == null || state.actor.isBlank() ? "" : "，触发=" + state.actor)
                    .append('\n');
            Map<String, String> snapshot = new java.util.LinkedHashMap<>();
            snapshot.put("variableCount", String.valueOf(deployEnv.size()));
            snapshot.put("templateCount", String.valueOf(effective.templateCount()));
            snapshot.put("overrideCount", String.valueOf(effective.overrideCount()));
            snapshot.put("secretCount", String.valueOf(secretCount));
            snapshot.put("network", network == null ? "" : network);
            snapshot.put("portLease", String.valueOf(hostPort));
            if (state.bindingId != null) {
                snapshot.put("bindingId", state.bindingId);
                snapshot.put("confirmedBy", state.confirmedBy == null ? "" : state.confirmedBy);
            }
            deployEnv.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> snapshot.put("keyHash:" + e.getKey(), sha256(e.getValue())));
            state.artifacts.add(new ArtifactRef("env-snapshot", "env:" + state.env, digest, 0, snapshot));
        }

        DeployRequest request = new DeployRequest(
                state.projectId,
                state.runId,
                state.gitSha,
                image,
                spec.artifact(),
                null,
                runDir.toAbsolutePath().toString(),
                target,
                hostPort,
                spec.containerPort(),
                spec.healthPath(),
                previous,
                rollback,
                serverId,
                deployEnv,
                network,
                state.env);

        DeployResult result = deployers.deploy(request);
        String label = stageId;
        state.log.append('[').append(label).append("] ").append(target.wireName())
                .append(" → ").append(result.endpoint())
                .append(deployEnv.isEmpty() ? "" : "（注入 " + deployEnv.size() + " 个环境变量）")
                .append(network == null || network.isBlank() ? "" : "（网络 " + network + "）")
                .append('\n')
                .append(result.log()).append('\n');

        if (!result.success()) {
            state.stages.add(StageResult.failed(label, 1, result.errorCode(),
                    result.summary(), elapsed(start)));
            return false;
        }
        state.deployTargetUsed = target.wireName();
        // 部署器可能在远端重新分配端口（ServerDeployer），以实际 endpoint 为准回写租约，
        // 否则下次发布会继续按旧端口检查，端点漂移且日志对不上。
        int actualPort = portOf(result.endpoint());
        if (actualPort > 0 && actualPort != hostPort && envStore != null) {
            state.log.append("[port] 部署器实际使用 ").append(actualPort)
                    .append("（期望 ").append(hostPort).append("），已更新租约\n");
            state.deployHostPort = actualPort;
            envStore.saveLease(new PortLease(state.projectId, state.env, spec.containerPort(),
                    actualPort, state.runId, System.currentTimeMillis()));
        }
        List<ArtifactRef> artifacts = List.of(new ArtifactRef("deployment",
                result.endpoint(), null, 0,
                Map.of("target", target.wireName(),
                        "resource", result.resource() == null ? "" : result.resource())));
        state.stages.add(StageResult.success(label, 0, artifacts, elapsed(start)));
        state.artifacts.addAll(artifacts);
        return true;
    }

    /**
     * 按本次真实产物纠正部署目标（agent 自愈的一部分）。
     *
     * <p>典型错配：环境默认目标是 host，但本次只产出了镜像（没有 target/*.jar），
     * 部署必然 E-504；反过来只产出 jar 却选了 docker 也会白起容器。
     *
     * @return 纠正后的目标；null = 既没有镜像也没有可运行 jar（无法部署）
     */
    private DeployTarget reconcileTarget(RunState state, DeployTarget requested, String image,
                                         boolean rollback, Path runDir,
                                         PipelineDefinition.DeploySpec spec) {
        if (rollback) {
            return requested; // 回滚沿用已有产物，不按本次构建判断
        }
        boolean hasImage = image != null && !image.isBlank();
        boolean hasJar = hasRunnableJar(runDir, spec.artifact());
        if (requested == DeployTarget.HOST) {
            if (hasJar) {
                return DeployTarget.HOST;
            }
            if (hasImage) {
                state.log.append("[deploy] 部署目标由 host 自动修正为 docker：本次已产出镜像 ")
                        .append(shortRef(image)).append("，HOST 只接受可运行 jar（target/*.jar）\n");
                return DeployTarget.DOCKER;
            }
            return null;
        }
        if (requested == DeployTarget.DOCKER) {
            if (hasImage || !spec.services().isEmpty()) {
                return DeployTarget.DOCKER;
            }
            if (hasJar) {
                state.log.append("[deploy] 部署目标由 docker 自动修正为 host：")
                        .append("本次没有镜像，但存在可运行 jar（target/*.jar）\n");
                return DeployTarget.HOST;
            }
            return null;
        }
        return requested;
    }

    private static String shortRef(String image) {
        return image.length() <= 24 ? image : image.substring(0, 24) + "…";
    }

    /** 本次工作区里是否有可运行 jar（显式 artifact 优先）。 */
    private static boolean hasRunnableJar(Path runDir, String artifact) {
        if (artifact != null && !artifact.isBlank()) {
            return Files.isRegularFile(runDir.resolve(artifact).normalize());
        }
        Path target = runDir.resolve("target");
        if (!Files.isDirectory(target)) {
            return false;
        }
        try (var stream = Files.list(target)) {
            return stream.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".jar") && !name.endsWith("-sources.jar")
                        && !name.endsWith("-javadoc.jar");
            });
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * test 形态多服务部署：每个服务一个容器，共用环境网络，按服务独立数据卷。
     *
     * <p>前端容器通过网络别名 {@code <project>-backend:<containerPort>} 访问后端
     * （不依赖宿主机端口），对外端点以前端服务为准。
     */
    private boolean deployServiceSet(RunState state, Path runDir, long start, String stageId,
                                     PipelineDefinition.DeploySpec spec,
                                     Map<String, String> deployEnv, String network,
                                     top.rongx.agent.domain.env.EnvTemplate template,
                                     boolean rollback, String image) {
        if (rollback) {
            state.stages.add(StageResult.failed(stageId, 1, "E-507",
                    "多服务部署暂不支持回滚：请重新发布或手动回退镜像", elapsed(start)));
            return false;
        }
        String namespace = template == null ? "agent-" + state.env : template.namespace();
        String net = network != null && !network.isBlank() ? network : namespace + "-net";
        StringBuilder netLog = new StringBuilder();
        if (!dockerDeployer.ensureNetwork(runDir, net, netLog)) {
            state.stages.add(StageResult.failed(stageId, 1, "E-502",
                    "环境网络 " + net + " 不可用：" + netLog, elapsed(start)));
            return false;
        }
        state.log.append("[deploy] 多服务发布：" ).append(spec.services().size())
                .append(" 个容器，网络 ").append(net)
                .append("，命名空间 ").append(namespace).append('\n');

        PipelineDefinition.ServiceSpec backend = spec.services().stream()
                .filter(s -> "backend".equals(s.name())).findFirst().orElse(null);
        String primaryEndpoint = null;
        int primaryPort = 0;
        String primaryResource = null;
        List<ArtifactRef> artifacts = new ArrayList<>();
        for (PipelineDefinition.ServiceSpec service : spec.services()) {
            int desired = service.hostPort() > 0
                    ? service.hostPort() : spec.port() + spec.services().indexOf(service);
            int hostPort = allocateHostPort(state, DeployTarget.DOCKER, desired,
                    service.containerPort());
            Map<String, String> svcEnv = deployEnv;
            if ("frontend".equals(service.name()) && backend != null) {
                svcEnv = new java.util.LinkedHashMap<>(deployEnv);
                svcEnv.put("BACKEND_PORT", String.valueOf(backend.containerPort()));
            }
            DeployResult result = dockerDeployer.deployService(runDir, state.projectId, state.env,
                    state.runId, state.gitSha, namespace, service.name(), service.image(),
                    hostPort, service.containerPort(), net, svcEnv, service.volumePath());
            state.log.append('[').append(stageId).append("] ").append(service.name())
                    .append(" → ").append(result.endpoint()).append('\n')
                    .append(result.log()).append('\n');
            if (!result.success()) {
                state.stages.add(StageResult.failed(stageId, 1, result.errorCode(),
                        service.name() + " 启动失败：" + result.summary(), elapsed(start)));
                return false;
            }
            ArtifactRef artifact = new ArtifactRef("deployment", result.endpoint(), null, 0,
                    Map.of("target", "docker",
                            "service", service.name(),
                            "resource", result.resource() == null ? "" : result.resource()));
            artifacts.add(artifact);
            state.artifacts.add(artifact);
            if ("frontend".equals(service.name()) || primaryEndpoint == null) {
                primaryEndpoint = result.endpoint();
                primaryPort = hostPort;
                primaryResource = result.resource();
            }
        }
        state.deployTargetUsed = "docker";
        state.deployHostPort = primaryPort;
        state.log.append("[deploy] 对外端点 ").append(primaryEndpoint)
                .append("（容器 ").append(primaryResource).append("）\n");
        state.stages.add(StageResult.success(stageId, 0, artifacts, elapsed(start)));
        return true;
    }

    /**
     * 读取本次部署要注入的环境变量。
     *
     * <p>读不到（DB 损坏/主密钥不匹配）时**不吞异常**：带着错误配置部署比失败更危险，
     * 由 stage 的异常处理给出 E-2xx。
     */
    private Map<String, String> loadDeployEnv(RunState state) {
        if (envVars == null) {
            return Map.of();
        }
        return envVars.resolve(state.projectId, state.env);
    }

    /**
     * 宿主端口分配：优先沿用租约；冲突则在期望端口之后找空闲端口。
     *
     * <p>SERVER 目标不在本机检查（远端由部署器报错），只沿用/记录租约。
     */
    private int allocateHostPort(RunState state, DeployTarget target, int desired, int containerPort) {
        if (envStore == null) {
            return desired;
        }
        PortLease lease = envStore.lease(state.projectId, state.env, containerPort).orElse(null);
        int candidate = lease == null ? desired : lease.hostPort();
        boolean free = target == DeployTarget.SERVER || portFree(candidate);
        if (!free) {
            int found = -1;
            for (int p = Math.max(1, desired) + 1; p <= desired + 200; p++) {
                if (portFree(p)) {
                    found = p;
                    break;
                }
            }
            if (found < 0) {
                throw new IllegalStateException("端口分配失败：从 " + desired
                        + " 起 200 个端口都不可用（宿主端口耗尽）");
            }
            state.log.append("[port] ").append(candidate).append(" 被占用 → 分配 ").append(found)
                    .append('\n');
            candidate = found;
        } else if (lease != null && lease.hostPort() != desired) {
            state.log.append("[port] 沿用租约端口 ").append(candidate)
                    .append("（容器端口 ").append(containerPort).append("）\n");
        }
        state.deployHostPort = candidate;
        envStore.saveLease(new PortLease(state.projectId, state.env, containerPort,
                candidate, state.runId, System.currentTimeMillis()));
        return candidate;
    }

    /** 本机端口分配：跳过被占用或已分配给其它组件的端口（前后端不能共用一个端口）。 */
    private int allocateDistinctHostPort(RunState state, int desired, int containerPort,
                                         int... reserved) {
        int port = allocateHostPort(state, DeployTarget.HOST, desired, containerPort);
        for (int i = 0; i <= 200; i++) {
            boolean clash = false;
            for (int r : reserved) {
                if (port == r) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                return port;
            }
            port = allocateHostPort(state, DeployTarget.HOST, port + 1, containerPort);
        }
        throw new IllegalStateException("端口分配失败：从 " + desired + " 起找不到可用端口");
    }

    private static boolean portFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 从部署 endpoint 取出实际端口（http://host:port/path）；解析失败返回 -1。 */
    private static int portOf(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return -1;
        }
        try {
            int port = URI.create(endpoint).getPort();
            return port > 0 ? port : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 把 health URL 的端口换成实际分配端口（pipeline 里写的是期望端口）。 */
    private static String withPort(String url, int port) {
        try {
            URI uri = URI.create(url);
            if (uri.getPort() == port) {
                return url;
            }
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), port,
                    uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
        } catch (Exception e) {
            return url;
        }
    }

    /** 部署声明里的必填变量与已配置 env 求差：返回缺失（或值为空）的变量名。 */
    private static List<String> missingRequiredEnv(PipelineDefinition.DeploySpec spec,
                                                   Map<String, String> deployEnv) {
        if (spec.envRequired() == null || spec.envRequired().isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String key : spec.envRequired()) {
            String value = deployEnv.get(key);
            if (value == null || value.isBlank()) {
                missing.add(key);
            }
        }
        return missing;
    }

    private boolean health(RunState state, PipelineDefinition.StageDef stage, long start) {
        String url = stage.url();
        if (url == null || url.isBlank()) {
            state.stages.add(StageResult.failed(stage.id(), 1, "E-201", "health 缺少 url", elapsed(start)));
            return false;
        }
        if (state.deployHostPort > 0) {
            String actual = withPort(url, state.deployHostPort);
            if (!actual.equals(url)) {
                state.log.append("[health] 端口租约 ").append(state.deployHostPort)
                        .append("，健康检查 URL ").append(url).append(" → ").append(actual).append('\n');
                url = actual;
            }
        }
        String resource = deployedResource(state);
        boolean dockerTarget = "docker".equals(state.deployTargetUsed);
        // docker 容器若自带 HEALTHCHECK（前端 + 后端接口 + actuator 的组合探活），
        // 只认 HTTP 200 会在 nginx 静态页先起来时假通过（后端还在连库/迁移）。
        // 因此 docker 目标要等到容器 healthy 才算通过；等待窗口放宽到约 3 分钟（应用启动 + Flyway）。
        int attempts = dockerTarget && resource != null ? 90 : 20;
        long sleepMs = dockerTarget && resource != null ? 2000L : 300L;
        boolean waitingHealth = false;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        String body = "";
        String exitInfo = null;
        for (int i = 0; i < attempts; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                body = response.body();
                if (response.statusCode() == stage.expected() && containerReady(state, resource, dockerTarget)) {
                    state.log.append("[health] ").append(response.statusCode()).append(' ')
                            .append(body.length() > 500 ? body.substring(0, 500) + "…" : body).append('\n');
                    state.stages.add(StageResult.success(stage.id(), 0, List.of(), elapsed(start)));
                    return true;
                }
                if (response.statusCode() == stage.expected() && !waitingHealth) {
                    waitingHealth = true;
                    state.log.append("[health] HTTP 已 ").append(response.statusCode())
                            .append("，继续等待容器 HEALTHCHECK 通过（避免静态页假通过）\n");
                }
            } catch (Exception ignored) {
                // 服务尚未起来，继续重试
            }
            // docker 目标：容器已退出（如应用启动脚本校验配置失败自杀）就别再空等 6 秒，
            // 立即失败并把退出码带进摘要——真实原因由下一步的容器日志给出。
            if (dockerTarget && resource != null) {
                exitInfo = containerExitInfo(state, resource);
                if (exitInfo != null) {
                    state.log.append("[health] 容器 ").append(resource).append(' ')
                            .append(exitInfo).append("，提前终止等待\n");
                    break;
                }
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        String diag = healthDiagnostics(state, stage.id());
        removeFailedContainer(state, stage.id());
        String summary = "健康检查未通过：" + url
                + (exitInfo == null ? " body=" + brief(body) : "（容器 " + exitInfo + "）")
                + (diag == null ? "" : "；容器日志：" + diag);
        state.stages.add(StageResult.failed(stage.id(), 1, "E-401", summary, elapsed(start)));
        return false;
    }

    /**
     * 容器是否真正可用：docker 目标且声明了 HEALTHCHECK 时要求 healthy；
     * 未声明 HEALTHCHECK（none）或非 docker 目标则视为可用（沿用 HTTP 判定）。
     */
    private boolean containerReady(RunState state, String resource, boolean dockerTarget) {
        if (!dockerTarget || resource == null || resource.isBlank()) {
            return true;
        }
        ActionResult probe = host.run(Path.of("."), spec("health-probe", List.of(
                List.of("docker", "inspect", "-f",
                        "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}", resource))), () -> false);
        if (probe.result().status() != StageStatus.SUCCEEDED) {
            return true; // 探测失败不阻塞：HTTP 已通即认为服务可用
        }
        String status = probe.logTail() == null ? "" : probe.logTail().trim();
        return status.isEmpty() || "none".equals(status) || "healthy".equals(status);
    }

    /** docker inspect 容器状态：已退出（exited/dead）返回状态+退出码；运行中或探测失败返回 null。 */
    private String containerExitInfo(RunState state, String resource) {
        ActionResult probe = host.run(Path.of("."), spec("health-probe", List.of(
                List.of("docker", "inspect", "-f",
                        "{{.State.Status}} {{.State.ExitCode}}", resource))), () -> false);
        if (probe.result().status() != StageStatus.SUCCEEDED) {
            return null;
        }
        String out = probe.logTail() == null ? "" : probe.logTail().trim();
        return (out.startsWith("exited") || out.startsWith("dead")) ? out : null;
    }

    /** 摘要里的响应体：压成一行并截断，避免整页 HTML 淹没错误信息。 */
    private static String brief(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "…" : oneLine;
    }

    /** 本次运行部署出来的容器资源名（取最后一个 deployment 产物）。 */
    private String deployedResource(RunState state) {
        return state.artifacts.stream()
                .filter(a -> "deployment".equals(a.kind()))
                .reduce((first, second) -> second)
                .map(a -> a.meta().get("resource"))
                .filter(r -> r != null && !r.isBlank())
                .orElse(null);
    }

    /**
     * 健康检查失败后移除刚部署的容器：否则它会 `--restart unless-stopped` 无限重启，
     * 而这次运行已经被判失败，留着只会污染机器。日志已在上一步收集。
     */
    private void removeFailedContainer(RunState state, String stageId) {
        String resource = deployedResource(state);
        if (resource == null) {
            return;
        }
        host.run(Path.of("."), spec("health-cleanup", List.of(
                List.of("docker", "rm", "-f", resource))), () -> false);
        state.log.append('[').append(stageId).append("] 已移除健康检查失败的容器 ")
                .append(resource).append('\n');
    }

    /**
     * 把容器最近日志带进 summary / 运行日志。
     *
     * <p>之前的失败只显示 `body=`，用户根本不知道是端口不对、容器起不来还是应用配置缺失。
     *
     * @return 最后一条非空日志（用于摘要）；无容器或无日志时返回 null
     */
    private String healthDiagnostics(RunState state, String stageId) {
        String resource = deployedResource(state);
        if (resource == null) {
            return null;
        }
        ActionResult logs = host.run(Path.of("."), spec("health-diag", List.of(
                List.of("docker", "logs", "--tail", "20", resource))), () -> false);
        String tail = logs.logTail() == null ? "" : logs.logTail().trim();
        if (tail.isEmpty()) {
            return null;
        }
        state.log.append('[').append(stageId).append("] 容器最近日志：\n").append(tail).append('\n');
        // 摘要优先取应用自己打的 ERROR 行（那才是根因，如"未设置 DB_HOST"）；
        // 末行经常是启动横幅或收尾空行，单独拿末行会把真实原因淹没。
        List<String> errors = new ArrayList<>();
        String lastNonEmpty = null;
        for (String raw : tail.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            lastNonEmpty = line;
            if (line.contains("ERROR")) {
                errors.add(line);
            }
        }
        String pick;
        if (!errors.isEmpty()) {
            pick = String.join("；", errors.subList(Math.max(0, errors.size() - 3), errors.size()));
        } else {
            pick = lastNonEmpty == null ? "" : lastNonEmpty;
        }
        if (pick.isEmpty()) {
            return null;
        }
        return pick.length() > 300 ? pick.substring(0, 300) + "…" : pick;
    }

    private void runOnFailure(RunState state, PipelineDefinition definition,
                              Path runDir, Map<String, String> vars) {
        for (PipelineDefinition.StageDef stage : definition.onFailure()) {
            if ("rollback".equals(stage.type())) {
                // 平台回滚：用 lastGood 重新部署上一版，而不是执行仓库脚本
                deploy(state, runDir, System.nanoTime(), stage.id(), true);
                continue;
            }
            ActionId action = ActionId.fromWire(stage.action()).orElse(null);
            if (action == null) {
                continue;
            }
            Map<String, String> merged = new java.util.HashMap<>(vars);
            String good = lastGood.get(state.projectId);
            if (good != null) {
                merged.put("tag", good);
            }
            Optional<ActionSpec> spec = recipes.resolve(state.recipeRoot, action);
            if (spec.isEmpty()) {
                continue;
            }
            ActionResult result = host.run(runDir, substitute(spec.get(), Map.copyOf(merged)),
                    () -> false);
            state.log.append("[on-failure:").append(stage.id()).append("] ")
                    .append(result.logTail()).append('\n');
            state.stages.add(rename(result.result(), "on-failure:" + stage.id()));
        }
    }

    private String imageDigest(Path runDir, String tag) {
        ActionResult r = host.run(runDir, spec("image-digest", List.of(
                List.of("docker", "image", "inspect", "--format", "{{.Id}}", tag))), () -> false);
        if (r.result().status() != StageStatus.SUCCEEDED) {
            return null;
        }
        String line = r.logTail().trim();
        return line.contains(":") ? line.substring(line.lastIndexOf(':') + 1) : line;
    }

    /** 镜像大小（字节）；取不到时返回 0，不影响构建结论。 */
    private long imageSize(Path runDir, String tag) {
        ActionResult r = host.run(runDir, spec("image-size", List.of(
                List.of("docker", "image", "inspect", "--format", "{{.Size}}", tag))), () -> false);
        if (r.result().status() != StageStatus.SUCCEEDED) {
            return 0L;
        }
        try {
            return Long.parseLong(r.logTail().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private ActionSpec substitute(ActionSpec spec, Map<String, String> vars) {
        List<List<String>> steps = spec.steps().stream()
                .map(argv -> argv.stream()
                        .map(token -> {
                            String out = token;
                            for (Map.Entry<String, String> e : vars.entrySet()) {
                                out = out.replace("${" + e.getKey() + "}", e.getValue());
                            }
                            return out;
                        }).toList())
                .toList();
        return new ActionSpec(spec.action(), steps, spec.produce(), spec.timeout(), spec.source());
    }

    private ActionSpec spec(String id, List<List<String>> steps) {
        return new ActionSpec(ActionId.BACKEND_BUILD, steps, List.of(), STAGE_TIMEOUT,
                ActionSource.builtin("engine#" + id));
    }

    private StageResult rename(StageResult result, String stageId) {
        return new StageResult(stageId, result.status(), result.exitCode(), result.errorCode(),
                result.errorSummary(), result.artifacts(), result.durationMs(), result.retryCount());
    }

    /** 路径给 git / docker 用时统一成 POSIX 风格（Windows 下 E:\x → E:/x）。 */
    private String posix(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /** 环境变量快照摘要：对 key + 值哈希做 SHA-256，可用于比对两次运行是否用了同一套变量。 */
    private static String envDigest(Map<String, String> values) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        String vh = sha256(e.getValue());
                        md.update((e.getKey() + "=" + vh + "\n")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    });
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("环境变量快照摘要失败", e);
        }
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    md.digest(String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 把动作命令的实时输出按行写入运行日志。
     *
     * <p>命令输出按块到达，这里按换行切分、给每行补 {@code [stage] } 前缀，
     * 避免块边界把前缀插到行中间；同时限制单阶段实时日志上限，防止大构建占满内存。
     * 超过上限后不再转发（仍会保留动作尾部摘要），且标记为截断供引擎补日志尾部。
     */
    private static final class StageLog implements Consumer<String> {

        private static final int MAX_LIVE_CHARS = 200_000;

        private final RunState state;
        private final String prefix;
        private final StringBuilder pending = new StringBuilder();
        private int written;
        private boolean truncated;

        StageLog(RunState state, String stageId) {
            this.state = state;
            this.prefix = "[" + stageId + "] ";
        }

        @Override
        public void accept(String chunk) {
            synchronized (pending) {
                pending.append(chunk);
                int start = 0;
                int idx;
                while ((idx = pending.indexOf("\n", start)) >= 0) {
                    write(pending.substring(start, idx + 1));
                    start = idx + 1;
                }
                if (start > 0) {
                    pending.delete(0, start);
                }
            }
        }

        /** 收尾：最后一行可能没有换行符。 */
        void flush() {
            synchronized (pending) {
                if (pending.length() > 0) {
                    write(pending.toString());
                    pending.setLength(0);
                }
            }
        }

        boolean streamed() {
            return written > 0;
        }

        boolean truncated() {
            return truncated;
        }

        private void write(String line) {
            int room = MAX_LIVE_CHARS - written;
            if (room <= 0) {
                truncated = true;
                return;
            }
            String text = line.length() <= room ? line : line.substring(0, room);
            if (text.length() < line.length()) {
                truncated = true;
            }
            synchronized (state.log) {
                state.log.append(prefix).append(text);
            }
            written += text.length();
        }
    }

    /** 一次运行的状态（构建历史）。 */
    private static final class RunState {
        final String runId;
        final String projectId;
        /** 目标环境（dev / test / prod）：决定用哪份固化脚本、要不要校验测试机成功案例。 */
        final String env;
        final String pipelineId;
        final String gitSha;
        /** 触发时间（epoch millis），构建历史按它倒序展示。 */
        final long createdAt = System.currentTimeMillis();
        final List<StageResult> stages = new ArrayList<>();
        final List<ArtifactRef> artifacts = new ArrayList<>();
        final StringBuilder log = new StringBuilder();
        volatile StageStatus status = StageStatus.PENDING;
        /** 构建序号（项目 + 环境内从 1 开始）：历史顺序与镜像版本 vN。 */
        volatile int seq;
        volatile boolean cancelled = false;
        /** 本次运行的部署目标覆盖（触发参数）；null = 用 pipeline.yml 的 deploy.target。 */
        volatile String deployTargetOverride;
        /** 本次运行已生效的部署目标（回滚复用）。 */
        volatile String deployTargetUsed;
        /** 检查构建是否保留镜像打包（触发参数 verifyImage，默认 false：最贵的一步且与"能不能编过"无关）。 */
        volatile boolean verifyImage;
        /** 端口租约分配出的宿主端口（0 = 尚未分配，health 用 pipeline 里的端口）。 */
        volatile int deployHostPort;
        /** 本次运行加载的流水线定义（deploy 段从这里读）。 */
        volatile PipelineDefinition definition;
        /** 定义所在的根目录（仓库检出目录 or 平台定义目录）：配方从这里解析。 */
        volatile Path recipeRoot;
        /** 定义来源（决定要不要做"部署配置受审"校验）。 */
        volatile PipelineDefinitionResolver.Source definitionSource;
        /** 定义来源的一句话说明（例如"用测试环境固化的脚本"）。 */
        volatile String definitionNote;
        /** 触发者：ui:<userId> / agent（审计用）。 */
        volatile String actor;
        /** Agent 触发时携带的环境绑定（人工确认后的映射）。 */
        volatile String bindingId;
        /** 环境绑定的人工确认人。 */
        volatile String confirmedBy;
        /** 失败运行保留的构建现场（配合「从失败阶段继续」）；清理后为 null。 */
        volatile Path keptRunDir;
        volatile Path keptBare;
        /** 每个阶段开始前的产物数量：重试时回收该阶段及之后产生的产物，避免重复。 */
        final Map<String, Integer> artifactMark = new java.util.concurrent.ConcurrentHashMap<>();

        RunSnapshot snapshot() {
            return new RunHistoryStore.RunSnapshot(runId, projectId, env, pipelineId, gitSha,
                    status, createdAt, seq, List.copyOf(stages), List.copyOf(artifacts),
                    ProcessExecutor.redact(log.toString()),
                    definitionSource == null ? null : definitionSource.name(), definitionNote);
        }

        RunState(String runId, String projectId, String env, String pipelineId, String gitSha) {
            this.runId = runId;
            this.projectId = projectId;
            this.env = env;
            this.pipelineId = pipelineId;
            this.gitSha = gitSha;
        }
    }
}
