package top.rongx.agent.domain.port;

import java.util.List;

/**
 * 流水线定义的只读视图（给网关做"就绪检查"用）。
 *
 * <p>为什么放在 domain 端口：定义解析在执行面（{@code agent-executor}），而网关按
 * §2.1 只能依赖 Runtime/Tools/Governance/Domain/Infra，不能反向依赖执行面。
 * 网关通过这个端口拿到"部署目标 / 必填变量"等结论，不接触执行面的类。
 */
public interface PipelineDefinitionInspector {

    /**
     * @param target      部署目标：docker / host / server；无 deploy 段时为空
     * @param envRequired 部署必填变量名（只含名字，不含值）
     * @param hasDeploy   定义里是否有 deploy 段
     * @param error       解析失败原因；null 表示解析成功
     */
    record Inspection(String target, List<String> envRequired, boolean hasDeploy, String error) {

        public static Inspection failed(String error) {
            return new Inspection("", List.of(), false, error);
        }
    }

    /** 解析 YAML；永不抛异常，失败信息放 {@link Inspection#error()}。 */
    Inspection inspect(String pipelineYaml);
}
