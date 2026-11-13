package top.rongx.agent.domain.bugfix;

/**
 * 部署端口：把指定提交部署到测试环境，返回可读的部署证据。
 *
 * <p>实现必须遵守平台门禁：只允许 dev/test，自动修复永远不会操作生产环境。
 */
public interface BugFixDeployPort {

    String deploy(BugFixRun run, String sha);
}
