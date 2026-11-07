package top.rongx.agent.domain.port;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionSpec;

/**
 * 配方解析端口（方案 D 的核心）。
 *
 * <p>解析顺序：{@code ci/actions.yml} → {@code Taskfile.yml} → {@code Makefile} → 平台内置默认。
 * 前三种内容由**人**写在项目仓库里并经 review；平台只负责读与校验。
 */
public interface ActionResolver {

    /**
     * 解析某个动作。
     *
     * @param repoRoot 项目工作区根目录
     * @param action   动作
     * @return 规格；无配方且无内置默认时为空
     */
    Optional<ActionSpec> resolve(Path repoRoot, ActionId action);

    /** 解析全部动作（用于"这个项目能做什么"）。 */
    List<ActionSpec> resolveAll(Path repoRoot);

    /** 该项目是否已有任何一种配方来源（用于接入向导提示）。 */
    boolean hasRecipe(Path repoRoot);
}
