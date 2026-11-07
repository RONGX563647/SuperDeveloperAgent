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

    @Bean
    public PipelineEngine pipelineEngine(ActionResolver recipes,
                                         @Qualifier("hostActionRunner") ProcessActionRunner host,
                                         @Qualifier("actionRunner") ActionRunner sandboxed,
                                         @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
                                         @Value("${agent.pipeline.history:true}") boolean persist) {
        Path root = Path.of(workspaceRoot);
        RunHistoryStore store = persist ? RunHistoryStore.file(root) : RunHistoryStore.disabled();
        return new BuiltInPipelineEngine(recipes, sandboxed, host, root, store);
    }
}
