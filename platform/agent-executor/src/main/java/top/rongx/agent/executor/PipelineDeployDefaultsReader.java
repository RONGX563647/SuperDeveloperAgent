package top.rongx.agent.executor;

import top.rongx.agent.domain.deploy.DeployDefaults;
import top.rongx.agent.domain.port.DeployDefaultsReader;
import top.rongx.agent.domain.port.PipelineDefStore;

/** {@link DeployDefaultsReader} 的实现：解析平台侧 yaml 的 deploy 段。 */
public class PipelineDeployDefaultsReader implements DeployDefaultsReader {

    private final PipelineDefStore defs;

    public PipelineDeployDefaultsReader(PipelineDefStore defs) {
        this.defs = defs;
    }

    @Override
    public DeployDefaults read(String projectId) {
        return defs.read(projectId)
                .map(yaml -> PipelineDefinition.parse(yaml, "平台侧定义").deploy())
                .map(d -> new DeployDefaults(d.port(), d.healthPath(), d.target()))
                .orElse(null);
    }
}
