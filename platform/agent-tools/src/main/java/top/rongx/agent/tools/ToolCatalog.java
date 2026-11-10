package top.rongx.agent.tools;

import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 工具装配。
 *
 * <p>提供两个 Toolkit（均为无状态单例，按环境二选一，避免运行时改共享 Toolkit 的激活集）：
 * <ul>
 *   <li>{@code readOnlyToolkit}：只含只读工具，供<b>生产环境</b>会话使用——
 *       写工具根本不在 schema 里（比"组未激活"更强的保证）；</li>
 *   <li>{@code fullToolkit}：只读工具 + 全部写工具，供<b>非生产环境</b>会话使用。</li>
 * </ul>
 * 这样"生产环境没有写工具"是装配层面的，而不是提示词层面的。
 *
 * <p><b>为什么不把写工具放进工具组：</b>AgentScope 的 ReActAgent 在每次 call 前都会用
 * 会话状态里的 {@code activatedGroups} 覆盖 Toolkit 的激活集，而新会话的该列表为空——
 * 放进自定义分组的工具会被整体过滤掉，模型也就看不到 {@code request_confirmation}，
 * 与系统提示自相矛盾。写操作的边界由只读 Toolkit（生产）与确认卡/控制器共同保证，
 * 不依赖工具组的激活状态。
 */
@Configuration
public class ToolCatalog {

    @Bean
    @Qualifier("readOnlyToolkit")
    public Toolkit readOnlyToolkit(ProjectTools projectTools,
                                   CiQueryTools ciQueryTools,
                                   ActionQueryTools actionQueryTools,
                                   RepoQueryTools repoQueryTools,
                                   RuntimeTools runtimeTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(projectTools);
        toolkit.registerTool(ciQueryTools);
        toolkit.registerTool(actionQueryTools);
        toolkit.registerTool(repoQueryTools);
        toolkit.registerTool(runtimeTools);
        return toolkit;
    }

    @Bean
    @Primary
    public Toolkit agentToolkit(ProjectTools projectTools,
                                CiQueryTools ciQueryTools,
                                CiCommandTools ciCommandTools,
                                ChangeTools changeTools,
                                ActionQueryTools actionQueryTools,
                                ActionCommandTools actionCommandTools,
                                DeployTools deployTools,
                                EnvTools envTools,
                                RepoQueryTools repoQueryTools,
                                RuntimeTools runtimeTools) {
        Toolkit toolkit = new Toolkit();

        // 只读工具
        toolkit.registerTool(projectTools);
        toolkit.registerTool(ciQueryTools);
        toolkit.registerTool(actionQueryTools);
        toolkit.registerTool(envTools);
        toolkit.registerTool(deployTools);
        toolkit.registerTool(repoQueryTools);
        toolkit.registerTool(runtimeTools);

        // 写操作工具：直接注册，保证非生产会话始终可见（确认交给变更单 / 确认卡）
        toolkit.registerTool(ciCommandTools);
        toolkit.registerTool(changeTools);
        toolkit.registerTool(actionCommandTools);

        return toolkit;
    }

    /**
     * 部署 Agent 专用工具面：环境状态 + 触发 + 固化 + 通用只读工具。
     *
     * <p>只给"部署这件事需要的最小集合"：它不需要变更单工具，也不需要随手改配方的能力。
     */
    @Bean
    @Qualifier("deployToolkit")
    public Toolkit deployToolkit(ProjectTools projectTools,
                                 CiQueryTools ciQueryTools,
                                 ActionQueryTools actionQueryTools,
                                 DeployTools deployTools,
                                 EnvTools envTools,
                                 RuntimeTools runtimeTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(projectTools);
        toolkit.registerTool(ciQueryTools);
        toolkit.registerTool(actionQueryTools);
        toolkit.registerTool(envTools);
        toolkit.registerTool(deployTools);
        toolkit.registerTool(runtimeTools);
        return toolkit;
    }
}
