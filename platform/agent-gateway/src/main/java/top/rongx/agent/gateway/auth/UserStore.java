package top.rongx.agent.gateway.auth;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;

/**
 * 用户仓库（内存版）。
 *
 * <p>内网平台 + 3 个种子账号，先用内存表把「真实身份 + 角色」立起来；
 * 换数据库时只需替换本类为 DAO 实现，Sa-Token 的 StpInterface 不变。
 *
 * <p>密码用 BCrypt 存哈希（spring-security-crypto），明文不落任何地方。
 */
@Component
public class UserStore {

    /** 一个平台账号。 */
    public record User(String userId, String displayName, String bcryptHash,
                       Role role, List<String> projects) {
    }

    private final Map<String, User> users = new ConcurrentHashMap<>();

    public UserStore() {
        // 种子账号：userId / displayName / 明文（仅用于生成哈希，见使用指南）/ 角色 / 可见项目
        register("admin", "管理员", "admin-2026", Role.ADMIN, List.of("RY"));
        register("devops", "运维开发", "devops-2026", Role.DEVOPS, List.of("RY"));
        register("viewer", "访客", "viewer-2026", Role.VIEWER, List.of("RY"));
    }

    private void register(String userId, String displayName, String raw, Role role, List<String> projects) {
        users.put(userId, new User(userId, displayName, BCrypt.hashpw(raw, BCrypt.gensalt()), role, projects));
    }

    /** 校验密码（常量时间比较由 BCrypt.checkpw 保证）。 */
    public boolean checkPassword(String userId, String raw) {
        User user = users.get(userId);
        return user != null && BCrypt.checkpw(raw, user.bcryptHash());
    }

    /** 按登录名取用户；不存在返回 null。 */
    public User find(String userId) {
        return users.get(userId);
    }

    /** 全部用户（管理面板预留）。 */
    public List<User> all() {
        return List.copyOf(users.values());
    }
}
