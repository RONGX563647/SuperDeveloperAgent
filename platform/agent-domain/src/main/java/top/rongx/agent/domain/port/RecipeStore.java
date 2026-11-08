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
}
