package top.rongx.agent.domain.port;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import top.rongx.agent.domain.deploy.DeployRecipe;

/**
 * 环境级可复现脚本（recipe）存储。
 *
 * <p>每个 (项目, 环境) 有一个 {@code current} 版本，历史版本保留以便回滚；
 * 内容是确定性脚本（pipeline.yml + actions.yml）与"它跑通过"的证据 run id。
 */
public interface RecipeStore {

    /** 保存一个新版本，返回版本号（从 1 递增），并把它设为 current。 */
    DeployRecipe save(DeployRecipe recipe);

    /** 当前生效版本。 */
    Optional<DeployRecipe> current(String projectId, String env);

    /** 指定版本。 */
    Optional<DeployRecipe> version(String projectId, String env, int version);

    /** 该环境的所有版本（新→旧）。 */
    List<DeployRecipe> versions(String projectId, String env);

    /** 把某个历史版本重新设为 current（回滚脚本，不部署）。 */
    boolean activate(String projectId, String env, int version);

    /** 读取某个版本的定义根目录（其下有 ci/，供解析与配方查找复用）。 */
    Path rootOf(String projectId, String env, int version);

    /**
     * 脚本内容没变时只刷新证据（runId / gitSha），不新增版本。
     *
     * <p>为什么需要它：晋升校验要求"固化脚本的 gitSha 等于本次发布提交"，
     * 而脚本内容往往连续多个提交都不变；若只在内容变化时更新证据，
     * 第二次发布起就会永远是旧提交的证据，生产晋升被误判为"未在测试环境验证"。
     *
     * @return 更新后的版本；没有 current 版本时返回空（调用方应走 {@link #save}）
     */
    default Optional<DeployRecipe> updateEvidence(String projectId, String env,
                                                  String runId, String gitSha) {
        return Optional.empty();
    }

    /**
     * 让该环境当前生效脚本失效（保留历史版本，可再 activate 回来）。
     *
     * <p>用于"平台定义被重新生成"：旧脚本是旧定义的固化产物，继续生效会让用户以为
     * 新定义没起作用。默认 no-op，方便测试替身。
     */
    default void invalidate(String projectId, String env) {
    }
}
