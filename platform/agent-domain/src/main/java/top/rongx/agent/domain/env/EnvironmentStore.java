package top.rongx.agent.domain.env;

import java.util.List;
import java.util.Optional;

/**
 * 环境模板 / 中间件资源 / 环境变量 / 项目覆盖 / 绑定 / 端口租约的统一仓储端口。
 *
 * <p>放在 domain：执行面（PipelineEngine）与网关（设置页）共用同一份规则，
 * 避免"页面看到的变量"和"部署注入的变量"两套实现。
 */
public interface EnvironmentStore {

    // ---------------------------------------------------------------- 模板

    List<EnvTemplate> templates();

    Optional<EnvTemplate> template(String env);

    void saveTemplate(EnvTemplate template);

    // ---------------------------------------------------------------- 中间件资源

    List<EnvResource> resources(String env);

    Optional<EnvResource> resource(String id);

    EnvResource saveResource(EnvResource resource);

    void deleteResource(String id);

    /** 记录健康检查结果（只读，不启停）。 */
    void recordHealth(String id, boolean running, String error);

    // ---------------------------------------------------------------- 环境变量

    /** 环境模板变量（敏感项掩码）。 */
    List<EnvVariable> envVars(String env);

    /** value 为空 = 保持原值（新建时必须非空）。 */
    EnvVariable saveEnvVar(String env, String key, String value, boolean secret, String source);

    /** 单个模板变量的明文（仅"查看密码"接口使用，调用方负责权限与审计）。 */
    Optional<String> envVarValue(String env, String key);

    /**
     * 把中间件资源的连接信息同步成环境模板变量（source=RESOURCE）。
     *
     * <p>只补/更新 RESOURCE 来源的键，不覆盖人工维护的 TEMPLATE 变量；
     * 运行状态不参与，容器没启动也照常保存连接信息。
     *
     * @return 实际写入的变量数
     */
    int syncResourceVars(String env);

    void deleteEnvVar(String env, String key);

    // ---------------------------------------------------------------- 项目覆盖

    List<EnvVariable> overrides(String projectId, String env);

    EnvVariable saveOverride(String projectId, String env, String key, String value, boolean secret);

    void deleteOverride(String projectId, String env, String key);

    // ---------------------------------------------------------------- 有效环境

    /** 环境模板 + 项目覆盖；values 含明文，只给执行面用。 */
    EffectiveEnvironment effective(String projectId, String env);

    // ---------------------------------------------------------------- Agent 绑定

    EnvBinding saveBinding(EnvBinding binding);

    Optional<EnvBinding> binding(String id);

    void confirmBinding(String id, String confirmedBy, long expiresAt);

    // ---------------------------------------------------------------- 端口租约

    Optional<PortLease> lease(String projectId, String env, int containerPort);

    /** 某项目某环境的全部端口租约（设置页/排障查询）。 */
    List<PortLease> leases(String projectId, String env);

    void saveLease(PortLease lease);

    // ---------------------------------------------------------------- 本地服务租约

    /** 某项目某环境的全部本地服务租约（dev 前后端）。 */
    List<ServiceLease> serviceLeases(String projectId, String env);

    /** 某环境所有项目的本地服务租约（端口冲突排查）。 */
    List<ServiceLease> allServiceLeases(String env);

    /** 写入/更新一条本地服务租约（按 project+env+service 唯一）。 */
    void saveServiceLease(ServiceLease lease);

    void deleteServiceLease(String projectId, String env, String service);
}
