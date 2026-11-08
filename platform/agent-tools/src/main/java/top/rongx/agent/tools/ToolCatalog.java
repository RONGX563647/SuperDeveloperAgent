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
 *   <li>{@code fullToolkit}：只读工具 + 写组（初始激活），供<b>非生产环境</b>会话使用。</li>
 * </ul>
 * 这样"生产环境没有写工具"是装配层面的，而不是提示词层面的。
 */
@Configuration
public class ToolCatalog {

    /** 写操作工具分组名。 */
    public static final String GROUP_WRITE = "write";

    @Bean
    @Qualifier("readOnlyToolkit")
    public Toolkit readOnlyToolkit(ProjectTools projectTools,
                                   CiQueryTools ciQueryTools,
                                   ActionQueryTools actionQueryTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(projectTools);
        toolkit.registerTool(ciQueryTools);
        toolkit.registerTool(actionQueryTools);
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
                                DeployTools deployTools) {
        Toolkit toolkit = new Toolkit();

        // 只读工具：注册即生效（进入 basic 组）
        toolkit.registerTool(projectTools);
        toolkit.registerTool(ciQueryTools);
        toolkit.registerTool(actionQueryTools);
        toolkit.registerTool(deployTools);

        // 写操作单独成组：非生产会话激活
        toolkit.createToolGroup(GROUP_WRITE, "会改变环境状态的操作（构建 / 变更请求 / 固化脚本）", true);
        toolkit.registration().tool(ciCommandTools).group(GROUP_WRITE).apply();
        toolkit.registration().tool(changeTools).group(GROUP_WRITE).apply();
        toolkit.registration().tool(actionCommandTools).group(GROUP_WRITE).apply();

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
                                 DeployTools deployTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(projectTools);
        toolkit.registerTool(ciQueryTools);
        toolkit.registerTool(actionQueryTools);
        toolkit.registerTool(deployTools);
        return toolkit;
    }
}
