package top.rongx.agent.domain.port;

/**
 * 只读部署默认值：Gateway 需要展示「真正生效」的端口/健康路径/目标，
 * 但不允许直接依赖 executor 的 PipelineDefinition。
 */
public interface PipelineDeployReader {

    /** 没有平台侧定义或解析失败时返回 null。 */
    DeployDefaults read(String projectId);

    record DeployDefaults(int port, String healthPath, String target) {
    }
}
