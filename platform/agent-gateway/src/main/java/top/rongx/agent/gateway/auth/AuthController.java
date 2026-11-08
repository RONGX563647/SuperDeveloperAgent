package top.rongx.agent.gateway.auth;

import cn.dev33.satoken.stp.StpUtil;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录/登出接口（唯一签发会话令牌的地方）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserStore users;

    public AuthController(UserStore users) {
        this.users = users;
    }

    /** 登录：用户名 + 密码 → 会话令牌（前端后续所有请求带 Authorization: Bearer <token>）。 */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        String userId = request.username() == null ? "" : request.username().trim();
        UserStore.User user = users.find(userId);
        // 用户不存在与密码错误返回同一句文案：不向探测者泄露账号存在性
        if (user == null || !users.checkPassword(userId, request.password() == null ? "" : request.password())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        StpUtil.login(user.userId());
        return Map.of(
                "token", StpUtil.getTokenValue(),
                "userId", user.userId(),
                "displayName", user.displayName(),
                "role", user.role().code(),
                "permissions", user.role().permissions(),
                "projects", user.projects());
    }

    /** 登出：销毁当前会话。 */
    @PostMapping("/logout")
    public Map<String, Object> logout() {
        StpUtil.logout();
        return Map.of("ok", true);
    }

    /** 当前登录身份（前端启动时校验 token 是否仍有效）。 */
    @PostMapping("/me")
    public Map<String, Object> me() {
        StpUtil.checkLogin();
        String userId = StpUtil.getLoginIdAsString();
        UserStore.User user = users.find(userId);
        return Map.of(
                "userId", userId,
                "displayName", user == null ? userId : user.displayName(),
                "role", user == null ? "?" : user.role().code(),
                "permissions", user == null ? java.util.List.of() : user.role().permissions());
    }

    /** 登录请求体。 */
    public record LoginRequest(String username, String password) {
    }
}
