package top.rongx.agent.domain.port;

import java.util.Optional;
import top.rongx.agent.domain.change.ChangePlan;

/**
 * 变更单存储端口。
 *
 * <p>放在 domain 的原因与 {@link PipelineEngine} 相同：工具层要"登记一个变更"，
 * 但按分层规约 tools 只能依赖 domain。实现（内存/数据库）在 governance 或 infra。
 */
public interface ChangeStore {

    /**
     * 登记一个待确认的变更计划。
     *
     * @param projectId 项目（服务端上下文）
     * @param env       目标环境
     * @param kind      RELEASE / ROLLBACK / RUNBOOK / MIGRATION
     * @param summary   面向人的一句话
     * @param imageDigest 镜像 digest，可空
     * @param frontendDistHash 前端产物哈希，可空
     * @param highRisk  是否高危
     * @return 计划（含 changeId 与 planHash）
     */
    ChangePlan request(String projectId, String env, String kind, String summary,
                       String imageDigest, String frontendDistHash, boolean highRisk);

    /**
     * 用**调用方给定的 planHash** 登记变更。
     *
     * <p>动作类变更（stack_deploy / stack_rollback）的哈希来自"实际配方"，
     * 而不是摘要文本：这样配方在确认之后被改动，执行时会因哈希不符被拒。
     */
    ChangePlan registerWithHash(String projectId, String env, String kind, String summary,
                                String planHash, boolean highRisk);

    /** 按 id 查。 */
    Optional<ChangePlan> find(String changeId);
}
