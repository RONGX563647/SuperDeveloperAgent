package top.rongx.agent.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.PipelineDefStore;
import top.rongx.agent.domain.port.PipelineDeployReader;

/** 把平台侧流水线的 deploy 段映射为 Gateway 可用的最小只读视图。 */
@Component
public class PipelineDeployReaderAdapter implements PipelineDeployReader {

    private static final Logger log = LoggerFactory.getLogger(PipelineDeployReaderAdapter.class);

    private final PipelineDefStore defs;

    public PipelineDeployReaderAdapter(PipelineDefStore defs) {
        this.defs = defs;
    }

    @Override
    public DeployDefaults read(String projectId) {
        try {
            return defs.read(projectId)
                    .map(yaml -> PipelineDefinition.parse(yaml, "平台侧定义").deploy())
                    .map(d -> new DeployDefaults(d.port(),
                            d.healthPath() == null ? "" : d.healthPath(),
                            d.target() == null ? "" : d.target()))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("流水线定义解析失败，端口/健康路径退回环境模板 projectId={}：{}",
                    projectId, e.getMessage());
            return null;
        }
    }
}
