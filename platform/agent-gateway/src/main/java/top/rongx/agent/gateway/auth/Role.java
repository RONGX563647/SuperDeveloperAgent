package top.rongx.agent.gateway.auth;

import java.util.List;

/**
 * 角色定义（权限体系的最小集合，够用即可，不做成可配置数据库表）。
 *
 * <p>映射到 Sa-Token 的权限码，由 {@link UserStore} 在登录时授予。
 */
public enum Role {

    /** 管理员：全部权限（含用户管理预留）。 */
    ADMIN("admin", List.of("*")),

    /**
     * 研发运维：可触发构建、执行 L2 动作、发起并执行发布/回滚；可只读设置面板，
     * 并允许查看环境变量明文（写审计日志），但不具备修改环境配置的写权限。
     */
    DEVOPS("devops", List.of(
            "chat", "ci:read", "ci:run", "action:read", "action:run",
            "change:request", "change:confirm", "change:apply",
            "settings:read", "settings:reveal")),

    /** 只读：看流水线/历史/日志，执行任何写操作都会被拦。 */
    VIEWER("viewer", List.of("chat", "ci:read", "action:read"));

    private final String code;
    private final List<String> permissions;

    Role(String code, List<String> permissions) {
        this.code = code;
        this.permissions = List.copyOf(permissions);
    }

    public String code() {
        return code;
    }

    public List<String> permissions() {
        return permissions;
    }

    public static Role of(String code) {
        for (Role r : values()) {
            if (r.code.equalsIgnoreCase(code)) {
                return r;
            }
        }
        throw new IllegalArgumentException("未知角色: " + code);
    }
}
