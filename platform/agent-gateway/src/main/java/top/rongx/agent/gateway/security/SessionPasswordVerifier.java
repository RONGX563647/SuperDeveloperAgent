package top.rongx.agent.gateway.security;

import org.springframework.stereotype.Component;
import top.rongx.agent.gateway.auth.UserStore;

/**
 * 真实二级密码校验（高危变更需要）。
 *
 * <p>BCrypt 校验当前登录账号的密码——即"重新输一次密码"的语义，
 * 而不是"输入第二个静态密码"。校验对象由 Sa-Token 会话确定，不可伪造。
 */
@Component
public class SessionPasswordVerifier implements PasswordVerifier {

    private final UserStore users;

    public SessionPasswordVerifier(UserStore users) {
        this.users = users;
    }

    @Override
    public boolean verify(String userId, String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return false;
        }
        return users.checkPassword(userId, rawPassword);
    }
}
