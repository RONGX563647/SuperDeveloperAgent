package top.rongx.agent.executor;

import org.springframework.stereotype.Component;
import top.rongx.agent.domain.port.PipelineDefinitionInspector;

/** 用执行面的定义解析规则实现只读视图（网关不直接依赖 executor）。 */
@Component
public class PipelineDefinitionInspectorImpl implements PipelineDefinitionInspector {

    @Override
    public Inspection inspect(String pipelineYaml) {
        if (pipelineYaml == null || pipelineYaml.isBlank()) {
            return Inspection.failed("定义为空");
        }
        try {
            PipelineDefinition definition = PipelineDefinition.parse(pipelineYaml, "pipeline.yml");
            PipelineDefinition.DeploySpec deploy = definition.deploy();
            if (deploy == null) {
                return new Inspection("", java.util.List.of(), false, null);
            }
            return new Inspection(
                    deploy.target() == null ? "" : deploy.target(),
                    deploy.envRequired() == null ? java.util.List.of() : deploy.envRequired(),
                    true,
                    null);
        } catch (RuntimeException e) {
            return Inspection.failed(e.getMessage());
        }
    }
}
