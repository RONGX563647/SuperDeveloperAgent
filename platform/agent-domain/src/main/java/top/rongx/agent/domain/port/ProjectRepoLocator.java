package top.rongx.agent.domain.port;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 项目仓库定位器：给 projectId，回答"git 仓库在哪、构建工作树放哪、怎么同步最新代码"。
 *
 * <p>两种存储模式（v2 直接模式）：
 * <ul>
 *   <li><b>DIRECT</b>：仓库就在用户指定的本地目录（本地项目原地登记，或 Gitee clone 到
 *       用户选择的目录）——平台在该目录上直接 fetch + worktree 构建，不复制、不建 bare 副本；</li>
 *   <li><b>MANAGED</b>：平台托管（bare 在 {@code workspaces/<id>/git}）——v1 遗留布局。</li>
 * </ul>
 */
public interface ProjectRepoLocator {

    /** git 仓库目录（DIRECT = 用户目录；MANAGED = bare）。 */
    Optional<Path> repoDir(String projectId);

    /** 构建工作树的父目录（DIRECT = 用户目录下 .agent/worktrees；MANAGED = workspaces/<id>/worktrees）。 */
    Optional<Path> worktreeRoot(String projectId);

    /** 同步最新代码：DIRECT 从远端 fetch；MANAGED 在 bare 内 fetch。 */
    void sync(String projectId);

    /** 存储模式。 */
    StorageMode modeOf(String projectId);

    enum StorageMode {
        /** 仓库在用户本地目录，平台就地操作。 */
        DIRECT,
        /** 平台托管 bare（v1 布局）。 */
        MANAGED
    }
}
