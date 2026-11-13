package top.rongx.agent.domain.project;

/**
 * 会话级项目上下文。
 *
 * <p><b>安全要点</b>：本对象由服务端在建立会话时构造，经 AgentScope 的 {@code RuntimeContext}
 * 注入到工具参数中。模型<b>无法</b>提供或修改它（规约 §1.1 A2）。
 *
 * <p>因此工具可以安全地在任意线程执行——不依赖任何 ThreadLocal。
 *
 * @param projectId 项目
 * @param env       目标环境
 * @param userId    当前操作人
 * @param repoUrl   仓库地址
 * @param worktree  当前工作树（可空 = 主仓库）
 * @param readOnly  当前用户是否只读（VIEWER）：只装配只读工具面
 */
public record ProjectContext(String projectId, String env, String userId, String repoUrl,
                             String worktree, boolean readOnly) {

    /** 合法工作树名：平台按 {@code wt-*} 命名，禁止分隔符与 {@code ..}（防目录逃逸）。 */
    private static final java.util.regex.Pattern WORKTREE_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** 兼容旧调用：默认非只读。 */
    public ProjectContext(String projectId, String env, String userId, String repoUrl,
                          String worktree) {
        this(projectId, env, userId, repoUrl, worktree, false);
    }

    public ProjectContext {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空（必须由服务端绑定）");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        worktree = (worktree == null || worktree.isBlank()) ? null : worktree.trim();
        // 工作树名来自客户端：必须在进入工具层之前校验，杜绝 ../../ 逃逸到服务器任意目录
        if (worktree != null
                && (!WORKTREE_NAME.matcher(worktree).matches() || worktree.contains(".."))) {
            throw new IllegalArgumentException("工作树名不合法：" + worktree);
        }
    }

    public boolean isProduction() {
        return "prod".equals(env);
    }
}
