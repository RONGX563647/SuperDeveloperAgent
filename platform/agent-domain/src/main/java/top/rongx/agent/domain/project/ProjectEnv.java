package top.rongx.agent.domain.project;

/**
 * 项目下的一套环境。
 *
 * <p>不再登记「应用访问地址」：某个环境实际跑在哪个端口，一律由 EffectiveEndpointResolver
 * 从服务/端口租约或流水线定义实时解析。静态地址曾经是接入时写死的占位值（8180/8181），
 * 既不参与部署也对不齐真实端口，只会制造误判（见 V7__drop_project_env_app_url）。
 *
 * @param projectId 所属项目
 * @param env       环境名：dev / test / staging / prod
 * @param composeDir 部署目录备注，可为空；仅登记、不参与部署落点
 */
public record ProjectEnv(String projectId, String env, String composeDir) {

    public boolean isProduction() {
        return "prod".equals(env);
    }
}
