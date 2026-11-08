package top.rongx.agent.domain.port;

import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;

/**
 * 部署器端口（方案 C 的核心）：平台拥有部署语义，不再让仓库脚本自行部署。
 *
 * <p>实现方（执行面）负责把"构建产物"变成一个可访问的运行态，并保证：
 * <ul>
 *   <li><b>可重复</b>：同一请求重复执行结果一致（先停旧、再起新）；</li>
 *   <li><b>可回滚</b>：{@link DeployRequest#previousImageRef()} 非空时能退回上一版；</li>
 *   <li><b>可观测</b>：返回 endpoint 与平台托管资源名，供健康检查与审计使用。</li>
 * </ul>
 *
 * <p>未实现的目标必须 <b>fail-closed</b>（返回 E-5xx + 明确指引），不允许静默跳过。
 */
public interface Deployer {

    /** 是否支持该目标。 */
    boolean supports(DeployTarget target);

    /** 执行部署。实现不得抛出异常，所有失败都以 {@link DeployResult} 表达。 */
    DeployResult deploy(DeployRequest request);
}
