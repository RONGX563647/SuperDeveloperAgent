package top.rongx.agent.gateway.auth;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

/**
 * 当前登录身份的统一出口。
 *
 * <p>所有控制器用这里取 userId，替代原来的 {@code X-User-Id} 请求头——
 * 身份由 Sa-Token 会话保证，客户端无法再伪造。
 */
@Component
public class CurrentUser {

    /** 取当前登录 userId；未登录时抛 Sa-Token 异常（由拦截器提前拦下，这里兜底）。 */
    public String requireUserId() {
        StpUtil.checkLogin();
        return StpUtil.getLoginIdAsString();
    }

    /** 当前用户是否拥有某权限码（"*" 通配全部）。 */
    public boolean hasPermission(String permission) {
        return StpUtil.hasPermission(permission);
    }

    /** 校验权限码；无权限抛异常 → 统一错误处理器返回 403。 */
    public void checkPermission(String permission) {
        StpUtil.checkPermission(permission);
    }
}
