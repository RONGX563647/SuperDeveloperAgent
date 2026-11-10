package top.rongx.agent.domain.env;

import java.util.List;

/**
 * Agent 环境绑定的领域端口。
 *
 * <p>Agent 工具层只依赖这个接口：生成映射草案、运行前校验绑定；
 * 具体的项目扫描与哈希实现留在网关侧，避免工具模块反向依赖 Web 模块。
 */
public interface EnvBindingPort {

    /** 生成草案（DRAFT）；不包含任何变量值。 */
    EnvBinding draft(String projectId, String env, String actor);

    /** 运行前校验：必须已人工确认、未过期、项目/环境一致、变量未变更。 */
    EnvBinding requireForRun(String bindingId, String projectId, String env);

    /** 项目声明的必填变量名（来自 .env.example 等，只读键名，不含值）。 */
    default List<String> requiredKeys(String projectId) {
        return List.of();
    }
}
