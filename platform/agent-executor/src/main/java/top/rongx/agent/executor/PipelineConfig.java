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
     * 平台部署器组合（方案 C）：DOCKER（本机容器）/ HOST（本机 jar）/ SERVER（待配置）。
     *
     * <p>@Primary：避免与 {@link ServerDeployer} 单 Bean 造成按类型注入歧义。
     */
    @Bean
    @Primary
    public top.rongx.agent.domain.port.Deployer deployers(ServerDeployer serverDeployer) {
        return new Deployers(java.util.List.of(
                new DockerDeployer(), new HostDeployer(), serverDeployer));
    }

    @Bean
    public PipelineEngine pipelineEngine(ActionResolver recipes,
                                         @Qualifier("hostActionRunner") ProcessActionRunner host,
                                         @Qualifier("actionRunner") ActionRunner sandboxed,
                                         @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
                                         @Value("${agent.pipeline.history:true}") boolean persist,
                                         @Value("${agent.pipeline.image-prefix:ci}") String imagePrefix,
                                         top.rongx.agent.domain.port.ProjectRepoLocator repoLocator,
                                         top.rongx.agent.domain.port.Deployer deployers) {
        Path root = Path.of(workspaceRoot);
        RunHistoryStore store = persist ? RunHistoryStore.file(root) : RunHistoryStore.disabled();
        BuiltInPipelineEngine engine = new BuiltInPipelineEngine(recipes, sandboxed, host, root, store,
                imagePrefix, repoLocator, deployers);
        // 应用关闭时中断执行线程，避免悬挂构建进程。
        // 用虚拟线程工厂创建钩子线程（规约 R04 禁止 new Thread）；
        // 虚拟线程必须为 daemon，而 JVM 退出本身会回收这些轻量线程，关闭语义不受影响。
        Runtime.getRuntime().addShutdownHook(
                Thread.ofVirtual().name("pipeline-shutdown").unstarted(engine::close));
        return engine;
    }
}
