package top.rongx.agent.domain.project;

/**
 * 项目下的一套环境。
 *
 * @param projectId 所属项目
 * @param env       环境名：dev / test / staging / prod
 * @param appUrl    应用访问地址（健康检查用）
 * @param composeDir 部署目录（含 docker-compose.yml），可为空
 */
public record ProjectEnv(String projectId, String env, String appUrl, String composeDir) {

    public boolean isProduction() {
        return "prod".equals(env);
    }
}
