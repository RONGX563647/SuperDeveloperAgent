package top.rongx.agent.domain.deploy;

/**
 * 流水线触发的授权门禁（E-602）。
 *
 * <p>放在 domain 而不是 HTTP 层，是为了让<b>所有</b>触发入口（UI、Agent 工具、内部重放）
 * 走同一份校验——否则"换个入口就绕过一次性人工确认"。
 */
public interface RunAuthorizationGate {

    /** 是否装配了门禁实现（无实现时视为不启用，方便单测）。 */
    boolean available();

    /**
     * 校验并消费一次性确认令牌；不满足时抛 {@link IllegalArgumentException}（E-602）。
     *
     * <p>只有"需要证据/人工确认"的环境才会真正校验，其余环境直接放行。
     */
    void require(String projectId, String env, String sha, String confirmToken);
}
