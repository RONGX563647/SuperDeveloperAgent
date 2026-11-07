package org.example.agent.gateway.security;

/**
 * 二级密码校验（高危变更需要）。
 *
 * <p>账号体系尚未接入时使用 {@link FailClosedPasswordVerifier}：<b>一律拒绝</b>，
 * 而不是放行——高危操作在能力缺失时必须失败关闭（规约 §8：默认取安全侧）。
 */
public interface PasswordVerifier {

    /**
     * 校验用户密码。
     *
     * @param userId   用户
     * @param rawPassword 明文密码
     * @return 是否通过
     */
    boolean verify(String userId, String rawPassword);
}
