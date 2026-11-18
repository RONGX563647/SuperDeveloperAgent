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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.artifact.ArtifactRef;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.error.BuildException;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.PromotionException;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvNames;
import top.rongx.agent.domain.env.EnvResource;
import top.rongx.agent.domain.env.EnvVariable;
import top.rongx.agent.domain.env.PortLease;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.PipelineEngine.ImageVersion;
import top.rongx.agent.domain.port.PipelineEngine.LogSlice;
import top.rongx.agent.domain.port.PipelineEngine.StagePreview;
import top.rongx.agent.domain.release.ReleaseVersion;
import top.rongx.agent.domain.process.BoundedCommand;
import top.rongx.agent.domain.run.StageResult;
import top.rongx.agent.domain.run.StageStatus;
import top.rongx.agent.executor.RunHistoryStore.RunSnapshot;
import top.rongx.agent.domain.error.PlatformException;

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
    /** 运行看护：扫描周期 / 落盘间隔 / 无输出心跳 / 强制终态前的宽限。 */
    private static final long WATCHDOG_TICK_MS = 5_000L;
    private static final long HEARTBEAT_SAVE_MS = 10_000L;
    private static final long HEARTBEAT_LOG_MS = 120_000L;
    private static final long WATCHDOG_GRACE_MS = 5 * 60_000L;
    private static final long FORCE_TERMINATE_AFTER_MS = 60_000L;
    /** 单次健康探测（HTTP / docker inspect）的超时：窗口收敛计算也要把它算进去。 */
    private static final long HEALTH_PROBE_TIMEOUT_MS = 2_000L;
    /** 健康检查里的 docker 探测（inspect/logs/rm）预算：不能让守护进程挂起吃掉整个阶段。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    /** 失败清理（docker rm -f / docker logs）预算：收尾必须收敛，否则失败运行也拖满阶段预算。 */
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);
    /** 健康检查等待窗口下限：再紧也要给应用一次启动探测的机会。 */
    private static final long MIN_HEALTH_WINDOW_MS = 30_000L;
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

    private static final Logger log = LoggerFactory.getLogger(BuiltInPipelineEngine.class);

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    /** 镜像版本账本（按 (项目, 环境) 持久化；回滚锚点与按版本部署都以它为准）。 */
    private final ImageVersionStore imageVersions;

    /** 有界队列：全局并发上限 4；同一项目同一环境串行由 TriggerScheduler 保证。 */
    private final ExecutorService workers = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory());
    /**
     * 运行看护：单线程周期扫描所有 RUNNING 的运行，负责
     * ① 运行中定期落盘 ② 长时间无输出时写心跳 ③ 超预算请求取消 ④ 仍不收敛时强制终态。
     * 任何未覆盖的阻塞点（外部 CLI、文件 IO、模型诊断）都不会再留下永久 RUNNING 的幽灵任务。
     */
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final TriggerScheduler scheduler = new TriggerScheduler();
    /** 构建历史持久化（重启可恢复）。 */
    private final RunHistoryStore history;
    /** 发布镜像 tag 前缀（可配置；避免测试字样进入正式发布链路）。 */
    private final String imagePrefix;
    /** 每个 (项目, 环境) 保留的镜像版本数（含当前版本）。 */
    private static final int KEEP_IMAGES = 5;
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
    /**
     * 自动修复策略：默认开启「确定性修复 → 平台复核 → 自动续跑」，发布 Agent 作为兜底。
     * 用装配点注入（不写死环境/镜像/路径），测试也可换成自定义修复链。
     */
    private volatile AutoRepairPolicy autoRepairPolicy = AutoRepairPolicy.defaults();
    private volatile List<FailureRepair> autoRepairs = List.of();
    /** 多服务部署（test 前后端分离）直接使用 docker 实现。 */
    private volatile DockerDeployer dockerDeployer = new DockerDeployer();
    /** Docker CLI 可执行文件（可指向 podman 等兼容实现）。 */
    private volatile DockerCli dockerCli = DockerCli.defaults();

    /**
     * 统一运行账本（与对话/工作区/修复共用一张表）：让流水线运行也进入统一视图与启动恢复。
     * 为 null 时不记账；写入失败只记日志，绝不影响流水线本身。
     */
    private final top.rongx.agent.domain.run.RunLedgerRepository ledger;

    /** 应用关闭时停掉执行线程：不再接新任务，正在跑的构建被中断（避免悬挂进程）。 */
    @Override
    public void close() {
        watchdog.shutdownNow();
        // 优雅关闭：终态对外可见后，doExecute 的 finally 还要清理 worktree 并再次落盘。
        // 直接 shutdownNow 会打断这段收尾，在 Windows 上留下删不掉的工作树与半截历史。
        workers.shutdown();
        try {
            if (!workers.awaitTermination(3, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
        this(recipes, sandboxed, host, workspaceRoot, history, imagePrefix, repoLocator, deployers,
                resolver, recipeStore, envVars, envStore, envRuntime, envProvisioner,
                recoveryAdvisor, localDevHost, null);
    }

    /**
     * 最完整构造：再注入统一运行账本（跨编排运行视图 + 重启恢复）。
     *
     * <p>账本为空时行为不变；账本写入在 {@code finally} 中兜底，失败只记日志。
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
                                 LocalDevHostDeployer localDevHost,
                                 top.rongx.agent.domain.run.RunLedgerRepository ledger) {
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
        this.ledger = ledger;
        this.imageVersions = workspaceRoot == null
                ? ImageVersionStore.disabled() : ImageVersionStore.file(workspaceRoot);
        restoreHistory();
        watchdog.scheduleAtFixedRate(this::watchdogTick, WATCHDOG_TICK_MS, WATCHDOG_TICK_MS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * 装配自动修复策略与修复链（同包调用：Spring 配置与单测）。
     * {@code repairs} 为空时按策略构造默认链：镜像自愈优先，发布 Agent 兜底。
     */
    BuiltInPipelineEngine withAutoRepair(AutoRepairPolicy policy, List<FailureRepair> repairs) {
        this.autoRepairPolicy = policy == null ? AutoRepairPolicy.disabled() : policy;
        this.autoRepairs = repairs == null ? List.of() : List.copyOf(repairs);
        return this;
    }

    /** 指定 Docker CLI 二进制（Spring 装配注入；默认 docker）。 */
    public BuiltInPipelineEngine withDockerCli(DockerCli cli) {
        if (cli != null) {
            this.dockerCli = cli;
            this.dockerDeployer = new DockerDeployer(cli);
        }
        return this;
    }

    private List<FailureRepair> repairChain() {
        if (!autoRepairs.isEmpty()) {
            return autoRepairs;
        }
        List<FailureRepair> chain = new ArrayList<>();
        // Docker 守护进程没起会被误诊成缺镜像：先处理这个环境类根因，再谈 pull
        chain.add(new DockerDaemonRepair());
        chain.add(new ImagePullRepair());
        chain.add(new AgentDiagnosisRepair(autoRepairPolicy.agentFallback()));
        return chain;
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
    private static final String E_NO_PIPELINE = ErrorCode.PIPELINE_DEFINITION_UNAVAILABLE.code();
    /** 定义存在但解析不出来（人工写坏了平台定义或仓库覆盖文件）。 */
    private static final String E_BAD_PIPELINE = ErrorCode.PIPELINE_DEFINITION_INVALID.code();

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
     * 当前生效的发布摘要：与 {@link #pipelines(String)} 用同一份定义解析，
     * 保证设置页展示的端口/健康路径就是执行时会用的值。
     */
    @Override
    public Optional<DeploySummary> deploySummary(String projectId) {
        Path repoDir = resolveRepoForRead(projectId);
        try {
            PipelineDefinition def;
            if (resolver != null) {
                def = resolver.resolveForRead(projectId, repoDir).definition();
            } else if (repoDir != null && Files.isRegularFile(repoDir.resolve(PIPELINE_FILE))) {
                def = PipelineDefinition.load(repoDir);
            } else {
                return Optional.empty();
            }
            if (def.deploy() == null) {
                return Optional.empty();
            }
            PipelineDefinition.DeploySpec deploy = def.deploy();
            return Optional.of(new DeploySummary(deploy.target(), deploy.port(), deploy.healthPath()));
        } catch (RuntimeException e) {
            return Optional.empty();
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

    @Override
    public DryRunReport dryRun(String projectId, String pipelineId, String env, String gitSha) {
        String normalized = normalizeEnv(env == null || env.isBlank() ? "test" : env);
        List<StagePreview> previews = preview(projectId, pipelineId, normalized, gitSha);
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<StageDryRun> stages = new ArrayList<>();

        top.rongx.agent.domain.env.EffectiveEnvironment effective =
                envStore.effective(projectId, normalized);
        if (!effective.missingSecrets().isEmpty()) {
            blockers.add(ErrorCode.SECRET_UNRESOLVED.code() + " 密钥引用无法解析：" + String.join("、", effective.missingSecrets()));
        }
        Path repo = resolveRepoForRead(projectId);
        if (repo != null) {
            top.rongx.agent.domain.env.ProjectEnvFiles.Declared declared =
                    top.rongx.agent.domain.env.ProjectEnvFiles.scan(repo);
            List<String> missing = declared.required().stream()
                    .filter(k -> !effective.values().containsKey(k))
                    .toList();
            if (!missing.isEmpty()) {
                blockers.add(ErrorCode.ENV_REQUIRED_MISSING.code() + " 缺少必需环境变量：" + String.join("、", missing));
            }
        }
        if (previews.isEmpty()) {
            blockers.add("没有可执行的阶段：请检查 ci/pipeline.yml 或配方是否存在");
        }
        boolean needsDocker = previews.stream().anyMatch(p ->
                "image".equalsIgnoreCase(p.type()) || "docker".equalsIgnoreCase(p.runner()));
        if (needsDocker && !dockerAvailable()) {
            blockers.add("Docker 不可用：镜像/容器部署无法执行（L3 fail-closed）");
        }
        // P0-3：预检全绿不代表发布能成功。
        // 有镜像阶段但提交里没有 Dockerfile：image_package 必然 E-101/E-501，而且自动修复
        // 也救不了（生成 Dockerfile 是待确认补丁，确认后还得用新提交重跑），必须在预检挡住。
        String missingDockerfile = missingImageDockerfile(repo, previews);
        if (missingDockerfile != null) {
            blockers.add(ErrorCode.DEPLOY_PREREQUISITE_MISSING.code()
                    + " 发布将失败：镜像阶段需要 " + missingDockerfile
                    + "，但本次提交里没有（先生成 Dockerfile，用新提交再发布）");
        }
        // DOCKER 目标却没有镜像阶段时，发布必然 E-501。
        String deployTarget = previews.stream().map(StagePreview::deployTarget)
                .filter(t -> t != null && !t.isBlank()).findFirst().orElse(null);
        boolean hasImageStage = previews.stream().anyMatch(BuiltInPipelineEngine::isImageStage);
        if ("docker".equalsIgnoreCase(deployTarget) && !hasImageStage) {
            boolean hasDockerfile = repo != null
                    && (Files.isRegularFile(repo.resolve("Dockerfile"))
                    || Files.isRegularFile(repo.resolve("backend").resolve("Dockerfile"))
                    || Files.isRegularFile(repo.resolve("frontend").resolve("Dockerfile")));
            if (!hasDockerfile) {
                blockers.add(ErrorCode.DEPLOY_PREREQUISITE_MISSING.code() + " 发布将失败：DOCKER 目标但仓库没有 Dockerfile，镜像阶段无法生成");
            } else if ("verify".equals(pipelineId)) {
                warnings.add("检查构建会跳过镜像打包；发布前需运行 release 流水线，否则可能 "
                        + ErrorCode.DEPLOY_PREREQUISITE_MISSING.code());
            } else {
                blockers.add(ErrorCode.DEPLOY_PREREQUISITE_MISSING.code() + " 发布将失败：DOCKER 目标缺少 image_package 阶段（构建镜像）");
            }
        }
        for (StagePreview p : previews) {
            boolean runnable = p.skipReason() == null || p.skipReason().isBlank();
            stages.add(new StageDryRun(p.id(), p.type(), p.action(), runnable,
                    runnable ? "" : p.skipReason(), p.command() == null ? "" : p.command(),
                    p.scriptSha() == null ? "" : p.scriptSha(), p.builtin()));
        }
        String envDigest = envDigest(effective.digestValues());
        String fingerprint = String.join("|", projectId, normalized, pipelineId,
                gitSha == null ? "" : gitSha, envDigest,
                previews.stream().map(p -> p.scriptSha() == null ? "" : p.scriptSha())
                        .reduce("", (a, b) -> a + "," + b));
        String reportHash = sha256Short(fingerprint);
        boolean ok = blockers.isEmpty();
        return new DryRunReport(projectId, normalized, pipelineId,
                gitSha == null ? "" : gitSha, reportHash, fingerprint, ok,
                blockers, warnings, stages, System.currentTimeMillis());
    }

    private volatile long dockerProbeAt;
    private volatile boolean dockerProbeOk;

    /** 该阶段是否在打包镜像（动作或阶段类型）。 */
    static boolean isImageStage(StagePreview preview) {
        return "image_package".equals(preview.action()) || "image".equalsIgnoreCase(preview.type());
    }

    /**
     * 镜像阶段要用、但本次提交里不存在的 Dockerfile；没有缺失时返回 null。
     *
     * <p>路径从镜像阶段的命令里取 {@code -f/--file}（缺省 {@code Dockerfile}）：
     * 不能一律按根目录 {@code Dockerfile} 判断，否则自定义配方会被误判成阻塞。
     */
    static String missingImageDockerfile(Path repo, List<StagePreview> previews) {
        if (repo == null) {
            return null;
        }
        for (StagePreview preview : previews) {
            if (!isImageStage(preview)) {
                continue;
            }
            String dockerfile = dockerfileOf(preview.command());
            return Files.isRegularFile(repo.resolve(dockerfile)) ? null : dockerfile;
        }
        return null;
    }

    /** 从 {@code docker build -f <path> ...} 里取 Dockerfile 路径，缺省 Dockerfile。 */
    private static String dockerfileOf(String command) {
        if (command == null || command.isBlank()) {
            return "Dockerfile";
        }
        String[] argv = command.trim().split("\\s+");
        for (int i = 0; i < argv.length - 1; i++) {
            if ("-f".equals(argv[i]) || "--file".equals(argv[i])) {
                return argv[i + 1];
            }
        }
        return "Dockerfile";
    }

    /** 本机 Docker 可用性（10 秒缓存）：dry-run 门控与界面下拉共用，避免每次刷新都阻塞在 CLI 上。 */
    @Override
    public boolean dockerAvailable() {
        long now = System.currentTimeMillis();
        if (now - dockerProbeAt < 10_000) {
            return dockerProbeOk;
        }
        // 有界探测：原先 readAllBytes() 在 waitFor 之前，docker CLI 不退出时 3s 超时形同虚设，
        // 会让 dry-run 门控与界面下拉永久卡住。走共用原语：并行读流 + 硬超时 + 明确结果。
        dockerProbeOk = BoundedCommand.run(
                dockerCli.cmd("version", "--format", "{{.Server.Version}}"), 3_000L).ok();
        dockerProbeAt = now;
        return dockerProbeOk;
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
        // waitFor 之后再读流同样不安全：输出超过管道缓冲时子进程会阻塞在写，
        // 永远等不到退出。统一用有界原语并行排空输出。
        BoundedCommand.Outcome outcome = BoundedCommand.run(
                List.of("git", "-C", repoDir.toString(), "show", gitSha + ":" + relPath),
                repoDir, 10_000L);
        return outcome.ok() ? outcome.output() : null;
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
                    // deploy 阶段把定义里的默认目标带给前端：否则 UI 只能按环境猜
                    // （test 一律猜 docker），无 Dockerfile 的项目会白跑完整构建再失败。
                    return new StagePreview(s.id(), s.type(), s.action(), s.runner(), s.workdir(),
                            kept.contains(s.id()) ? null : verifySkipReason(s),
                            cmd.shell(), cmd.command(), cmd.script(), cmd.sha(), cmd.builtin(),
                            "deploy".equals(s.type()) && definition.deploy() != null
                                    ? definition.deploy().target() : null);
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
            } catch (PlatformException | IllegalArgumentException e) {
                return badDefinition(state, e);
            }
        }
        try {
            Path repoRoot = repoLocator == null ? null : repoLocator.repoDir(state.projectId).orElse(null);
            return resolver.resolveForRun(state.projectId, runDir, repoRoot, state.env);
        } catch (PlatformException | IllegalArgumentException e) {
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
        // verify 永远不是"环境跑通"：即使定义被改坏带进了 deploy，也不能当成晋升证据
        if ("verify".equals(state.pipelineId)) {
            return false;
        }
        return state.definition.stages().stream()
                .anyMatch(s -> "deploy".equals(s.type()) || "stack_deploy".equals(s.action()));
    }

    /**
     * 运行模式裁剪的唯一入口：触发与自动续跑共用。
     *
     * <p>此前自动续跑直接使用原始定义，verify 运行会退化成完整 release：
     * 真的起进程、固化环境脚本，只读的检查构建因此污染环境并伪造晋升证据。
     *
     * @param pipelineId  {@code verify} = 只构建，不部署
     * @param deployImage 非空 = 按版本部署（复用镜像，只跑 deploy/health）
     * @param verifyImage verify 时是否保留镜像打包
     * @param target      有效部署目标（触发参数覆盖后的值，可为空；预留给后续按目标裁剪）
     */
    static PipelineDefinition planFor(String pipelineId, String deployImage, boolean verifyImage,
                                      String target, PipelineDefinition definition) {
        if (definition == null) {
            return null;
        }
        if (deployImage != null && !deployImage.isBlank()) {
            return definition.forDeployOnly();
        }
        if ("verify".equals(pipelineId)) {
            return definition.forVerify(verifyImage);
        }
        return definition;
    }

    /** 应用运行模式裁剪（verify / 按版本部署）。 */
    private PipelineDefinition applyRunMode(RunState state, PipelineDefinition definition) {
        return planFor(state.pipelineId, state.deployImage, state.verifyImage,
                effectiveDeployTarget(state, definition), definition);
    }

    /** 有效部署目标：触发参数覆盖优先，否则取定义里的 deploy.target。 */
    private static String effectiveDeployTarget(RunState state, PipelineDefinition definition) {
        if (state.deployTargetOverride != null && !state.deployTargetOverride.isBlank()) {
            return state.deployTargetOverride;
        }
        return definition.deploy() == null ? null : definition.deploy().target();
    }

    private PipelineDefinitionResolver.Resolved badDefinition(RunState state, RuntimeException e) {
        String message = "ci/pipeline.yml 有语法/结构错误，无法执行：" + e.getMessage()
                + "（stage 只能引用白名单动作，见《流水线执行器契约》）。";
        state.log.append('[').append(E_BAD_PIPELINE).append("] ").append(message).append('\n');
        state.stages.add(StageResult.failed("pipeline-def", 1, E_BAD_PIPELINE, message, 0));
        state.status = StageStatus.FAILED;
        return null;
    }

    @Override
    // @handbook executor-pipeline-engine/trigger | 流水线触发与异步执行入口
    public String trigger(String projectId, String pipelineId, Map<String, String> params) {
        List<String> available = pipelines(projectId);
        if (!available.contains(pipelineId)) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "流水线不在白名单：" + pipelineId
                    + "（可用：" + String.join(", ", available) + "）");
        }
        String sha = params.get("gitSha");
        if (sha == null || !sha.matches("[0-9a-f]{7,40}")) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "gitSha 非法（需 7-40 位小写十六进制）");
        }
        String env = normalizeEnv(params.get("env"));
        // 带 dryRunHash 的触发必须自证"预检仍然有效"：哈希由配方/提交/环境/密钥版本共同决定。
        // 控制器与 Agent 工具都会先 dry-run 再传哈希；这里再算一次，防止"预检后配方被改"。
        String requestedHash = params.get("dryRunHash");
        if (requestedHash != null && !requestedHash.isBlank()) {
            PipelineEngine.DryRunReport report = dryRun(projectId, pipelineId, env, sha);
            if (!report.ok()) {
                throw new EnvironmentException(ErrorCode.DRY_RUN_FAILED,
                        "dry-run 未通过：" + String.join("；", report.blockers()));
            }
            if (!requestedHash.equals(report.reportHash())) {
                throw new EnvironmentException(ErrorCode.DRY_RUN_STALE,
                        "dry-run 报告已过期（代码/配方/环境/密钥版本已变化），请重新 dry-run");
            }
        }
        String deployTarget = params.get("deployTarget");
        // 服务器操作的前置：必须已有"测试机成功案例"（测试环境跑通并固化的脚本 + 证据运行成功）
        String problem = promotionProblem(projectId, env, sha);
        if (problem != null) {
            throw new PromotionException(ErrorCode.ENV_NOT_PROMOTED, problem);
        }
        // 不再校验"该提交里有没有 ci/pipeline.yml"：定义可以放在平台侧（默认），
        // 仓库里那份只是可选覆盖。真正缺定义时由执行期的 pipeline-def stage 说明。
        // 前置：仓库必须可定位，否则建出来的运行只会在历史里留下一条失败记录
        if (resolveRepoForRead(projectId) == null) {
            throw new BuildException(ErrorCode.COMPILE_FAILED,
                    "项目仓库不可用：请先在「项目接入」页完成 clone / 本地登记，"
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
        String deployImage = params.get("deployImage");
        state.deployImage = deployImage == null || deployImage.isBlank() ? null : deployImage.trim();
        // 版本号 + 是否打 tag：打 tag = 新版本独立部署（不覆盖旧版本）；
        // 不打 tag = 覆盖该环境默认部署槽（旧行为），版本号仅作为展示标签。
        state.deployVersion = normalizeDeployVersion(params.get("version"));
        state.deployTagged = "true".equalsIgnoreCase(String.valueOf(params.get("tag")))
                || "1".equals(String.valueOf(params.get("tag")));
        state.branch = normalizeBranchName(params.get("branch"));
        if (state.deployTagged && state.deployVersion == null) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT,
                    "打 tag 时必须填写版本号（例如 0.1.0）");
        }
        if (state.deployVersion != null) {
            ReleaseVersion version = ReleaseVersion.parse(state.deployVersion);
            if (state.deployTagged) {
                requireVersionBranch(state, version, sha);
            } else {
                state.log.append("[deploy] 快照构建 ").append(version.snapshotLabel())
                        .append("：不打 tag，仅在该环境独立部署\n");
            }
        }
        if (state.deployTagged) {
            state.log.append("[deploy] 版本 ").append(state.deployVersion)
                    .append("：打 tag 独立部署，旧版本不会被覆盖\n");
        } else if (state.deployVersion != null) {
            state.log.append("[deploy] 版本 ").append(state.deployVersion)
                    .append("：未打 tag，将覆盖该环境的默认部署\n");
        }
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
        recordLedger(state);
        scheduler.submit(projectId, env, () -> workers.submit(() -> execute(state)));
        return runId;
    }

    /** 环境名规范化：只认 dev/test/prod；缺省按 test（绝不让"没传环境"落到生产），未知值 fail-fast。 */
    private String normalizeEnv(String env) {
        if (env == null || env.isBlank()) {
            return "test";
        }
        String normalized = env.trim().toLowerCase();
        if (!EnvNames.ALL.contains(normalized)) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "未知环境：" + env + "（可选 dev / test / prod；不会自动回落到 test）");
        }
        return normalized;
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

    /** 运行中阶段进度：供工作台渲染「当前阶段 / 总阶段」与运行中占位。 */
    @Override
    public PipelineEngine.RunProgress progress(String runId) {
        RunState s = runs.get(runId);
        if (s == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        return new PipelineEngine.RunProgress(s.currentStageId, s.stageTotal, s.stages.size(),
                now - s.createdAt,
                s.stageStartedAt > 0 ? now - s.stageStartedAt : 0,
                s.cancelled, s.forceTerminated);
    }

    /** 自动修复状态：供工作台显示「已自动修复 / 已自动续跑 / 仍需人工」。 */
    @Override
    public PipelineEngine.AutoRepairView autoRepair(String runId) {
        RunState s = runs.get(runId);
        if (s == null) {
            return null;
        }
        return new PipelineEngine.AutoRepairView(autoRepairPolicy.enabled(),
                s.autoRepairAttempts, autoRepairPolicy.maxAttempts(), s.autoRepairResumed,
                s.autoRepairLastAction == null ? "" : s.autoRepairLastAction,
                s.autoRepairLastResult == null ? "" : s.autoRepairLastResult);
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
            s.cancelRequestedAt = System.currentTimeMillis();
            // 立即给出可见反馈；真正的终止由各阻塞点的 200ms 取消检查完成，
            // 看护会在 60s 后仍未收敛时强制终态。
            s.log.append("[cancel] 已请求取消，正在终止当前命令…\n");
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
                return ProcessExecutor.redact(s.log.toString(), s.sensitiveValues);
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
        String added = ProcessExecutor.redact(raw.substring(from),
                s == null ? Set.of() : s.sensitiveValues);
        // 片段脱敏后长度可能变化，end 用「原始已读位置」表达，前端只用 offset 续传
        return new LogSlice(from, raw.length(), added);
    }

    /** 构建历史列表（含重启前落盘的运行），转为 domain 视图。 */
    @Override
    public List<RunSummary> history() {
        return history.summaries(null, 0, Integer.MAX_VALUE).stream()
                .map(h -> new RunSummary(h.runId(), h.projectId(), h.env(), h.pipelineId(), h.gitSha(),
                        h.status(), h.createdAt(), h.seq(), h.stageCount(), h.durationMs(),
                        h.version(), h.tagStatus(), h.branch()))
                .toList();
    }

    /** 历史分页直接在摘要索引上完成：不重读 YAML，耗时与历史条数无关。 */
    @Override
    public List<RunSummary> history(String projectId, int offset, int limit) {
        return history.summaries(projectId, offset, limit).stream()
                .map(h -> new RunSummary(h.runId(), h.projectId(), h.env(), h.pipelineId(), h.gitSha(),
                        h.status(), h.createdAt(), h.seq(), h.stageCount(), h.durationMs(),
                        h.version(), h.tagStatus(), h.branch()))
                .toList();
    }

    // ---------------------------------------------------------------- 镜像版本账本 / 部署预检

    @Override
    public List<ImageVersion> deployImages(String projectId, String env) {
        return imageVersions.list(projectId, env);
    }

    @Override
    public java.util.Optional<String> gitShaForImage(String projectId, String env, String tag) {
        return imageVersions.find(projectId, env, tag).map(ImageVersion::gitSha)
                .filter(sha -> sha != null && !sha.isBlank());
    }

    /**
     * 按版本部署的触发前预检：把"触发后才失败"的错误提前到 400。
     *
     * <p>校验顺序与执行期一致：版本归属 → 镜像是否存在 / Docker 是否可用 →
     * 环境密钥（E-704）→ 必填变量（E-509）。只读，不启动任何容器。
     */
    @Override
    public List<String> deployPreflight(String projectId, String env, String tag) {
        List<String> blockers = new ArrayList<>();
        if (tag == null || tag.isBlank()) {
            return List.of("镜像 tag 为空");
        }
        if (imageVersions.find(projectId, env, tag).isEmpty()) {
            blockers.add("镜像版本不存在或不属于 " + projectId + " / " + env + "：" + tag);
        }
        if (!dockerAvailable()) {
            blockers.add(ErrorCode.LOCAL_IMAGE_UNAVAILABLE.code() + " 本机 Docker 不可用，无法按版本部署镜像 " + tag);
        } else if (!dockerImageExists(tag)) {
            blockers.add(ErrorCode.LOCAL_IMAGE_UNAVAILABLE.code() + " 本机不存在镜像 " + tag + "（可能已被清理），请重新构建该版本");
        }
        if (envStore != null) {
            try {
                EffectiveEnvironment effective = envStore.effective(projectId, env);
                if (!effective.missingSecrets().isEmpty()) {
                    blockers.add(ErrorCode.SECRET_UNRESOLVED.code() + " 密钥引用无法解析：" + String.join("、", effective.missingSecrets())
                            + "。请先在「设置 → 项目环境 → 密钥」创建或轮换对应密钥");
                }
                PipelineDefinition definition = readDefinition(projectId, env);
                if (definition != null && definition.deploy() != null) {
                    List<String> missing = missingRequiredEnv(definition.deploy(), effective.values());
                    if (!missing.isEmpty()) {
                        blockers.add(ErrorCode.ENV_REQUIRED_MISSING.code() + " 环境「" + env + "」缺少必填环境变量 " + String.join("、", missing)
                                + "。请到「设置 → 环境」或「项目覆盖」填写后重试");
                    }
                }
            } catch (RuntimeException ignored) {
                // 环境数据异常不阻断触发：真正的校验仍会在执行期做
            }
        }
        return blockers;
    }

    /** 触发前只读解析定义（不检出代码）；失败返回 null，避免预检本身变成阻塞源。 */
    private PipelineDefinition readDefinition(String projectId, String env) {
        if (resolver == null) {
            return null;
        }
        try {
            return resolver.resolveForRead(projectId, resolveRepoForRead(projectId), env).definition();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean dockerImageExists(String tag) {
        // 只读预检：缺镜像是配置问题，5s 内给出 false，由调用方转成 E-705 明确阻塞原因。
        return BoundedCommand.run(dockerCli.cmd("image", "inspect", tag), 5_000L).ok();
    }

    @Override
    public boolean deleteHistory(String runId) {
        RunState state = runs.get(runId);
        if (state != null && !state.status.isTerminal()) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "运行进行中，不能删除");
        }
        // 失败运行可能保留了构建现场，删除历史时一并清掉，避免 worktree 越积越多
        if (state != null && state.keptRunDir != null && state.keptBare != null) {
            cleanupWorktree(state.keptBare, state.keptRunDir, state);
            state.keptRunDir = null;
            state.keptBare = null;
        }
        boolean deleted = history.delete(runId);
        runs.remove(runId);
        deleteArtifactDir(runId);
        return deleted;
    }

    /** 删除历史时一并清掉该运行归档的产物目录（否则磁盘只增不减）。 */
    private void deleteArtifactDir(String runId) {
        Path dir = workspaceRoot.resolve("artifacts").resolve(runId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 单个文件被占用时不阻塞历史删除
                }
            });
        } catch (IOException e) {
            ProcessExecutor.warn("清理产物目录失败 " + dir + ": " + e.getMessage());
        }
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
                } catch (PlatformException | IllegalArgumentException ignored) {
                    // 未知来源（旧文件或枚举演进）：保持 null
                }
            }
            restored.definitionNote = snap.definitionNote();
            restored.deployVersion = snap.version();
            // 平台重启时还在 PENDING/RUNNING 的运行永远不会再推进：
            // 不修正就会在历史里留下一条"进行中"，让页头一直显示 1 个运行。
            if (!restored.status.isTerminal()) {
                restored.status = StageStatus.CANCELLED;
                restored.log.append("[recover] 平台重启前运行未完成，已标记为取消\n");
                history.save(restored.snapshot());
            }
            runs.put(snap.runId(), restored);
            if (restored.status == StageStatus.SUCCEEDED) {
                // 升级兼容：账本里没有的旧版本从历史导入一次，保证回滚锚点/按版本部署不丢数据
                snap.artifacts().stream()
                        .filter(a -> "image".equals(a.kind()))
                        .filter(a -> imageVersions.find(snap.projectId(), snap.env(), a.ref()).isEmpty())
                        .forEach(a -> imageVersions.upsert(snap.projectId(), snap.env(),
                                new top.rongx.agent.domain.port.PipelineEngine.ImageVersion(
                                        a.ref(), a.digest(), a.sizeBytes(),
                                        a.meta().getOrDefault("version", ""),
                                        snap.gitSha(), snap.runId(), "SUCCEEDED",
                                        snap.createdAt(), snap.createdAt(), "history", snap.seq())));
            }
        }
    }

    // ---------------------------------------------------------------- 执行

    // ---------------------------------------------------------------- 运行看护

    /**
     * 周期扫描所有进行中的运行：定期落盘、无输出时写心跳、超预算请求取消、
     * 取消后仍不收敛则强制终态。
     *
     * <p>动作级超时只覆盖 {@link ProcessExecutor} 管辖的进程；准备阶段、收尾清理、
     * 文件 IO、失败自动诊断等都在其外。看护是最后一道防线，保证任何阻塞点都不会
     * 让工作台永远显示「运行中」。
     */
    private void watchdogTick() {
        long now = System.currentTimeMillis();
        for (RunState s : runs.values()) {
            if (s.status != StageStatus.RUNNING) {
                continue;
            }
            try {
                heartbeat(s, now);
            } catch (Exception e) {
                log.warn("运行看护异常 run={}：{}", s.runId, e.getMessage());
            }
        }
    }

    private void heartbeat(RunState s, long now) {
        // 1. 运行中定期落盘：后端崩溃/重启后历史不再是 PENDING 空日志
        if (now - s.lastSavedAt >= HEARTBEAT_SAVE_MS) {
            history.save(s.snapshot());
            s.lastSavedAt = now;
        }
        // 2. 日志长时间无变化 → 写心跳行（mvn -q 之类静默命令也能看出"还活着"）
        synchronized (s.log) {
            if (s.log.length() != s.lastLogLength) {
                s.lastLogLength = s.log.length();
                s.lastLogChangeAt = now;
            } else if (now - s.lastLogChangeAt >= HEARTBEAT_LOG_MS) {
                s.log.append("[heartbeat] 运行中 · 已耗时 ")
                        .append((now - s.createdAt) / 1000).append("s · 当前阶段 ")
                        .append(s.currentStageId == null ? "准备中" : s.currentStageId)
                        .append("（暂无新输出，进程仍在运行）\n");
                s.lastLogLength = s.log.length();
                s.lastLogChangeAt = now;
            }
        }
        // 3. 超出总预算：先请求取消，所有受管进程会在 200ms 内被终止并走正常失败路径。
        // 自动修复期间不按原预算误杀：pull（硬超时）与 Agent 诊断（5 分钟）本来就有界，
        // 且续跑开始时会把新增耗时计入 maxRunMillis。
        if (!s.autoRepairing && !s.cancelled && now - s.createdAt > s.maxRunMillis) {
            s.cancelled = true;
            s.cancelRequestedAt = now;
            synchronized (s.log) {
                s.log.append("[watchdog] 运行超过总预算 ")
                        .append(s.maxRunMillis / 60_000).append(" 分钟，已请求取消\n");
            }
        }
        // 4. 仍不收敛（卡在没有取消检查点的调用）→ 强制终态并落盘
        if (s.cancelRequestedAt > 0 && now - s.cancelRequestedAt > FORCE_TERMINATE_AFTER_MS) {
            forceTerminate(s, ErrorCode.TIMEOUT.code() + " 运行超时且无法中断，已强制结束");
        }
    }

    private void forceTerminate(RunState s, String reason) {
        synchronized (s) {
            if (s.status.isTerminal()) {
                return;
            }
            s.forceTerminated = true;
            s.cancelled = true;
            s.status = StageStatus.TIMEOUT;
            synchronized (s.log) {
                s.log.append("[watchdog] ").append(reason).append('\n');
            }
            s.stages.add(StageResult.failed("watchdog", 124, ErrorCode.TIMEOUT.code(), reason, 0));
            history.save(s.snapshot());
            recordLedger(s);
            scheduler.complete(s.projectId, s.env);
        }
    }

    private void execute(RunState state) {
        try {
            state.status = StageStatus.RUNNING;
            state.lastSavedAt = System.currentTimeMillis();
            state.lastLogChangeAt = state.lastSavedAt;
            state.maxRunMillis = WATCHDOG_GRACE_MS;
            state.cancelRequestedAt = 0;
            state.forceTerminated = false;
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
            if (!state.forceTerminated) {
                if (state.status == StageStatus.SUCCEEDED) {
                    // 打 tag 的发布成功后才同步到 git（失败不污染仓库）
                    syncGitTag(state);
                }
                // 每次运行结束（含异常）都落盘，保证重启后可查询历史。
                // 看护已强制终态时跳过，避免重复 complete 或把 TIMEOUT 覆盖回 RUNNING。
                history.save(state.snapshot());
                recordLedger(state);
                scheduler.complete(state.projectId, state.env);
            }
        }
    }

    /**
     * 把本次发布的版本号同步成 git 标签：本地仓库创建 tag，有 origin 时尽力推送。
     *
     * <p>失败只记日志，不影响已经成功的部署；历史里仍会显示用户填写的版本号。
     */
    /**
     * 把本次版本号同步到构建 worktree 的根 pom.xml。
     *
     * <p>快照构建写 {@code x.y.z-SNAPSHOT}，正式发布写 {@code x.y.z}；
     * 失败只记日志，不能让版本展示问题阻断发布。
     */
    private void syncPomVersion(Path runDir, RunState state) {
        PomVersionSync.apply(runDir, state.deployVersion, !state.deployTagged)
                .ifPresent(result -> {
                    if (result.oldVersion() == null) {
                        state.log.append("[version] 没有根 pom.xml，跳过版本同步\n");
                    } else if (result.changed()) {
                        state.log.append("[version] pom.xml ").append(result.oldVersion())
                                .append(" → ").append(result.newVersion()).append('\n');
                    } else {
                        state.log.append("[version] pom.xml 已是 ")
                                .append(result.newVersion()).append('\n');
                    }
                });
    }

    private void syncGitTag(RunState state) {
        if (!state.deployTagged || state.deployVersion == null
                || state.deployVersion.isBlank() || repoLocator == null) {
            // 未打 tag 的构建是快照版：历史里要能和正式版本区分开
            state.tagStatus = state.deployVersion == null ? null : "SNAPSHOT";
            return;
        }
        try {
            top.rongx.agent.domain.port.ProjectRepoLocator.TagOutcome outcome =
                    repoLocator.createTag(state.projectId, state.deployVersion, state.gitSha,
                            userIdOf(state.actor));
            // PUSHED=远端可见；LOCAL=只存在本机（无 origin/推送失败）；FAILED=创建失败
            state.tagStatus = outcome.pushed() ? "PUSHED" : outcome.created() ? "LOCAL" : "FAILED";
            state.log.append("[tag] ").append(outcome.message()).append('\n');
            if (!outcome.pushed()) {
                state.log.append("[tag] 标签未同步到远端：检查代码托管账号的凭据后，"
                        + "可在构建历史里点「重推标签」。\n");
            }
        } catch (Exception e) {
            state.tagStatus = "FAILED";
            state.log.append("[tag] 同步 git 标签失败：").append(e.getMessage()).append('\n');
        }
    }

    /** 触发者形如 {@code ui:<userId>}；agent 触发时没有用户，返回 null（退回本机凭据）。 */
    private static String userIdOf(String actor) {
        if (actor == null) {
            return null;
        }
        String value = actor.trim();
        return value.startsWith("ui:") && value.length() > 3 ? value.substring(3) : null;
    }

    /**
     * 把当前运行状态同步到统一账本（跨编排视图 + 重启恢复）。
     *
     * <p>只写扁平生命周期，不复制阶段细节；写入失败只记日志，流水线照常结束。
     */
    private void recordLedger(RunState state) {
        if (ledger == null) {
            return;
        }
        try {
            top.rongx.agent.domain.run.RunState mapped = switch (state.status) {
                case PENDING -> top.rongx.agent.domain.run.RunState.QUEUED;
                case RUNNING -> top.rongx.agent.domain.run.RunState.RUNNING;
                case SUCCEEDED -> top.rongx.agent.domain.run.RunState.SUCCEEDED;
                case TIMEOUT -> top.rongx.agent.domain.run.RunState.FAILED;
                case FAILED -> top.rongx.agent.domain.run.RunState.FAILED;
                case CANCELLED -> top.rongx.agent.domain.run.RunState.CANCELLED;
            };
            String error = "";
            if (state.status == StageStatus.FAILED || state.status == StageStatus.TIMEOUT) {
                error = state.stages.stream()
                        .filter(stage -> stage.status() == StageStatus.FAILED
                                || stage.status() == StageStatus.TIMEOUT)
                        .reduce((first, second) -> second)
                        .map(stage -> stage.errorSummary() == null
                                ? String.valueOf(stage.errorCode()) : stage.errorSummary())
                        .orElse("运行失败");
            }
            ledger.save(new top.rongx.agent.domain.run.RunRecord(
                    state.runId, top.rongx.agent.domain.run.RunKind.PIPELINE, state.projectId,
                    state.pipelineId,
                    state.actor == null || state.actor.isBlank() ? "system" : state.actor,
                    mapped, stageLabel(state), error, state.createdAt, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("运行账本写入失败 run={}：{}", state.runId, e.getMessage());
        }
    }

    private static String stageLabel(RunState state) {
        if (state.status == StageStatus.PENDING) {
            return "已排队";
        }
        if (state.status == StageStatus.RUNNING) {
            return "执行中";
        }
        if (state.stages.isEmpty()) {
            return "已结束";
        }
        StageResult last = state.stages.get(state.stages.size() - 1);
        return "最近阶段 " + last.stageId() + " / " + last.status();
    }

    /**
     * 失败处理：先做**平台可自证**的自动修复，复核通过就自动从失败阶段续跑；
     * 修复不成功或策略关闭时才回落到「发布 Agent 诊断 + 人工重跑」。
     *
     * <p>与旧行为的区别：以前只有一句「自动调用发布 Agent 诊断与修复…」，诊断结论不会让
     * 运行继续，镜像缺失这类确定性故障也必须人工 pull + 点重跑。现在「修复 → 验证 →
     * 续跑」由执行器闭环完成，模型只在确定性修复失败时兜底。
     *
     * <p>约束：生产 / Agent 自触发不进入（避免递归）；诊断期间状态回 RUNNING 让前端继续
     * 轮询日志；同一失败周期幂等，一次运行最多 {@link AutoRepairPolicy#maxAttempts()} 轮。
     */
    private void recoverIfNeeded(RunState state) {
        if (state.status != StageStatus.FAILED || state.forceTerminated || state.cancelled
                || state.recoveryDone) {
            return;
        }
        if ("prod".equalsIgnoreCase(state.env)
                || state.actor == null || !state.actor.startsWith("ui:")) {
            return;
        }
        state.recoveryDone = true;
        if (!autoRepairPolicy.enabled()) {
            agentDiagnosisOnly(state);
            return;
        }
        state.status = StageStatus.RUNNING;
        state.autoRepairing = true;
        state.cancelRequestedAt = 0;
        appendLog(state, "[autofix] 运行失败，开始自动修复（最多 "
                + autoRepairPolicy.maxAttempts() + " 轮；只有平台复核通过才自动续跑）\n");
        try {
            AutoRepairCoordinator.Result result = new AutoRepairCoordinator(
                    autoRepairPolicy, repairChain()).run(new AutoRepairCoordinator.Host() {
                        @Override
                        public FailureRepair.Diagnosis diagnosis(int attempt) {
                            return diagnoseFailure(state, attempt);
                        }

                        @Override
                        public FailureRepair.Context context(int attempt) {
                            return repairContext(state);
                        }

                        @Override
                        public void log(String line) {
                            appendLog(state, line);
                        }

                        @Override
                        public boolean cancelled() {
                            return state.cancelled || state.forceTerminated;
                        }

                        @Override
                        public boolean resume(String stageId) {
                            return resumeFromFailedStage(state, stageId);
                        }
                    });
            state.autoRepairAttempts = result.attempts();
            state.autoRepairResumed = result.resumed();
            state.autoRepairLastAction = result.lastAction();
            state.autoRepairLastResult = result.lastResult();
            if (result.succeeded()) {
                appendLog(state, "[autofix] ✅ 自动修复完成，运行已恢复成功（无需人工介入）\n");
            } else {
                appendLog(state, "[autofix] 自动修复未收敛：" + result.lastResult()
                        + " · 共 " + result.attempts() + " 轮 · 运行保持失败，请查看结论或手动重跑\n");
            }
            history.save(state.snapshot());
        } finally {
            state.autoRepairing = false;
            if (state.status == StageStatus.RUNNING) {
                state.status = StageStatus.FAILED;
            }
        }
    }

    /** 策略关闭时的原行为：只把发布 Agent 的诊断步骤写进日志，不改变终态。 */
    private void agentDiagnosisOnly(RunState state) {
        if (recoveryAdvisor == null || !recoveryAdvisor.available()) {
            return;
        }
        state.status = StageStatus.RUNNING;
        appendLog(state, "[agent] 运行失败，自动调用发布 Agent 诊断与修复…\n");
        try {
            List<String> lines = recoveryAdvisor.advise(
                    new top.rongx.agent.domain.run.RunRecoveryAdvisor.Context(
                            state.runId, state.projectId, state.env, state.pipelineId,
                            state.gitSha, state.actor, tailOfLog(state, 20_000)));
            if (lines == null || lines.isEmpty()) {
                appendLog(state, "[agent] 本次未产生诊断步骤\n");
            } else {
                for (String line : lines) {
                    appendLog(state, "[agent] " + line + "\n");
                }
            }
        } catch (Throwable e) {
            appendLog(state, "[agent] 自动诊断异常："
                    + (e.getMessage() == null ? e.toString() : e.getMessage()) + "\n");
        } finally {
            state.status = StageStatus.FAILED;
        }
    }

    /** 当前失败快照：失败阶段、错误码、该阶段的日志尾部、日志里出现的缺失镜像名。 */
    private FailureRepair.Diagnosis diagnoseFailure(RunState state, int attempt) {
        StageResult failed = lastFailedStage(state);
        String code = failed == null ? "" : String.valueOf(failed.errorCode());
        String message = failed == null ? "运行失败"
                : failed.errorSummary() == null ? code : failed.errorSummary();
        // 只取失败阶段自己的日志：整段运行日志里可能残留前一个阶段已自愈的
        // "No such image: maven..."，否则前端阶段失败会被误判成后端镜像缺失并重复 pull。
        String tail = stageTail(state, failed == null ? null : failed.stageId(), 20_000);
        // 动作前预检（DockerSandbox.ensureImageReady）已经自行 pull 过：
        // 再拉一次只会把一次失败拖成两轮硬超时，交给 Agent 兜底/人工排查更合适。
        boolean precheckTried = tail.contains("自动拉取失败")
                || tail.contains("拉取后仍无法通过 docker image inspect");
        return new FailureRepair.Diagnosis(state.runId,
                failed == null ? null : failed.stageId(), code, message, tail,
                FailureRepair.Diagnosis.missingImage(message, tail), attempt,
                autoRepairPolicy.agentFallback(), precheckTried);
    }

    private static StageResult lastFailedStage(RunState state) {
        for (int i = state.stages.size() - 1; i >= 0; i--) {
            StageResult r = state.stages.get(i);
            if (r.status() == StageStatus.FAILED || r.status() == StageStatus.TIMEOUT) {
                return r;
            }
        }
        return null;
    }

    private static String tailOfLog(RunState state, int max) {
        String text;
        synchronized (state.log) {
            text = state.log.toString();
        }
        return text.length() > max ? text.substring(text.length() - max) : text;
    }

    /**
     * 失败阶段最后一次执行的日志段（从最近一次「[stageId] 阶段开始」算起）。
     *
     * <p>自动修复必须只看失败阶段自己的输出：整段运行日志会把上一个阶段已经
     * 自动拉取成功的旧 "No such image" 带进来，导致修复器对着错误的镜像反复 pull。
     */
    private static String stageTail(RunState state, String stageId, int max) {
        if (stageId == null || stageId.isBlank()) {
            return tailOfLog(state, max);
        }
        String text;
        synchronized (state.log) {
            text = state.log.toString();
        }
        int start = text.lastIndexOf("[" + stageId + "] 阶段开始");
        if (start < 0) {
            start = text.lastIndexOf("[" + stageId + "]");
        }
        if (start < 0) {
            return text.length() > max ? text.substring(text.length() - max) : text;
        }
        String segment = text.substring(start);
        return segment.length() > max ? segment.substring(segment.length() - max) : segment;
    }

    /** 线程安全地追加一行日志，并刷新「最后输出时间」避免看护误写心跳。 */
    private static void appendLog(RunState state, String line) {
        synchronized (state.log) {
            state.log.append(line);
        }
        state.lastLogChangeAt = System.currentTimeMillis();
    }

    /** 修复上下文：命令只由管理员配置的沙箱生成，日志/模型提供的字符串不进入 argv。 */
    private FailureRepair.Context repairContext(RunState state) {
        DockerSandbox sandbox = sandboxed instanceof DockerActionRunner dockerRunner
                ? dockerRunner.sandbox() : null;
        StageResult failed = lastFailedStage(state);
        String stageId = failed == null ? null : failed.stageId();
        PipelineDefinition.StageDef stage = stageId == null || state.definition == null
                ? null : state.definition.stages().stream()
                .filter(s -> stageId.equals(s.id())).findFirst().orElse(null);
        // 修复必须对着失败阶段自己的工具链镜像（如 frontend 的 node），
        // 不能拿全局默认镜像（maven）去 pull/inspect，否则会修错对象。
        if (sandbox != null && stage != null && stage.image() != null
                && !stage.image().isBlank()) {
            sandbox = sandbox.withImage(stage.image());
        }
        return new FailureRepair.Context(
                () -> state.cancelled || state.forceTerminated,
                line -> appendLog(state, line),
                (argv, timeoutMs, live) -> new ProcessExecutor().exec(
                        workspaceRoot == null ? Path.of(".") : workspaceRoot,
                        argv, timeoutMs, new StringBuilder(),
                        () -> state.cancelled || state.forceTerminated, live),
                autoRepairPolicy.pullTimeoutMs(),
                sandbox == null ? null : sandbox.imagePullCommand(),
                sandbox == null ? null : sandbox.imageInspectCommand(),
                sandbox == null ? null : sandbox.image(),
                recoveryAdvisor, state.projectId, state.env, state.pipelineId,
                state.gitSha, state.actor, stageTail(state, stageId, 20_000),
                () -> preconditionsOk(state));
    }

    /**
     * 平台侧的「现在能不能重跑」复核：只读 dry-run 无阻塞项。
     *
     * <p>Agent 修好端口、健康路径、环境变量或阶段定义后，这里必须重新算出"真的能跑"，
     * 而不是采信模型自述；dry-run 异常一律按未通过处理（保守，不自动续跑）。
     */
    private boolean preconditionsOk(RunState state) {
        try {
            var report = dryRun(state.projectId, state.pipelineId, state.env, state.gitSha);
            return report != null && report.ok() && report.blockers().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 自动续跑：复用保留的构建现场，只重跑失败阶段及其后阶段。
     *
     * <p>与人工「从失败阶段继续」共用 {@link #prepareResume}，行为完全一致；
     * 区别只是触发者是平台自己，且不会再次进入失败自愈（由协调器统一控制轮次）。
     *
     * @return 运行是否已恢复成功
     */
    private boolean resumeFromFailedStage(RunState state, String stageId) {
        // 本机（dev）没有 worktree：按最新配置整条重跑，否则 dev 的环境类故障永远无法自愈。
        if (isLocalDev(state)) {
            return resumeLocalDev(state);
        }
        PipelineDefinition definition = state.definition;
        Path runDir = state.keptRunDir;
        if (definition == null || runDir == null || !Files.isDirectory(runDir)
                || state.deployImage != null) {
            appendLog(state, "[autofix] 无可用构建现场（按版本部署），跳过自动续跑\n");
            return false;
        }
        // Agent 修的往往就是定义/端口/健康路径：续跑前必须重新解析，否则刚修好的配置
        // 会被失败时缓存的 state.definition 覆盖回去，看起来"改了但没用"。
        definition = refreshDefinitionForResume(state, runDir);
        int idx = -1;
        for (int i = 0; i < definition.stages().size(); i++) {
            if (definition.stages().get(i).id().equals(stageId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            appendLog(state, "[autofix] 失败阶段 " + stageId + " 不在当前定义中，跳过自动续跑\n");
            return false;
        }
        Map<String, String> vars = state.keptVars;
        if (vars == null) {
            String tag = imagePrefix + "-" + state.env + "-" + state.gitSha.substring(0, 7);
            vars = Map.of("gitSha", state.gitSha,
                    "gitSha7", state.gitSha.substring(0, 7), "tag", tag);
        }
        prepareResume(state, definition, idx);
        state.status = StageStatus.RUNNING;
        state.cancelled = false;
        state.forceTerminated = false;
        state.cancelRequestedAt = 0;
        state.lastSavedAt = System.currentTimeMillis();
        state.lastLogChangeAt = state.lastSavedAt;
        // 自愈 + 续跑是新增的执行周期，不能让原来的总预算把刚恢复的运行再次杀掉
        state.maxRunMillis += autoRepairPolicy.pullTimeoutMs() + 600_000L;
        appendLog(state, "[retry] 自动修复后从阶段 " + stageId + " 继续（复用构建现场）\n");
        try {
            runStagesFrom(state, definition, runDir, vars, idx);
        } catch (Throwable e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            appendLog(state, "[autofix] 自动续跑异常：" + why + "\n");
            state.stages.add(StageResult.failed("engine", 1, "E-2xx", why, 0));
            state.status = StageStatus.FAILED;
        }
        if (state.status == StageStatus.SUCCEEDED) {
            if (state.keptBare != null) {
                cleanupWorktree(state.keptBare, runDir, state);
            }
            state.keptRunDir = null;
            state.keptBare = null;
        }
        history.save(state.snapshot());
        return state.status == StageStatus.SUCCEEDED;
    }

    /**
     * 本机（dev）自动续跑：没有 worktree 可复用，按最新配置整条重跑。
     *
     * <p>仍是同一次自愈周期（{@code recoveryDone} 保持 true），不会递归触发自动修复；
     * 上一轮遗留的本机进程/jar 锁会在 {@code executeLocalDev} 开头先清理。
     */
    private boolean resumeLocalDev(RunState state) {
        Path repo = repoLocator == null ? null : repoLocator.repoDir(state.projectId).orElse(null);
        refreshDefinitionForResume(state, repo);
        state.stages.clear();
        state.artifacts.clear();
        state.status = StageStatus.RUNNING;
        state.cancelled = false;
        state.forceTerminated = false;
        state.cancelRequestedAt = 0;
        state.lastSavedAt = System.currentTimeMillis();
        state.lastLogChangeAt = state.lastSavedAt;
        state.maxRunMillis += autoRepairPolicy.pullTimeoutMs() + 600_000L;
        appendLog(state, "[retry] 自动修复后重新执行本机流水线（无构建现场可复用）\n");
        try {
            doExecute(state);
        } catch (Throwable e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            appendLog(state, "[autofix] 本机自动续跑异常：" + why + "\n");
            state.stages.add(StageResult.failed("engine", 1, "E-2xx", why, 0));
            state.status = StageStatus.FAILED;
        }
        history.save(state.snapshot());
        return state.status == StageStatus.SUCCEEDED;
    }

    /**
     * 自动续跑前重新解析定义：Agent 修的往往就是端口 / 健康路径 / 阶段动作。
     *
     * <p>沿用失败时缓存的 {@code state.definition} 会把刚修好的配置覆盖回去，
     * 表现为「Agent 改了但续跑还是旧错误」。解析失败时退回旧定义，避免把一次可重试变成定义错误。
     *
     * @param runDir 定义解析根（本机模式传仓库目录，普通模式传保留的 worktree）
     */
    private PipelineDefinition refreshDefinitionForResume(RunState state, Path runDir) {
        if (resolver == null || runDir == null) {
            return state.definition;
        }
        try {
            Path repoRoot = repoLocator == null ? null : repoLocator.repoDir(state.projectId).orElse(null);
            PipelineDefinitionResolver.Resolved resolved =
                    resolver.resolveForRun(state.projectId, runDir, repoRoot, state.env);
            if (resolved == null || resolved.definition() == null) {
                return state.definition;
            }
            state.recipeRoot = resolved.recipeRoot();
            // 续跑必须重新裁剪：否则 verify 会拿到完整 release 的阶段列表，
            // 只读的检查构建变成真实部署（并在成功后固化配方）
            state.definition = applyRunMode(state, resolved.definition());
            state.definitionSource = resolved.source();
            state.definitionNote = resolved.note();
            appendLog(state, "[autofix] 按最新定义续跑（" + resolved.source()
                    + " · " + resolved.note() + "）\n");
            return state.definition;
        } catch (RuntimeException e) {
            appendLog(state, "[autofix] 重新解析定义失败，沿用本次运行的定义：" + e.getMessage() + "\n");
            return state.definition;
        }
    }

    /** 保留失败阶段之前的结果，回收该阶段及之后产生的产物，避免续跑后产物重复。 */
    private void prepareResume(RunState state, PipelineDefinition definition, int idx) {
        List<PipelineDefinition.StageDef> all = definition.stages();
        java.util.Set<String> before = new java.util.HashSet<>();
        for (int i = 0; i < idx; i++) {
            before.add(all.get(i).id());
        }
        List<StageResult> prefix = new ArrayList<>();
        // checkout 是引擎内置阶段（不在 definition.stages() 里），必须保留：
        // 否则续跑后阶段编号会从 2/6 退回 1/6，证据里也丢了检出记录
        int normal = 0;
        for (StageResult r : state.stages) {
            if ("checkout".equals(r.stageId())) {
                prefix.add(r);
                continue;
            }
            if (before.contains(r.stageId()) && normal < idx) {
                prefix.add(r);
                normal++;
            }
        }
        state.stages.clear();
        state.stages.addAll(prefix);
        Integer mark = state.artifactMark.get(all.get(idx).id());
        int cut = mark == null ? state.artifacts.size() : Math.min(mark, state.artifacts.size());
        if (cut < state.artifacts.size()) {
            state.artifacts.subList(cut, state.artifacts.size()).clear();
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
                    () -> new BuildException(ErrorCode.BUILD_INVALID_STATE, "项目仓库不存在（projectId=" + state.projectId
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
        Path runDir = state.deployImage != null
                // 按版本部署：镜像已存在，不需要检出代码；用仓库根（DIRECT 是工作区，MANAGED 是 bare）
                ? bare
                : wtParent.resolve("wt-build-" + state.runId);
        PipelineDefinition definition = null;
        boolean worktreeCreated = false;

        try {
            if (!Files.isDirectory(bare.resolve(".git"))
                    && !Files.isDirectory(bare.resolve("objects"))) {
                throw new BuildException(ErrorCode.BUILD_INVALID_STATE, "项目仓库不存在：" + bare
                        + "（请先通过「接入项目」完成登记）");
            }
            if (state.deployImage != null) {
                state.log.append("[deploy] 按版本部署：跳过检出，直接复用镜像 ")
                        .append(state.deployImage).append('\n');
            } else {
                // stage 1：从 bare 检出指定提交到独立 worktree
                checkout(bare, runDir, state);
                worktreeCreated = true;
            }
            // 版本号是发布动作的唯一来源：在一次性构建 worktree 里同步 pom，不动用户工作区
            if (state.deployVersion != null) {
                syncPomVersion(runDir, state);
            }
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
            // 运行模式裁剪只走 applyRunMode：触发与自动续跑必须得到同一份计划，
            // 否则 verify 续跑会退化成完整 release（部署 + 固化配方），只读检查构建污染环境。
            definition = applyRunMode(state, definition);
            if (state.deployImage != null) {
                if (definition.stages().isEmpty()) {
                    state.log.append("[deploy] 流水线没有 deploy 阶段，无法按版本部署\n");
                    state.stages.add(StageResult.failed("deploy", 1, ErrorCode.DEPLOY_SECTION_MISSING.code(),
                            "流水线没有 deploy 阶段，无法按版本部署", 0));
                    state.status = StageStatus.FAILED;
                    return;
                }
                // 复用版本的摘要/大小/版本号沿用账本，避免按版本部署把元信息覆盖成 tag
                ImageVersion known = imageVersions.find(state.projectId, state.env,
                        state.deployImage).orElse(null);
                String reusedVersion = known == null || known.version() == null
                        || known.version().isBlank() ? state.deployImage : known.version();
                state.artifacts.add(new ArtifactRef("image", state.deployImage,
                        known == null ? "" : known.digest(),
                        known == null ? 0 : known.sizeBytes(),
                        Map.of("version", reusedVersion, "reused", "true",
                                "localImage", state.deployImage)));
                state.log.append("[deploy] 按版本部署：复用镜像 ").append(state.deployImage)
                        .append("（跳过构建与打包）\n");
            }

            // 镜像 tag 只由「环境 + 提交」决定：同一提交重复构建复用同一个 tag（不会堆 vN），
            // 版本号 vN 只写进产物 meta，用于页面展示与回滚谱系。
            String tag = state.deployImage != null ? state.deployImage
                    : imagePrefix + "-" + state.env + "-" + state.gitSha.substring(0, 7);
            if (state.deployImage == null) {
                if ("verify".equals(state.pipelineId)) {
                    // 检查构建不打包、不记账：别让日志宣称"镜像版本 vN"
                    // 而环境面板显示"这个环境还没有镜像版本"。
                    state.log.append("[image] 检查构建：跳过镜像打包，不记录环境镜像版本\n");
                } else {
                    state.log.append("[image] 镜像版本 ").append(versionLabel(state))
                            .append(" · 本地镜像 ").append(tag).append('\n');
                }
            }
            Map<String, String> vars = Map.of(
                    "gitSha", state.gitSha,
                    "gitSha7", state.gitSha.substring(0, 7),
                    "tag", tag);
            state.definition = definition;
            // 自动续跑要复用同一份变量（tag / sha），不能靠重建，否则按版本部署会串 tag
            state.keptVars = vars;

            // E-508：部署配置必须与默认分支一致（防"改 YAML 即发布"绕过 review）
            if (!deployGuard(bare, runDir, definition, state)) {
                state.stages.add(StageResult.failed("deploy-guard", 1, ErrorCode.PIPELINE_DEFINITION_UNMERGED.code(),
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
        // +1 = 引擎内置的 checkout 阶段（forDeployOnly 跳过检出时不计）
        state.stageTotal = state.deployImage == null ? all.size() + 1 : all.size();
        for (int i = from; i < all.size(); i++) {
            if (state.forceTerminated) {
                return; // 看护已置终态，执行线程不得再改状态
            }
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
            // 只有真的产出了镜像才更新锚点：host 部署没有镜像，不该污染回滚目标
            ArtifactRef imageArtifact = state.artifacts.stream()
                    .filter(a -> "image".equals(a.kind()))
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (imageArtifact != null) {
                // 记账：发布成功即登记/更新版本账本（同一 tag 重复发布只保留一条，更新部署信息）
                imageVersions.upsert(state.projectId, state.env, new ImageVersion(
                        imageArtifact.ref(), imageArtifact.digest(), imageArtifact.sizeBytes(),
                        imageArtifact.meta().getOrDefault("version", versionOf(state)),
                        state.gitSha, state.runId, "SUCCEEDED",
                        state.createdAt, System.currentTimeMillis(),
                        state.actor == null ? "ui" : state.actor, state.seq));
            }
            // 按版本部署只切运行态：脚本仍是该版本验证过的那份，不重新固化（避免用旧提交覆盖环境脚本）
            if (state.deployImage == null) {
                freezeRecipe(state, runDir);
            }
            pruneOldImages(state);
        } else {
            state.log.append("[recipe] 本次是检查构建（未部署），不固化环境脚本、不更新回滚锚点\n");
        }
        if (state.forceTerminated) {
            return;
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
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "运行不存在：" + runId);
        }
        if (!state.status.isTerminal()) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "运行仍在进行中，不能重试");
        }
        if (state.status != StageStatus.FAILED) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "只有失败的运行可以重试");
        }
        if (isLocalDev(state)) {
            state.stages.clear();
            state.artifacts.clear();
            state.recoveryDone = false;
            state.autoRepairAttempts = 0;
            state.log.append("[retry] 重新执行本机部署\n");
            state.status = StageStatus.RUNNING;
            state.cancelled = false;
            history.save(state.snapshot());
            scheduler.submit(state.projectId, state.env, () -> workers.submit(() -> execute(state)));
            return runId;
        }
        PipelineDefinition definition = state.definition;
        Path runDir = state.keptRunDir;
        if (definition == null || runDir == null || !Files.isDirectory(runDir)) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "构建现场已清理，无法从该阶段继续，请整条重跑");
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
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "阶段不可重试：" + stageId + "（请整条重跑）");
        }
        // 保留该阶段之前的结果，并回收该阶段及之后产生的产物（避免重试后产物重复）
        prepareResume(state, definition, idx);
        // 人工重试是一轮新的失败周期：允许再次进入自动修复，但计数从头开始
        state.recoveryDone = false;
        state.autoRepairAttempts = 0;
        state.autoRepairResumed = false;

        String tag = imagePrefix + "-" + state.env + "-" + state.gitSha.substring(0, 7);
        Map<String, String> vars = Map.of(
                "gitSha", state.gitSha,
                "gitSha7", state.gitSha.substring(0, 7),
                "tag", tag);
        state.status = StageStatus.RUNNING;
        state.cancelled = false;
        state.log.append("[retry] 从阶段 ").append(stageId).append(" 继续（复用构建现场）\n");
        history.save(state.snapshot());
        int from = idx;
        scheduler.submit(state.projectId, state.env, () -> workers.submit(
                () -> executeRetry(state, definition, runDir, vars, from)));
        return runId;
    }

    private void executeRetry(RunState state, PipelineDefinition definition, Path runDir,
                              Map<String, String> vars, int from) {
        // 重试是一次新的运行周期：清掉上次的取消/强杀标记，否则看护会在 60s 后误杀
        state.cancelRequestedAt = 0;
        state.forceTerminated = false;
        state.lastSavedAt = System.currentTimeMillis();
        state.lastLogChangeAt = state.lastSavedAt;
        state.maxRunMillis = WATCHDOG_GRACE_MS;
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
            if (!state.forceTerminated) {
                scheduler.complete(state.projectId, state.env);
            }
        }
    }

    // ------------------------------------------------------------ 开发环境本机部署

    /** 镜像版本（展示用）：{@code test-v3}；旧运行没有序号时回落为提交短 sha。 */
    private static String versionOf(RunState state) {
        return state.seq > 0
                ? state.env + "-v" + state.seq
                : state.gitSha.substring(0, Math.min(7, state.gitSha.length()));
    }

    /** 展示版本：用户填写的版本号优先，否则回落到构建序号（test-v3）。 */
    static String versionLabel(String deployVersion, String env, int seq, String gitSha) {
        if (deployVersion != null && !deployVersion.isBlank()) {
            return deployVersion;
        }
        return seq > 0
                ? env + "-v" + seq
                : gitSha.substring(0, Math.min(7, gitSha.length()));
    }

    private static String versionLabel(RunState state) {
        return versionLabel(state.deployVersion, state.env, state.seq, state.gitSha);
    }

    /** 版本槽：打 tag 时用版本号，否则 null（覆盖默认槽）。 */
    private static String deploySlot(RunState state) {
        return state.deployTagged && state.deployVersion != null ? state.deployVersion : null;
    }

    /** 版本号入参校验：允许字母数字开头，后面可带 . _ -，最长 32，防路径穿越。 */
    private static String normalizeDeployVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // 统一走版本模型：严格 x.y.z，不接受 v 前缀，标签名即版本号
        return ReleaseVersion.parse(raw.trim()).toString();
    }

    private static String normalizeBranchName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (!value.matches("[A-Za-z0-9._/-]{1,120}") || value.startsWith("-") || value.contains("..")) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "分支名不合法：" + raw);
        }
        return value;
    }

    /**
     * 正式发布的版本/分支/标签约束（在触发时就拒绝，避免跑完构建才发现版本不对）。
     *
     * <p>规则：版本线必须与分支一致；提交必须在该分支上；补丁号必须连续，
     * 且同版本号不能重复打标签。
     */
    private void requireVersionBranch(RunState state, ReleaseVersion version, String sha) {
        String branch = state.branch;
        if (branch == null) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "正式发布必须选择版本分支（例如 "
                    + version.branchName() + "）");
        }
        if (!ReleaseVersion.isVersionBranch(branch)) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT,
                    "正式发布只能使用版本分支 x.y.0-snapshot（当前：" + branch + "）");
        }
        if (!version.onBranch(branch)) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "版本 " + version + " 属于版本线 "
                    + version.line() + "，只能发布到分支 " + version.branchName() + "（当前：" + branch + "）");
        }
        if (repoLocator == null || !repoLocator.isCommitOnBranch(state.projectId, branch, sha)) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, "提交 " + shortSha(sha) + " 不在分支 "
                    + branch + " 上，无法在该分支打版本标签");
        }
        String problem = versionSequenceProblem(version,
                repoLocator.listTags(state.projectId, 500));
        if (problem != null) {
            throw new BuildException(ErrorCode.BUILD_INVALID_INPUT, problem);
        }
    }

    /** 补丁序列校验：不重复、不倒退、不跳号；第一条版本必须是 x.y.0。 */
    static String versionSequenceProblem(ReleaseVersion version, java.util.List<String> tags) {
        if (tags != null && tags.contains(version.tagName())) {
            return "标签 " + version.tagName() + " 已存在，请递增补丁号";
        }
        ReleaseVersion max = null;
        if (tags != null) {
            for (String tag : tags) {
                ReleaseVersion candidate = ReleaseVersion.tryParse(tag).orElse(null);
                if (candidate == null || !candidate.line().equals(version.line())) {
                    continue;
                }
                if (max == null || candidate.compareTo(max) > 0) {
                    max = candidate;
                }
            }
        }
        if (max == null) {
            if (version.patch() != 0) {
                return "版本线 " + version.line() + " 还没有基础版本，第一个版本必须是 "
                        + version.line() + ".0";
            }
            return null;
        }
        if (version.compareTo(max) <= 0) {
            return "版本 " + version + " 必须大于已发布的最大版本 " + max;
        }
        if (version.patch() != max.patch() + 1) {
            return "补丁号不能跳号：" + version.line() + " 已发布到 " + max
                    + "，下一个版本必须是 " + max.nextPatch();
        }
        return null;
    }

    /** 版本槽目录（HOST 的专属状态目录 / Docker 的端口记录），与 HostDeployer 保持一致。 */
    private Path versionSlotDir(RunState state) {
        String project = state.projectId.replaceAll("[^A-Za-z0-9._-]", "_");
        String slot = state.deployVersion.replaceAll("[^A-Za-z0-9._-]", "-");
        return workspaceRoot.toAbsolutePath().normalize()
                .resolve("_platform").resolve("deploy")
                .resolve(project + "-" + state.env)
                .resolve("versions").resolve(slot);
    }

    /**
     * 打 tag 版本的宿主端口：优先复用该版本上次记录的端口；否则从默认端口之后找空闲端口。
     *
     * <p>端口记录写在版本槽目录里（不占数据库表），并避开其它版本槽已记录的端口，
     * 保证多个版本同时运行时不互相抢端口。
     */
    private int allocateTaggedPort(RunState state, int desired, int containerPort, String key) {
        Path dir = versionSlotDir(state);
        Path portFile = dir.resolve("port" + (key == null || key.isBlank() ? "" : "-" + key));
        int recorded = readPort(portFile);
        java.util.Set<Integer> reserved = otherSlotPorts(portFile);
        if (recorded > 0 && !reserved.contains(recorded) && portFree(recorded)) {
            state.log.append("[port] 版本 ").append(state.deployVersion)
                    .append(" 沿用端口 ").append(recorded).append('\n');
            state.deployHostPort = recorded;
            return recorded;
        }
        int candidate = Math.max(desired + 1, 1);
        for (int i = 0; i < 200; i++, candidate++) {
            if (reserved.contains(candidate) || !portFree(candidate)) {
                continue;
            }
            try {
                Files.createDirectories(dir);
                Files.writeString(portFile, String.valueOf(candidate));
            } catch (IOException e) {
                throw new BuildException(ErrorCode.BUILD_INVALID_STATE, "无法记录版本端口：" + e.getMessage(), e);
            }
            state.log.append("[port] 版本 ").append(state.deployVersion)
                    .append(" 分配端口 ").append(candidate)
                    .append("（容器端口 ").append(containerPort).append("）\n");
            state.deployHostPort = candidate;
            return candidate;
        }
        throw new BuildException(ErrorCode.BUILD_INVALID_STATE, "端口分配失败：从 " + desired
                + " 起 200 个端口都不可用（版本 " + state.deployVersion + "）");
    }

    private static int readPort(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return 0;
            }
            return Integer.parseInt(Files.readString(file).trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /** 其它版本槽已记录的端口（不含当前文件）。 */
    private static java.util.Set<Integer> otherSlotPorts(Path self) {
        java.util.Set<Integer> ports = new java.util.HashSet<>();
        Path versionsDir = self.getParent() == null ? null : self.getParent().getParent();
        if (versionsDir == null || !Files.isDirectory(versionsDir)) {
            return ports;
        }
        try (var stream = Files.walk(versionsDir, 2)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith("port") || file.equals(self)) {
                    continue;
                }
                int port = readPort(file);
                if (port > 0) {
                    ports.add(port);
                }
            }
        } catch (IOException ignored) {
            // 读不到就按没有保留端口处理，端口冲突由 portFree 兜底
        }
        return ports;
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
            state.stages.add(StageResult.failed("workspace", 1, ErrorCode.ENV_NOT_PROMOTED.code(),
                    "本机部署需要项目本地工作区：请先在「接入项目」选择本地目录", 0));
            state.status = StageStatus.FAILED;
            return;
        }
        state.deployTargetUsed = DeployTarget.HOST.wireName();
        long start = System.nanoTime();
        if (state.deployTagged) {
            state.log.append("[dev] 开发环境只有单一运行槽：本次忽略 tag，仍覆盖上一轮本机进程\n");
        }
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
            failLocalDev(state, "workspace", ErrorCode.ENV_NOT_PROMOTED.code(), "无法创建部署目录：" + e.getMessage(), null);
            return;
        }

        // 1. 后端构建
        Path backendRoot = detectBackendRoot(repo);
        if (backendRoot == null) {
            failLocalDev(state, "build", ErrorCode.ENV_NOT_PROMOTED.code(),
                    "未找到后端模块（仓库根或一级子目录需要 pom.xml）", null);
            return;
        }
        long buildStart = System.nanoTime();
        Path mavenLog = work.resolve("maven.log");
        int exit = localDevHost.mvnPackage(backendRoot, mavenLog);
        if (exit != 0) {
            String tail = localDevHost.logTail(mavenLog);
            // mvn 带 -q，明细全在日志里；不把首条 [ERROR] 捞进摘要，面板上就只剩一个退出码
            failLocalDev(state, "build", ErrorCode.ENV_NOT_PROMOTED.code(),
                    "后端构建失败：" + commandReason(exit, "mvn") + firstErrorSuffix(tail), tail);
            return;
        }
        Path jar = findRunnableJar(backendRoot);
        if (jar == null) {
            String tail = localDevHost.logTail(mavenLog);
            failLocalDev(state, "build", ErrorCode.ENV_NOT_PROMOTED.code(),
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
                    failLocalDev(state, "frontend", ErrorCode.CONFIRM_REQUIRED.code(),
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
                failLocalDev(state, "health", ErrorCode.HEALTH_CHECK_FAILED.code(), "后端探活未通过：" + liveness, null);
                return;
            }
            state.log.append("[health] 后端未提供 /actuator/health/liveness，已退化为 "
                    + backendBase + "/ 探活（未校验依赖）\n");
        }
        if (result.frontendPort() > 0 && !probeLocalDev(state, result.endpoint(), 20)) {
            failLocalDev(state, "health", ErrorCode.HEALTH_CHECK_FAILED.code(),
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
        state.stages.add(StageResult.failed(stageId, 1, code == null ? ErrorCode.ENV_NOT_PROMOTED.code() : code,
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
    static Path findRunnableJar(Path backendRoot) {
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
            throw new BuildException(ErrorCode.BUILD_INVALID_STATE, "git worktree add " + state.gitSha
                    + " 失败：" + add.result().errorSummary()
                    + "（若为最新提交，请先在项目详情里执行「同步到平台」）");
        }
        state.log.append("[checkout] worktree ").append(state.gitSha).append('\n');
        state.stages.add(StageResult.success("checkout", 0, List.of(),
                (System.nanoTime() - start) / 1_000_000L));
    }

    /**
     * 构建终态后清理本次 worktree（失败不影响主流程）。
     *
     * <p>Windows 上 {@code git worktree remove --force} 常因杀软/编辑器/残留句柄占用
     * node_modules、target 而 exit 255。这里不再只信 git：失败后由平台自己递归删除
     * （Java NIO 支持长路径），再 {@code git worktree prune} 注销登记；仍然失败只告警，
     * 不影响已经成功的运行结果。
     */
    private void cleanupWorktree(Path bare, Path runDir, RunState state) {
        ActionResult rm = host.run(bare, spec("checkout", List.of(
                List.of("git", "worktree", "remove", "--force", posix(runDir)))));
        if (rm.result().status() == StageStatus.SUCCEEDED) {
            state.log.append("[cleanup] worktree removed\n");
            return;
        }
        state.log.append("[cleanup] git worktree remove 失败（")
                .append(rm.result().errorSummary())
                .append("），改为删除目录并 prune 注销登记\n");
        boolean deleted = deleteWorktreeTree(runDir);
        ActionResult prune = host.run(bare, spec("checkout",
                List.of(List.of("git", "worktree", "prune"))));
        boolean pruned = prune.result().status() == StageStatus.SUCCEEDED;
        if (deleted && pruned) {
            state.log.append("[cleanup] 构建现场已清理（prune 已注销登记）\n");
        } else if (deleted) {
            state.log.append("[cleanup] 目录已删除，但 prune 失败：")
                    .append(prune.result().errorSummary()).append('\n');
        } else {
            state.log.append("[cleanup] ⚠️ 构建现场未完全清理（不影响运行结果）：")
                    .append(runDir).append(" · 删除历史时会再次尝试\n");
        }
    }

    /** 递归删除目录（Windows 下清理被短暂占用的文件）：最多重试 3 轮，清只读属性。 */
    private boolean deleteWorktreeTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return true;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                List<Path> paths;
                try (var stream = Files.walk(dir)) {
                    paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
                }
                for (Path path : paths) {
                    deleteOne(path);
                }
                if (!Files.exists(dir)) {
                    return true;
                }
            } catch (IOException e) {
                // 文件仍被占用：短暂等待后下一轮重试
            }
            try {
                Thread.sleep(400L * (attempt + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return !Files.exists(dir);
    }

    private static void deleteOne(Path path) throws IOException {
        try {
            Files.deleteIfExists(path);
        } catch (java.nio.file.AccessDeniedException e) {
            // Windows 只读属性会让删除失败：清掉再试一次
            try {
                Files.setAttribute(path, "dos:readonly", false);
            } catch (RuntimeException | IOException ignored) {
                // 非 Windows 或不支持该属性：保持原始异常
            }
            Files.deleteIfExists(path);
        }
    }

    private boolean runStage(RunState state, PipelineDefinition.StageDef stage,
                             Path runDir, Map<String, String> vars) {
        if (state.forceTerminated) {
            return false;
        }
        long start = System.nanoTime();
        state.currentStageId = stage.id();
        state.currentStageIndex = state.stages.size();
        state.stageStartedAt = System.currentTimeMillis();
        // 看护总预算按实际配方超时累加（内置阶段按默认 5m 计）
        state.maxRunMillis += STAGE_TIMEOUT.toMillis();
        state.log.append('[').append(stage.id()).append("] 阶段开始 · 第 ")
                .append(state.currentStageIndex + 1).append('/').append(state.stageTotal)
                .append(" 阶段\n");
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
                    () -> new BuildException(ErrorCode.BUILD_INVALID_INPUT, "未知动作：" + stage.action()));
            // 配方根 = 定义所在的根：仓库定义 → 检出目录；平台定义 → 平台目录。
            // 命令本身仍在 stageDir（检出目录 / 子目录）里执行。
            Optional<ActionSpec> resolved = recipes.resolve(state.recipeRoot, action);
            if (resolved.isEmpty()) {
                state.stages.add(StageResult.failed(stage.id(), 1, ErrorCode.ARGV_INVALID.code(),
                        "动作 " + action.wireName() + " 没有配方", elapsed(start)));
                return false;
            }
            ActionSpec original = resolved.get();
            ActionSpec substituted = substitute(original, vars);
            state.maxRunMillis += Math.max(0, substituted.timeout().toMillis()
                    - STAGE_TIMEOUT.toMillis() + 60_000L);
            ActionRunner runner = selectRunner(state, stage, substituted);
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
            // 真实执行的命令：容器模式展开成 docker run …。只显示配方里的 mvn -q 时，
            // 用户无法判断到底跑在宿主还是容器、用的哪个镜像、卡在哪一步。
            if (runner instanceof DockerActionRunner dockerRunner
                    && !substituted.steps().isEmpty()) {
                List<String> effective = dockerRunner.sandbox()
                        .wrap(stageDir, substituted.steps().get(0));
                state.log.append('[').append(stage.id()).append("] 实际命令: ")
                        .append(renderCommand(effective, shell)).append('\n');
            }
            state.log.append('[').append(stage.id()).append("] 预算 ")
                    .append(substituted.timeout().toMinutes()).append("m · 开始执行\n");
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
            state.artifacts.addAll(primaryArtifacts(stage, result.result().artifacts(), runDir));

            if (result.result().status() == StageStatus.SUCCEEDED
                    && "image_package".equals(stage.action())) {
                String tag = vars.get("tag");
                state.artifacts.add(new ArtifactRef("image", tag,
                        imageDigest(runDir, tag), imageSize(runDir, tag),
                        Map.of("runId", state.runId,
                                "version", versionLabel(state),
                                "localImage", tag)));
            }
            return result.result().status() == StageStatus.SUCCEEDED;
        } catch (Exception e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            state.stages.add(StageResult.failed(stage.id(), 1, "E-2xx", why, elapsed(start)));
            return false;
        } finally {
            state.currentStageId = null;
            state.stageStartedAt = 0;
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
        // verify（只检查构建、不部署）不归档：产物只是本次构建证据，复制一份没有部署价值，
        // 却会把每个模块的 jar（含几十 MB 的胖 jar）留在盘上。保留路径/摘要，标记未归档。
        if ("verify".equals(state.pipelineId)) {
            List<ArtifactRef> metadata = state.artifacts.stream()
                    .map(a -> "file".equals(a.kind())
                            ? new ArtifactRef(a.kind(), a.ref(), a.digest(), a.sizeBytes(),
                                    Map.of("archived", "false"))
                            : a)
                    .toList();
            state.artifacts.clear();
            state.artifacts.addAll(metadata);
            state.log.append("[artifacts] 检查构建不归档 ").append(metadata.size())
                    .append(" 个文件产物（仅记录路径与摘要）\n");
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

    /**
     * 镜像保留策略：每个 (项目, 环境) 只保留最近 {@link #KEEP_IMAGES} 个已部署版本。
     *
     * <p>账本与本地镜像一起收敛：先按账本挑出过期版本，再 {@code docker rmi -f}；
     * 删镜像失败（Docker 不可用 / 仍被容器引用）只记日志，不阻断本次发布。
     * 当前版本（账本第一条）永远保留。
     */
    private void pruneOldImages(RunState state) {
        try {
            List<ImageVersion> rows = imageVersions.list(state.projectId, state.env);
            if (rows.size() <= KEEP_IMAGES) {
                return;
            }
            List<ImageVersion> stale = rows.subList(KEEP_IMAGES, rows.size());
            int removed = 0;
            for (ImageVersion v : stale) {
                BoundedCommand.Outcome outcome = BoundedCommand.run(
                        dockerCli.cmd("rmi", "-f", v.tag()), 20_000L);
                if (!outcome.ok()) {
                    state.log.append("[image] 清理镜像失败（保留账本外记录不占空间）：")
                            .append(v.tag()).append(" — ")
                            .append(shorten(outcome.detail().isBlank()
                                    ? outcome.output().trim() : outcome.detail()))
                            .append('\n');
                } else {
                    removed++;
                }
                // 账本只保留最近 KEEP_IMAGES 个：镜像没删掉也要移出，否则列表无限增长
                imageVersions.remove(state.projectId, state.env, v.tag());
            }
            state.log.append("[image] 已清理 ").append(removed).append(" 个历史镜像，账本保留最近 ")
                    .append(KEEP_IMAGES).append(" 个版本\n");
        } catch (Exception e) {
            state.log.append("[image] 镜像清理跳过：").append(e.getMessage()).append('\n');
        }
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
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
     * 产物收口：多模块 Maven 会把每个子模块的 jar 都算成产物，但真正可部署的
     * 只有含 {@code BOOT-INF/} 的胖 jar。其余是构建中间物，不展示、不归档。
     *
     * <p>非 Spring Boot 工程（找不到胖 jar）保持原行为，避免误判「没有产物」。
     */
    private List<ArtifactRef> primaryArtifacts(PipelineDefinition.StageDef stage,
                                               List<ArtifactRef> artifacts, Path runDir) {
        List<ArtifactRef> prefixed = prefixArtifacts(artifacts, stage.workdir());
        if (!"backend_build".equals(stage.action())) {
            return prefixed;
        }
        List<ArtifactRef> jars = prefixed.stream()
                .filter(a -> "file".equals(a.kind()) && a.ref().endsWith(".jar"))
                .filter(a -> !a.ref().endsWith("-sources.jar"))
                .filter(a -> !a.ref().endsWith("-javadoc.jar"))
                .toList();
        if (jars.size() <= 1) {
            return prefixed;
        }
        List<ArtifactRef> runnable = jars.stream()
                .filter(a -> isExecutableJar(runDir.resolve(a.ref()).normalize()))
                .toList();
        if (runnable.isEmpty()) {
            return prefixed;
        }
        List<ArtifactRef> others = prefixed.stream()
                .filter(a -> !("file".equals(a.kind()) && a.ref().endsWith(".jar")))
                .toList();
        List<ArtifactRef> out = new ArrayList<>(others);
        out.addAll(runnable);
        return out;
    }

    /**
     * 选择执行器：host 走宿主；container 且声明了 {@code image} 时，
     * 用该 stage 专属工具链镜像新建一个沙箱（同一套隔离参数，只换镜像）。
     */
    /** 必须在宿主上执行的动作：它们直接调用宿主 Docker 守护进程。 */
    static boolean needsHostDocker(ActionId action) {
        return action == ActionId.IMAGE_PACKAGE;
    }

    /**
     * 配方是否可信任到能在宿主执行。
     *
     * <p>信任边界看的是**定义来源**而不是配方文件本身：平台侧定义（平台 ci/actions.yml、
     * 固化脚本）里的配方由平台维护；仓库定义里的 ci/actions.yml / Taskfile / Makefile
     * 属于仓库内容，给了宿主执行权就等于沙箱被绕过。内置配方始终可信。
     */
    static boolean trustedForHost(ActionSpec spec, PipelineDefinitionResolver.Source definitionSource) {
        if (spec == null) {
            return false;
        }
        ActionSource source = spec.source();
        if (source == null || source.kind() == ActionSource.Kind.BUILTIN) {
            return true;
        }
        return definitionSource != null && definitionSource != PipelineDefinitionResolver.Source.REPO;
    }

    private ActionRunner selectRunner(RunState state, PipelineDefinition.StageDef stage, ActionSpec spec) {
        ActionId action = ActionId.fromWire(stage.action()).orElse(null);
        if (needsHostDocker(action)) {
            // 镜像打包必须用宿主 Docker：沙箱镜像（maven/node …）里既没有 docker CLI，
            // 也没有挂 docker.sock，包进容器只会得到 exit 127（command not found）。
            if (trustedForHost(spec, state.definitionSource)) {
                state.log.append('[').append(stage.id())
                        .append("] 该阶段需要宿主 Docker（构建镜像），在宿主执行\n");
                return host;
            }
            // 仓库自己写的 docker 配方不给宿主执行权：那是绕过沙箱的通道。
            throw new BuildException(ErrorCode.BUILD_INVALID_STATE, "阶段 " + stage.id() + "（" + stage.action()
                    + "）需要宿主 Docker，但配方来自仓库（" + spec.source().ref()
                    + "）。请改用平台侧 ci/actions.yml 的配方，或删除仓库里的同名配方");
        }
        if ("host".equals(stage.runner())) {
            // 仓库里的 ci/pipeline.yml 可以写 runner: host。容器模式下若照做，
            // 等于给仓库内容一条跳出沙箱的通道，所以这里只在显式 process 模式生效。
            if (sandboxed instanceof DockerActionRunner) {
                return sandboxed;
            }
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
        if (!"prod".equals(state.env)) {
            // dev/test 本来就是迭代环境：要求"改 YAML 先合并默认分支"只会让 agent 无法自愈。
            // 受审约束只保留在生产发布上。
            state.log.append("[guard] 环境=").append(state.env)
                    .append("，跳过'部署配置与默认分支一致'校验（仅 prod 要求）\n");
            return true;
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
            state.stages.add(StageResult.failed(stageId, 1, ErrorCode.DEPLOY_SECTION_MISSING.code(),
                    "pipeline.yml 缺少 deploy 段（target/port），无法判断部署目标", elapsed(start)));
            return false;
        }
        String desired = state.deployTargetOverride != null && !state.deployTargetOverride.isBlank()
                ? state.deployTargetOverride : spec.target();
        DeployTarget target = DeployTarget.fromWire(desired).orElse(null);
        if (target == null) {
            state.stages.add(StageResult.failed(stageId, 1, ErrorCode.DEPLOY_SECTION_MISSING.code(),
                    "未知部署目标：" + desired + "（可选 docker / host / server）", elapsed(start)));
            return false;
        }

        String currentImage = state.artifacts.stream()
                .filter(a -> "image".equals(a.kind()))
                .reduce((first, second) -> second)
                .map(ArtifactRef::ref)
                .orElse(null);
        String previous = imageVersions.current(state.projectId, state.env)
                .map(ImageVersion::tag).orElse(null);
        String image = rollback ? previous : currentImage;
        if (rollback && (image == null || image.isBlank())) {
            // 首次发布失败时会走到回滚：没有上一版不是错误，跳过即可，
            // 否则一次失败会多出一条 E-507，误导成"第二个故障"。
            state.log.append('[').append(stageId)
                    .append("] 本项目尚无成功发布记录，无可回滚版本，跳过回滚\n");
            state.stages.add(StageResult.success(stageId, 0, List.of(), elapsed(start)));
            return true;
        }

        EffectiveEnvironment effective = envStore == null
                ? null : envStore.effective(state.projectId, state.env);
        state.sensitiveValues = secretValues(effective);
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
        // 但触发参数显式指定的目标是操作者的明确意图，不能被自愈悄悄改写：
        // 只记录风险并交给部署器如实失败（例如 HOST 找不到可运行 jar）。
        boolean explicitTarget = !rollback && state.deployTargetOverride != null
                && !state.deployTargetOverride.isBlank();
        DeployTarget reconciled = reconcileTarget(state, target, image, rollback, runDir, spec,
                explicitTarget);
        if (reconciled == null) {
            state.stages.add(StageResult.failed(stageId, 1, ErrorCode.DEPLOY_TARGET_UNDETERMINED.code(),
                    "无法确定部署目标：本次既没有镜像也没有可运行 jar。"
                            + "Java 项目请确认构建产物 target/*.jar；前端静态产物请在 deploy 段选择 docker",
                    elapsed(start)));
            return false;
        }
        target = reconciled;

        // 上一版镜像可能已被清理（删除构建历史 / 手动清理）：回滚是失败后的兜底，
        // 兜底做不到不应再报一条 E-501，否则一次构建失败会被展示成两个故障。
        if (rollback && target == DeployTarget.DOCKER && !dockerDeployer.imageExists(runDir, image)) {
            state.log.append('[').append(stageId)
                    .append("] 上一版镜像 ").append(image)
                    .append(" 已不在本机（可能已被清理），跳过回滚；本次运行状态不受影响\n");
            state.stages.add(StageResult.success(stageId, 0, List.of(), elapsed(start)));
            return true;
        }

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
                state.stages.add(StageResult.failed(stageId, 1, ErrorCode.ENV_PREPARE_FAILED.code(),
                        provision.message() + "。请检查本机 Docker 是否可用，或在「设置 → 环境」手动处理",
                        elapsed(start)));
                return false;
            }
            effective = envStore.effective(state.projectId, state.env);
            state.sensitiveValues = secretValues(effective);
            deployEnv = effective.values();
            network = effective.network();
        }

        if (effective != null && !effective.missingSecrets().isEmpty()) {
            state.stages.add(StageResult.failed(stageId, 1, ErrorCode.SECRET_UNRESOLVED.code(),
                    "密钥引用无法解析：" + String.join("、", effective.missingSecrets())
                            + "。请先在「设置 → 项目环境 → 密钥」创建或轮换对应密钥",
                    elapsed(start)));
            return false;
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
            state.stages.add(StageResult.failed(stageId, 1, ErrorCode.ENV_REQUIRED_MISSING.code(),
                    "部署前环境配置检查未通过：环境「" + state.env + "」缺少必填环境变量 "
                            + String.join("、", missingEnv)
                            + "。请到「设置 → 环境」或「项目覆盖」填写后重试（本次未启动容器）",
                    elapsed(start)));
            state.log.append("[deploy] ").append(ErrorCode.ENV_REQUIRED_MISSING.code())
                    .append(" 预检失败：缺少 ").append(String.join("、", missingEnv))
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
                state.stages.add(StageResult.failed(stageId, 1, ErrorCode.MIDDLEWARE_START_FAILED.code(),
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
        String slot = rollback ? null : deploySlot(state);
        boolean tagged = slot != null;
        if (target == DeployTarget.DOCKER) {
            String oldContainer = DockerDeployer.containerName(state.projectId, state.env, slot);
            // 升级前的命名没有环境后缀：一并清理，否则它会一直占着期望端口。
            // 打 tag 的版本只清理同名容器，绝不碰默认槽（否则旧版本会被新版本顶掉）。
            String legacyContainer = DockerDeployer.containerName(state.projectId);
            List<String> toRemove = tagged
                    ? List.of(oldContainer)
                    : (legacyContainer.equals(oldContainer)
                            ? List.of(oldContainer) : List.of(oldContainer, legacyContainer));
            for (String old : toRemove) {
                ActionResult stopped = host.run(runDir, spec("deploy-stop-old",
                        List.of(dockerCli.cmd("rm", "-f", old))), () -> false);
                if (stopped.result().status() == StageStatus.SUCCEEDED) {
                    state.log.append("[deploy] 已停止上一版容器 ").append(old).append('\n');
                }
            }
            // docker rm 后宿主端口由 docker-proxy 异步释放：短暂等待可避免端口无谓漂移
            int expectedPort = tagged
                    ? readPort(versionSlotDir(state).resolve("port")) : spec.port();
            for (int i = 0; i < 20 && expectedPort > 0 && !portFree(expectedPort); i++) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        int hostPort = tagged
                ? allocateTaggedPort(state, spec.port(), spec.containerPort(), null)
                : allocateHostPort(state, target, spec.port(), spec.containerPort());

        // 运行环境快照（放在端口分配后，快照里能带上本次租约端口）：
        // 只存来源、key 与值哈希，既可追溯"这次用了哪套变量"，又不泄露明文。
        if (effective != null) {
            long secretCount = effective.vars().stream().filter(v -> v.secret()).count();
            String digest = envDigest(effective.digestValues());
            Map<String, String> snapshotValues = effective.digestValues();
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
            snapshotValues.entrySet().stream()
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
                state.env,
                // 版本号只认用户在流水线里填的 / 打 tag 的版本：没填就是 latest
                rollback ? null : state.deployVersion,
                tagged,
                spec.volume());

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
        state.deployEndpoint = result.endpoint();
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
        // 证据链：产物里固化实际端口/容器端口/探活路径/镜像，排障时不必再从日志里反推
        int evidencePort = actualPort > 0 ? actualPort : hostPort;
        Map<String, String> meta = new java.util.LinkedHashMap<>();
        meta.put("target", target.wireName());
        meta.put("resource", result.resource() == null ? "" : result.resource());
        meta.put("port", String.valueOf(evidencePort));
        meta.put("containerPort", String.valueOf(spec.containerPort()));
        meta.put("healthPath", spec.healthPath() == null ? "" : spec.healthPath());
        if (image != null && !image.isBlank()) {
            meta.put("image", image);
        }
        List<ArtifactRef> artifacts = List.of(new ArtifactRef("deployment",
                result.endpoint(), null, 0, Map.copyOf(meta)));
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
                                         PipelineDefinition.DeploySpec spec,
                                         boolean explicitTarget) {
        if (rollback) {
            return requested; // 回滚沿用已有产物，不按本次构建判断
        }
        boolean hasImage = image != null && !image.isBlank();
        boolean hasJar = hasRunnableJar(runDir, spec.artifact());
        if (!hasImage && !hasJar && spec.services().isEmpty()) {
            return null; // E-504：没有任何可部署产物，显式目标也不能凭空部署
        }
        if (explicitTarget) {
            state.log.append("[deploy] 触发参数显式指定目标 ").append(requested.wireName())
                    .append("，跳过产物自愈（与产物不匹配时由部署器如实报错）\n");
            return requested;
        }
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
                // 以前这里会把 docker 静默改成 host：一次配置/配方缺陷从此被"自愈"掩盖，
                // 每次回放都发 host 进程，镜像链路永远不落地（镜像 tag 也一直不存在）。
                // 现在保持 docker，由部署器如实报 E-708，日志里直接指向缺 image_package。
                state.log.append("[deploy] 部署目标 docker 但本次没有镜像：不再降级为 host，")
                        .append("交由部署器报错（请检查 image_package 阶段）\n");
            }
            return DeployTarget.DOCKER;
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
        if (Files.isDirectory(target)) {
            try (var stream = Files.list(target)) {
                boolean any = stream.anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".jar") && !name.endsWith("-sources.jar")
                            && !name.endsWith("-javadoc.jar");
                });
                if (any) {
                    return true;
                }
            } catch (java.io.IOException ignored) {
                // 根 target 读不了时继续按子模块找
            }
        }
        // Maven 聚合工程：jar 在子模块 target 下
        return findRunnableJar(runDir) != null;
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
            state.stages.add(StageResult.failed(stageId, 1, ErrorCode.ROLLBACK_VERSION_MISSING.code(),
                    "多服务部署暂不支持回滚：请重新发布或手动回退镜像", elapsed(start)));
            return false;
        }
        String namespace = template == null ? "agent-" + state.env : template.namespace();
        String net = network != null && !network.isBlank() ? network : namespace + "-net";
        StringBuilder netLog = new StringBuilder();
        if (!dockerDeployer.ensureNetwork(runDir, net, netLog)) {
            state.stages.add(StageResult.failed(stageId, 1, ErrorCode.DEPLOY_START_FAILED.code(),
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
            // 打 tag 的版本用独立端口段（避免撞默认槽），端口记录在版本槽目录里
            int hostPort = state.deployTagged
                    ? allocateTaggedPort(state, desired, service.containerPort(), service.name())
                    : allocateHostPort(state, DeployTarget.DOCKER, desired,
                            service.containerPort());
            Map<String, String> svcEnv = deployEnv;
            if ("frontend".equals(service.name()) && backend != null) {
                svcEnv = new java.util.LinkedHashMap<>(deployEnv);
                svcEnv.put("BACKEND_PORT", String.valueOf(backend.containerPort()));
            }
            DeployResult result = dockerDeployer.deployService(runDir, state.projectId, state.env,
                    state.runId, state.gitSha, namespace, service.name(), service.image(),
                    hostPort, service.containerPort(), net, svcEnv, service.volumePath(),
                    state.deployVersion, state.deployTagged);
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
                            "resource", result.resource() == null ? "" : result.resource(),
                            "hostPort", String.valueOf(hostPort),
                            "containerPort", String.valueOf(service.containerPort()),
                            "image", service.image() == null ? "" : service.image()));
            artifacts.add(artifact);
            state.artifacts.add(artifact);
            if ("frontend".equals(service.name()) || primaryEndpoint == null) {
                primaryEndpoint = result.endpoint();
                primaryPort = hostPort;
                primaryResource = result.resource();
            }
        }
        state.deployTargetUsed = "docker";
        state.deployEndpoint = primaryEndpoint;
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
                throw new BuildException(ErrorCode.BUILD_INVALID_STATE, "端口分配失败：从 " + desired
                        + " 起 200 个端口都不可用（宿主端口耗尽）");
            }
            state.log.append("[port] ").append(candidate).append(" 被占用 → 分配 ").append(found)
                    .append('\n');
            candidate = found;
        } else if (lease != null && lease.hostPort() != desired) {
            // 端口有两个来源（定义 vs 租约）：必须把差异写清楚，否则工具/日志里
            // ci_definition=8182、实际部署=8183，诊断时无法判断哪个才是真的
            state.log.append("[port] 沿用租约端口 ").append(candidate)
                    .append("（定义端口 ").append(desired)
                    .append(" 与租约不一致，容器端口 ").append(containerPort)
                    .append("；如需改回请重置端口租约）\n");
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
        throw new BuildException(ErrorCode.BUILD_INVALID_STATE, "端口分配失败：从 " + desired + " 起找不到可用端口");
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

    /** 本次会注入的密钥明文（只取 secret 变量；空值不参与），用于运行日志值级脱敏。 */
    private static Set<String> secretValues(EffectiveEnvironment effective) {
        if (effective == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (EnvVariable var : effective.vars()) {
            if (!var.secret()) {
                continue;
            }
            String value = effective.values().get(var.key());
            if (value != null && !value.isBlank()) {
                out.add(value);
            }
        }
        return Set.copyOf(out);
    }

    /**
     * 把健康检查 URL 的 scheme/host/port 换成部署 endpoint，只保留原 URL 的 path/query。
     * 用于 SERVER 目标：pipeline 里的 127.0.0.1 只是占位。
     */
    private static String rebase(String url, String endpoint) {
        try {
            URI base = URI.create(endpoint);
            URI path = URI.create(url);
            if (base.getHost() == null) {
                return url;
            }
            return new URI(base.getScheme() == null ? "http" : base.getScheme(),
                    base.getUserInfo(), base.getHost(), base.getPort(),
                    path.getRawPath(), path.getRawQuery(), null).toString();
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

    /**
     * 健康检查等待窗口：默认按「次数 × 间隔」给足（docker 3 分钟 / host 1 分钟），
     * 但不超过运行剩余预算。看护线程在总预算耗尽时会直接请求取消并强制终态，
     * 健康检查若还继续等到窗口结束，用户看到的是一条 E-302「超时且无法中断」，
     * 而真正的失败原因（容器反复重启、端口不对）被完全盖住。
     *
     * <p>下限 {@value #MIN_HEALTH_WINDOW_MS}ms：剩余预算已耗尽时也至少探一轮，
     * 让失败摘要里带上真实证据（HTTP body / 容器退出码 / 容器日志）。
     */
    static long healthWindowMillis(int attempts, long sleepMs, long remainingMs) {
        long planned = Math.max(0, attempts * (sleepMs + HEALTH_PROBE_TIMEOUT_MS));
        long available = remainingMs - HEALTH_PROBE_TIMEOUT_MS * 2L;
        if (available >= planned) {
            return planned;
        }
        return Math.min(planned, Math.max(MIN_HEALTH_WINDOW_MS, available));
    }

    private boolean health(RunState state, PipelineDefinition.StageDef stage, long start) {
        String url = stage.url();
        if (url == null || url.isBlank()) {
            state.stages.add(StageResult.failed(stage.id(), 1, ErrorCode.ARGV_INVALID.code(), "health 缺少 url", elapsed(start)));
            return false;
        }
        if (state.deployHostPort > 0) {
            String actual = withPort(url, state.deployHostPort);
            if (!actual.equals(url)) {
                state.log.append("[health] 使用部署端口 ").append(state.deployHostPort)
                        .append("，健康检查 ").append(actual).append('\n');
                url = actual;
            }
        }
        // SERVER 目标：pipeline 里写的 127.0.0.1 是"服务器本机"的占位，探活必须发到
        // 部署器返回的真实 endpoint（host/port/scheme），否则永远连的是平台自己。
        if ("server".equals(state.deployTargetUsed)
                && state.deployEndpoint != null && !state.deployEndpoint.isBlank()) {
            String actual = rebase(url, state.deployEndpoint);
            if (!actual.equals(url)) {
                state.log.append("[health] 服务器目标，健康检查 URL ").append(url)
                        .append(" → ").append(actual).append('\n');
                url = actual;
            }
        }
        String resource = deployedResource(state);
        boolean dockerTarget = "docker".equals(state.deployTargetUsed);
        // docker 容器若自带 HEALTHCHECK（前端 + 后端接口 + actuator 的组合探活），
        // 只认 HTTP 200 会在 nginx 静态页先起来时假通过（后端还在连库/迁移）。
        // 因此 docker 目标要等到容器 healthy 才算通过；等待窗口放宽到约 3 分钟（应用启动 + Flyway）。
        // HOST 目标同样要给 Spring Boot 启动留时间：之前只等 6s，后端其实已起来却被判失败。
        int attempts = dockerTarget && resource != null ? 90 : 60;
        long sleepMs = dockerTarget && resource != null ? 2000L : 1000L;
        // 等待窗口不能超过运行剩余预算：看护线程在总预算耗尽时直接请求取消，
        // 再把运行强制判成 E-302「超时且无法中断」，真正的原因（端口不通、容器反复重启）
        // 就被这条看护失败盖住，日志里看不到健康检查的任何诊断。
        long remainingMs = state.maxRunMillis - (System.currentTimeMillis() - state.createdAt);
        long windowMs = healthWindowMillis(attempts, sleepMs, remainingMs);
        if (windowMs < attempts * sleepMs) {
            state.log.append("[health] 等待窗口按运行剩余预算收敛为 ")
                    .append(windowMs / 1000).append("s\n");
        }
        long deadlineNanos = System.nanoTime() + windowMs * 1_000_000L;
        boolean waitingHealth = false;
        String homeIssue = null;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        String body = "";
        String exitInfo = null;
        for (int i = 0; i < attempts && (i == 0 || System.nanoTime() < deadlineNanos); i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMillis(HEALTH_PROBE_TIMEOUT_MS)).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                body = response.body();
                boolean statusOk = stage.expected() == 0
                        ? response.statusCode() >= 200 && response.statusCode() < 300
                        : response.statusCode() == stage.expected();
                if (statusOk && healthBodyOk(url, body)
                        && containerReady(state, resource, dockerTarget)) {
                    // 接口活着不代表页面可用：根路径 5xx 说明静态托管/反代配错，补一次首页冒烟。
                    homeIssue = homepageIssue(client, url);
                    if (homeIssue == null) {
                        state.log.append("[health] ").append(response.statusCode()).append(' ')
                                .append(body.length() > 500 ? body.substring(0, 500) + "…" : body).append('\n');
                        state.stages.add(StageResult.success(stage.id(), 0, List.of(), elapsed(start)));
                        return true;
                    }
                    state.log.append("[health] 首页冒烟未通过：").append(homeIssue).append('\n');
                    break;
                }
                if (statusOk && !waitingHealth) {
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
        // docker 才需要看容器日志/清理容器；HOST 目标探活对象是宿主机进程，
        // 这里再走 docker 只会得到误导性的 "No such container: <sha>"。
        String diag = dockerTarget ? healthDiagnostics(state, stage.id()) : null;
        if (dockerTarget) {
            removeFailedContainer(state, stage.id());
        }
        String summary = "健康检查未通过：" + url
                + (homeIssue == null ? "" : "（首页：" + homeIssue + "）")
                + (exitInfo == null ? " body=" + brief(body) : "（容器 " + exitInfo + "）")
                + (diag == null ? "" : "；容器日志：" + diag);
        state.stages.add(StageResult.failed(stage.id(), 1, ErrorCode.ARTIFACT_MISSING.code(), summary, elapsed(start)));
        return false;
    }

    /**
     * 首页冒烟：健康接口活着不代表页面可用——静态托管或反向代理配错时根路径会 500。
     *
     * <p>只拦明确的服务端错误（5xx）。404/401/403 视为「没有首页」（纯 API 工程、
     * 或鉴权先于静态资源），3xx 交给浏览器；探活地址本身就是根路径时无需重复探。
     *
     * @return 失败原因；通过时返回 null
     */
    static String homepageIssue(HttpClient client, String healthUrl) {
        try {
            URI health = URI.create(healthUrl);
            URI root = new URI(health.getScheme(), null, health.getHost(), health.getPort(),
                    "/", null, null);
            if (root.equals(health)) {
                return null;
            }
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(root).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 500 ? "/ 返回 HTTP " + response.statusCode() : null;
        } catch (Exception noHomepage) {
            // 首页取不到但健康接口已通过：不据此判失败（部分工程只暴露接口）
            return null;
        }
    }

    /**
     * actuator 的 {@code /health} 可能返回 HTTP 200 但 status=DOWN（如 readiness 未就绪），
     * 只看状态码会假通过；非 actuator 探测地址不做 body 约束。
     */
    static boolean healthBodyOk(String url, String body) {
        if (url == null || !url.contains("/actuator/health") || body == null) {
            return true;
        }
        String compact = body.replaceAll("\\s+", "");
        return !compact.contains("\"status\":\"DOWN\"")
                && !compact.contains("\"status\":\"OUT_OF_SERVICE\"");
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
                dockerCli.cmd("inspect", "-f",
                        "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}", resource)),
                PROBE_TIMEOUT), () -> false);
        if (probe.result().status() != StageStatus.SUCCEEDED) {
            return true; // 探测失败不阻塞：HTTP 已通即认为服务可用
        }
        String status = probe.logTail() == null ? "" : probe.logTail().trim();
        return status.isEmpty() || "none".equals(status) || "healthy".equals(status);
    }

    /** docker inspect 容器状态：已退出（exited/dead）返回状态+退出码；运行中或探测失败返回 null。 */
    private String containerExitInfo(RunState state, String resource) {
        ActionResult probe = host.run(Path.of("."), spec("health-probe", List.of(
                dockerCli.cmd("inspect", "-f",
                        "{{.State.Status}} {{.State.ExitCode}}", resource)), PROBE_TIMEOUT), () -> false);
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
                dockerCli.cmd("rm", "-f", resource)), CLEANUP_TIMEOUT), () -> false);
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
                dockerCli.cmd("logs", "--tail", "20", resource)), CLEANUP_TIMEOUT), () -> false);
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
            String good = imageVersions.current(state.projectId, state.env)
                    .map(ImageVersion::tag).orElse(null);
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
                dockerCli.cmd("image", "inspect", "--format", "{{.Id}}", tag))), () -> false);
        if (r.result().status() != StageStatus.SUCCEEDED) {
            return null;
        }
        String line = r.logTail().trim();
        return line.contains(":") ? line.substring(line.lastIndexOf(':') + 1) : line;
    }

    /** 镜像大小（字节）；取不到时返回 0，不影响构建结论。 */
    private long imageSize(Path runDir, String tag) {
        ActionResult r = host.run(runDir, spec("image-size", List.of(
                dockerCli.cmd("image", "inspect", "--format", "{{.Size}}", tag))), () -> false);
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
        return spec(id, steps, STAGE_TIMEOUT);
    }

    /**
     * 带显式预算的引擎内部命令。
     *
     * <p>健康检查 / 清理这类"辅助探测"不能沿用 5 分钟的阶段预算：docker 守护进程
     * 一旦挂起，一次 inspect 就能把健康检查窗口（3 分钟）整个吃掉，看护线程再按总预算
     * 强制终态，真正的失败原因（HTTP 5xx、容器反复重启）全都看不到。
     */
    private ActionSpec spec(String id, List<List<String>> steps, Duration timeout) {
        return new ActionSpec(ActionId.BACKEND_BUILD, steps, List.of(), timeout,
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
            throw new BuildException(ErrorCode.BUILD_INVALID_STATE, "环境变量快照摘要失败", e);
        }
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    md.digest(String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BuildException(ErrorCode.BUILD_INVALID_STATE, "SHA-256 计算失败", e);
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
        // 线程安全：执行线程写阶段日志、看护线程写心跳，两条线程可能同时 append。
        final StringBuffer log = new StringBuffer();
        /**
         * 本次运行注入过的密钥明文：写日志前整段替换。
         * 只放在内存，不落库；空集合表示尚未解析环境。
         */
        volatile Set<String> sensitiveValues = Set.of();
        volatile StageStatus status = StageStatus.PENDING;
        /** 构建序号（项目 + 环境内从 1 开始）：历史顺序与镜像版本 vN。 */
        volatile int seq;
        volatile boolean cancelled = false;
        /** 本次运行的部署目标覆盖（触发参数）；null = 用 pipeline.yml 的 deploy.target。 */
        volatile String deployTargetOverride;
        /** 本次运行已生效的部署目标（回滚复用）。 */
        volatile String deployTargetUsed;
        /** 部署器返回的真实 endpoint（SERVER 健康检查据此改写 host/port）。 */
        volatile String deployEndpoint;
        /** 检查构建是否保留镜像打包（触发参数 verifyImage，默认 false：最贵的一步且与"能不能编过"无关）。 */
        volatile boolean verifyImage;
        /** 按版本部署：复用已存在的镜像 tag，跳过构建/打包只跑 deploy + health。
         * 空 = 正常构建发布。 */
        volatile String deployImage;
        /** 用户填写的版本号（可空）：打 tag 时作为独立版本槽，不打 tag 时作为展示标签。 */
        volatile String deployVersion;
        /** true = 打 tag：新版本独立部署，不覆盖该环境的上一版。 */
        volatile boolean deployTagged;
        /** 发布分支（版本线分支 x.y.0-snapshot）：一个版本线一个分支。 */
        volatile String branch;
        /** 打 tag 结果：PUSHED=远端可见 / LOCAL=仅本地 / FAILED=失败；null=未打标签。 */
        volatile String tagStatus;
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
        /** 自动续跑复用的变量（tag / gitSha）：与本次定义严格一致。 */
        volatile Map<String, String> keptVars;
        /** 同一失败周期只处理一次：避免 [agent]/[autofix] 日志被重复追加。 */
        volatile boolean recoveryDone;
        /** 自动修复进行中：看护跳过运行预算检查（修复动作自身有硬超时）。 */
        volatile boolean autoRepairing;
        volatile int autoRepairAttempts;
        volatile boolean autoRepairResumed;
        volatile String autoRepairLastAction;
        volatile String autoRepairLastResult;
        /** 每个阶段开始前的产物数量：重试时回收该阶段及之后产生的产物，避免重复。 */
        final Map<String, Integer> artifactMark = new java.util.concurrent.ConcurrentHashMap<>();

        // ---- 运行看护 / 进度（由 watchdog 线程与执行线程共享，全部 volatile）----
        /** 上次落盘时间：运行中每 10s 保存一次，崩溃后不会只剩 PENDING 空日志。 */
        volatile long lastSavedAt = System.currentTimeMillis();
        /** 上次日志内容变化时间：静默命令（mvn -q）超过 30s 时补心跳行。 */
        volatile long lastLogChangeAt = System.currentTimeMillis();
        volatile int lastLogLength;
        /** 请求取消的时间：看护据此在 60s 后强制终态。 */
        volatile long cancelRequestedAt;
        /** 本次运行允许的总时长（按实际配方超时累加，另有 5m 宽限）。 */
        volatile long maxRunMillis = WATCHDOG_GRACE_MS;
        /** 看护已强制置终态：执行线程不得再覆盖状态或重复 complete。 */
        volatile boolean forceTerminated;
        volatile String currentStageId;
        volatile int currentStageIndex;
        volatile int stageTotal;
        volatile long stageStartedAt;

        RunSnapshot snapshot() {
            return new RunHistoryStore.RunSnapshot(runId, projectId, env, pipelineId, gitSha,
                    status, createdAt, seq, List.copyOf(stages), List.copyOf(artifacts),
                    ProcessExecutor.redact(log.toString(), sensitiveValues),
                    definitionSource == null ? null : definitionSource.name(), definitionNote,
                    deployVersion, tagStatus, branch);
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
