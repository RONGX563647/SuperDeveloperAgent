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
            top.rongx.agent.domain.settings.SshAccess sshAccess) {
        return new Deployers(java.util.List.of(
                new DockerDeployer(), new HostDeployer(), new ServerDeployer(servers, sshAccess)));
    }

    /** SSH 访问实现（设置页测试连接与远程部署共用；known_hosts 留在平台工作区）。 */
    @Bean
    public top.rongx.agent.domain.settings.SshAccess sshAccess(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        Path knownHosts = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("known_hosts");
        return new SshExec(knownHosts);
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
                                         org.springframework.beans.factory.ObjectProvider<
                                                 top.rongx.agent.domain.port.PipelineDefAuthor> defAuthor) {
        Path root = Path.of(workspaceRoot);
        RunHistoryStore store = persist ? RunHistoryStore.file(root) : RunHistoryStore.disabled();
        // 定义解析：仓库提交覆盖 > 环境固化脚本 > 平台定义 > 规则生成 > agent 兜底
        PipelineDefinitionResolver resolver = new PipelineDefinitionResolver(
                defStore, scaffolder, defAuthor.getIfAvailable(), recipeStore);
        BuiltInPipelineEngine engine = new BuiltInPipelineEngine(recipes, sandboxed, host, root, store,
                imagePrefix, repoLocator, deployers, resolver, recipeStore);
        // 应用关闭时中断执行线程，避免悬挂构建进程。
        // 用虚拟线程工厂创建钩子线程（规约 R04 禁止 new Thread）；
        // 虚拟线程必须为 daemon，而 JVM 退出本身会回收这些轻量线程，关闭语义不受影响。
        Runtime.getRuntime().addShutdownHook(
                Thread.ofVirtual().name("pipeline-shutdown").unstarted(engine::close));
        return engine;
    }
}
