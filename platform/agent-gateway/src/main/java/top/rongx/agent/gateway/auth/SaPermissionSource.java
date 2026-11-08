package top.rongx.agent.gateway.auth;

import cn.dev33.satoken.stp.StpInterface;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 权限数据源：告诉框架「这个登录用户有哪些权限码」。
 *
 * <p>权限码来自账号角色（{@link Role}）；"*" 表示全部（仅 admin）。
 */
@Component
public class SaPermissionSource implements StpInterface {

    private final UserStore users;

    public SaPermissionSource(UserStore users) {
        this.users = users;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        UserStore.User user = users.find(String.valueOf(loginId));
        return user == null ? List.of() : new ArrayList<>(user.role().permissions());
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        UserStore.User user = users.find(String.valueOf(loginId));
        return user == null ? List.of() : List.of(user.role().code());
    }
}
