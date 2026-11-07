package top.rongx.agent.tools;

import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具装配。
 *
 * <p>写类工具进入 {@code write} 组（初始不激活），由运行时按环境决定是否启用。
 * 这样"生产环境默认没有写工具"是配置层面的，而不是提示词层面的。
 */
@Configuration
public class ToolCatalog {

    /** 写操作工具分组名。 */
    public static final String GROUP_WRITE = "write";

    @Bean
    public Toolkit agentToolkit(ProjectTools projectTools,
                                CiQueryTools ciQueryTools,
                                CiCommandTools ciCommandTools,
                                ChangeTools changeTools,
                                ActionQueryTools actionQueryTools,
                                ActionCommandTools actionCommandTools) {
        Toolkit toolkit = new Toolkit();

        // 只读工具：注册即生效（进入 basic 组）
        toolkit.registerTool(projectTools);
        toolkit.registerTool(ciQueryTools);
        toolkit.registerTool(actionQueryTools);

        // 写操作单独成组：默认不激活
        toolkit.createToolGroup(GROUP_WRITE, "会改变环境状态的操作（构建 / 变更请求）", false);
        toolkit.registration().tool(ciCommandTools).group(GROUP_WRITE).apply();
        toolkit.registration().tool(changeTools).group(GROUP_WRITE).apply();
        toolkit.registration().tool(actionCommandTools).group(GROUP_WRITE).apply();

        return toolkit;
    }
}
