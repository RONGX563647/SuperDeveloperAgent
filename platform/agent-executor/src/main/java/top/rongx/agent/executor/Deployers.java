package top.rongx.agent.executor;

import java.util.List;
import top.rongx.agent.domain.deploy.DeployRequest;
import top.rongx.agent.domain.deploy.DeployResult;
import top.rongx.agent.domain.deploy.DeployTarget;
import top.rongx.agent.domain.port.Deployer;

/**
 * 部署器注册表：按目标选择实现；未命中一律 fail-closed（E-505）。
 *
 * <p>默认内置 DOCKER + HOST + SERVER（占位）；Spring 环境由 {@link PipelineConfig}
 * 注入受管 Bean（含配置项）。
 */
public class Deployers implements Deployer {

    private final List<Deployer> delegates;

    public Deployers(List<Deployer> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    /** 默认组合：Docker + Host + Server（Server 未配置时返回 E-505）。 */
    public static Deployers builtin() {
        return new Deployers(List.of(new DockerDeployer(), new HostDeployer(),
                new ServerDeployer("", 22, "deploy", "", "/opt/agent/deploy")));
    }

    @Override
    public boolean supports(DeployTarget target) {
        return delegates.stream().anyMatch(d -> d.supports(target));
    }

    @Override
    public DeployResult deploy(DeployRequest request) {
        return delegates.stream()
                .filter(d -> d.supports(request.target()))
                .findFirst()
                .map(d -> d.deploy(request))
                .orElseGet(() -> DeployResult.failed(request.target(), "E-505",
                        "未注册的部署目标：" + request.target().wireName(), ""));
    }
}
