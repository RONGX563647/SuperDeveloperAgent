package top.rongx.agent.domain.settings;

/**
 * 能力安装器：把市场里的 MCP 服务 / Skill 落到平台配置。
 *
 * <p>放在 domain 层做端口，避免 agent-tools（Agent 工具）直接依赖 infra 的存储实现：
 * 设置页市场与 Agent 工具都调用同一个安装入口，行为一致。
 */
public interface CapabilityInstaller {

    /** 安装结果（已安装时 installed=true 且不重复写入）。 */
    record Result(String kind, String name, boolean installed, String message) {
    }

    /** 该能力是否已接入（MCP 按名称、Skill 按目录名判断）。 */
    boolean isInstalled(String kind, String name);

    /** 按目录 id 安装；不存在时抛 IllegalArgumentException。 */
    Result install(String capabilityId);
}
