package top.rongx.agent.domain.deploy;

/**
 * 一次部署请求（平台部署器的唯一输入）。
 *
 * @param projectId     项目 id
 * @param runId         触发本次部署的运行 id（用于容器名/日志归属）
 * @param gitSha        被部署的提交
 * @param imageRef      镜像引用（DOCKER 目标必需；形如 {@code ci-abc1234}）
 * @param artifactPath  HOST 目标的产物路径（相对工作区，通常是 jar）
 * @param workdir       仓库内工作目录（monorepo 子模块；可为空）
 * @param workspace     本次运行的工作树绝对路径
 * @param target        部署目标
 * @param hostPort      对外端口（DOCKER 映射 / HOST 监听 / SERVER 远端端口）
 * @param containerPort 容器内端口（仅 DOCKER）
 * @param healthPath    健康检查路径（如 {@code /healthz}），供后续门禁拼接 URL
 * @param previousImageRef 上一版镜像引用（回滚用；可能为空）
 * @param rollback      是否为回滚动作（true 时部署器应回到上一版，而不是当前构建产物）
 * @param serverId      目标服务器 id（SERVER 目标用；空则用默认服务器）
 */
public record DeployRequest(
        String projectId,
        String runId,
        String gitSha,
        String imageRef,
        String artifactPath,
        String workdir,
        String workspace,
        DeployTarget target,
        int hostPort,
        int containerPort,
        String healthPath,
        String previousImageRef,
        boolean rollback,
        String serverId) {
}
