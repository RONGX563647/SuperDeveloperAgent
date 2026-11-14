package top.rongx.agent.domain.port;

/**
 * 只读角色查询（运行层装配异步运行时使用）。
 *
 * <p>工作区调度 Agent 发起的运行不在 HTTP 请求线程里，拿不到网关的登录上下文；
 * 因此需要一个按 userId 查询角色的端口，保证 VIEWER 发起的运行仍然只装配只读工具面。
 */
public interface UserRolePort {

    /** 未知用户按只读处理（fail-closed）。 */
    boolean isReadOnly(String userId);
}
