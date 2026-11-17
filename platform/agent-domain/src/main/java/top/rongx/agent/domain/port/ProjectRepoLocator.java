package top.rongx.agent.domain.port;

import java.nio.file.Path;
import java.util.List;
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

    /**
     * 在项目仓库里创建 git 标签，并尽力推送到 origin。
     *
     * <p>打 tag 的流水线发布成功后调用：本地建标签失败不应影响已完成的部署，
     * 由调用方决定如何提示。
     */
    default TagOutcome createTag(String projectId, String tag, String sha) {
        return new TagOutcome(false, false, "当前仓库模式不支持创建 git 标签");
    }

    /**
     * 同上，但带上触发用户：仓库实现可用该用户保存的托管账号 token 推送，
     * 私有仓库不再依赖运行平台主机的本机凭据。
     */
    default TagOutcome createTag(String projectId, String tag, String sha, String userId) {
        return createTag(projectId, tag, sha);
    }

    /**
     * 重新推送已存在的本地标签（幂等）：标签只在本地/推送失败后由用户手动重试。
     */
    default TagOutcome pushTag(String projectId, String tag, String userId) {
        return new TagOutcome(false, false, "当前仓库模式不支持推送 git 标签");
    }

    /** 打标签结果：created = 本地已创建；pushed = 已推送到远端。 */
    record TagOutcome(boolean created, boolean pushed, String message) {
    }

    /**
     * 现有 git 标签（按版本降序，供版本号输入框做候选与重复校验）。
     *
     * <p>默认不支持，由具体仓库实现覆盖；仓库不可用时返回空列表。
     */
    default List<String> listTags(String projectId, int limit) {
        return List.of();
    }

    enum StorageMode {
        /** 仓库在用户本地目录，平台就地操作。 */
        DIRECT,
        /** 平台托管 bare（v1 布局）。 */
        MANAGED
    }
}
