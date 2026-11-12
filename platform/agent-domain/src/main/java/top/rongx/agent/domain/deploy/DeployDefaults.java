package top.rongx.agent.domain.deploy;

/** 平台侧流水线 deploy 段的生效默认值：页面展示与部署共用同一份解析结果。 */
public record DeployDefaults(int port, String healthPath, String target) {
}
