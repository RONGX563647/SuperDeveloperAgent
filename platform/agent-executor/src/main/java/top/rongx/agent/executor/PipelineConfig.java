package top.rongx.agent.executor;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.port.PipelineEngine;

/**
 * 装配：把「方案 D 的执行器」组合成一条真正的流水线。
 *
 * <p>{@code ci_run} 工具通过 {@link PipelineEngine} 端口触发，
 * 由 {@link BuiltInPipelineEngine} 按 {@code ci/pipeline.yml} 顺序执行。
 */
@Configuration
public class PipelineConfig {

    /** 宿主执行器：用于 git 检出等只需要本地工具链的 stage。 */
    @Bean("hostActionRunner")
    public ProcessActionRunner hostActionRunner() {
        return new ProcessActionRunner();
    }

    /**
     * 平台部署器组合（方案 C）：DOCKER（本机容器）/ HOST（本机 jar）/ SERVER（设置页配置的服务器）。
     *
     * <p>@Primary：避免与单个 Deployer Bean 造成按类型注入歧义。
     */
    @Bean
    @Primary
    public top.rongx.agent.domain.port.Deployer deployers(
            top.rongx.agent.domain.settings.DeployServerRepository servers,
            top.rongx.agent.domain.settings.SshAccess sshAccess,
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        // HOST 部署状态放在平台数据目录（跨运行稳定）：工作树随每次运行重建，
        // 状态目录若落在里面，日志会消失、previous 指针永远为空（回滚 E-507）。
        Path deployRoot = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("deploy");
        return new Deployers(java.util.List.of(
                new DockerDeployer(), new HostDeployer(deployRoot),
                new ServerDeployer(servers, sshAccess)));
    }

    /** SSH 访问实现（设置页测试连接与远程部署共用；known_hosts 留在平台工作区）。 */
    @Bean
    public top.rongx.agent.domain.settings.SshAccess sshAccess(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        Path knownHosts = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("known_hosts");
        return new SshExec(knownHosts);
    }

    /** 应用容器只读巡检：env_runtime_status / app_logs 的运行时事实来源。 */
    @Bean
    public AppRuntimeInspector appRuntimeInspector() {
        return new AppRuntimeInspector();
    }

    /** 中间件运行时：部署前自动启动项目引用的 docker 中间件（设置页也可手动启停）。 */
    @Bean
    public top.rongx.agent.domain.env.EnvRuntime envRuntime(
            top.rongx.agent.domain.env.EnvironmentStore envStore,
            top.rongx.agent.domain.settings.DeployServerRepository servers,
            top.rongx.agent.domain.settings.SshAccess sshAccess) {
        return new DockerEnvRuntime(envStore, servers, sshAccess);
    }

    /** 环境容器自愈：项目在该环境缺中间件时按需创建隔离容器，并注入连接信息。 */
    @Bean
    public top.rongx.agent.domain.env.EnvProvisioner envProvisioner(
            top.rongx.agent.domain.env.EnvironmentStore envStore,
            top.rongx.agent.domain.port.ProjectRepoLocator repoLocator) {
        return new DockerEnvProvisioner(envStore, repoLocator);
    }

    /** 开发环境本机托管（后端 jar + 前端 dist）；登记 ServiceLease 并共用 dev 日志目录。 */
    @Bean
    public LocalDevHostDeployer localDevHostDeployer(
            top.rongx.agent.domain.env.EnvironmentStore envStore,
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        // 与 LocalProcessServiceRuntime 的 stateRoot 对齐：app_logs dev 读同一份 backend.log
        var logRoot = java.nio.file.Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("local");
        return new LocalDevHostDeployer(envStore, logRoot);
    }

    /** 平台侧 deploy 段解析：环境页展示的端口/健康路径与真实部署保持一致。 */
    @Bean
    public top.rongx.agent.domain.port.DeployDefaultsReader deployDefaultsReader(
            top.rongx.agent.domain.port.PipelineDefStore defStore) {
        return new PipelineDeployDefaultsReader(defStore);
    }

    @Bean
    public PipelineEngine pipelineEngine(ActionResolver recipes,
                                         @Qualifier("hostActionRunner") ProcessActionRunner host,
                                         @Qualifier("actionRunner") ActionRunner sandboxed,
                                         @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
                                         @Value("${agent.pipeline.history:true}") boolean persist,
                                         @Value("${agent.pipeline.image-prefix:ci}") String imagePrefix,
                                         top.rongx.agent.domain.port.ProjectRepoLocator repoLocator,
                                         top.rongx.agent.domain.port.Deployer deployers,
                                         top.rongx.agent.domain.port.PipelineDefStore defStore,
                                         top.rongx.agent.domain.port.ProjectScaffolder scaffolder,
                                         top.rongx.agent.domain.port.RecipeStore recipeStore,
                                         top.rongx.agent.domain.settings.ProjectEnvVarRepository envVars,
                                         top.rongx.agent.domain.env.EnvironmentStore envStore,
                                         LocalDevHostDeployer localDevHost,
                                         top.rongx.agent.domain.env.EnvRuntime envRuntime,
                                         top.rongx.agent.domain.env.EnvProvisioner envProvisioner,
                                         org.springframework.beans.factory.ObjectProvider<
                                                 top.rongx.agent.domain.port.PipelineDefAuthor> defAuthor,
                                         org.springframework.beans.factory.ObjectProvider<
                                                 top.rongx.agent.domain.run.RunRecoveryAdvisor> recoveryAdvisor) {
        Path root = Path.of(workspaceRoot);
        RunHistoryStore store = persist ? RunHistoryStore.file(root) : RunHistoryStore.disabled();
        // 定义解析：仓库提交覆盖 > 环境固化脚本 > 平台定义 > 规则生成 > agent 兜底
        PipelineDefinitionResolver resolver = new PipelineDefinitionResolver(
                defStore, scaffolder, defAuthor.getIfAvailable(), recipeStore);
        // 失败自动诊断：用 ObjectProvider 延迟到"运行失败时"才解析，避免
        // pipelineEngine → advisor → ChatService → 工具 → pipelineEngine 的 Bean 循环依赖。
        top.rongx.agent.domain.run.RunRecoveryAdvisor lazyRecovery =
                new top.rongx.agent.domain.run.RunRecoveryAdvisor() {
                    @Override
                    public boolean available() {
                        return recoveryAdvisor.getIfAvailable() != null;
                    }

                    @Override
                    public java.util.List<String> advise(
                            top.rongx.agent.domain.run.RunRecoveryAdvisor.Context ctx) {
                        var target = recoveryAdvisor.getIfAvailable();
                        return target == null ? java.util.List.of() : target.advise(ctx);
                    }
                };
        BuiltInPipelineEngine engine = new BuiltInPipelineEngine(PipelineEngineConfig
                .builder(recipes, sandboxed, host, root)
                .history(store)
                .imagePrefix(imagePrefix)
                .repoLocator(repoLocator)
                .deployers(deployers)
                .resolver(resolver)
                .recipeStore(recipeStore)
                .envVars(envVars)
                .envStore(envStore)
                .envRuntime(envRuntime)
                .envProvisioner(envProvisioner)
                .recoveryAdvisor(lazyRecovery)
                .localDevHost(localDevHost)
                .build());
        // 应用关闭时中断执行线程，避免悬挂构建进程。
        // 用虚拟线程工厂创建钩子线程（规约 R04 禁止 new Thread）；
        // 虚拟线程必须为 daemon，而 JVM 退出本身会回收这些轻量线程，关闭语义不受影响。
        Runtime.getRuntime().addShutdownHook(
                Thread.ofVirtual().name("pipeline-shutdown").unstarted(engine::close));
        return engine;
    }
}
