package top.rongx.agent.domain.conversation;

import java.util.Locale;

/**
 * 工作区角色 Worker：把"角色"收敛成枚举，能力差异由 AgentFactory 的裁剪配置决定，
 * 不需要为每个角色新建一个类。
 *
 * <p>{@link #parse(String)} 用于把会话 / 调度节点保存的角色字符串映射成枚举；
 * 未知值（例如普通会话的 {@code chat}）返回 {@code null}，调用方退回通用 Agent。
 */
public enum WorkerRole {
    PRODUCT,
    DEV,
    TEST,
    OPS;

    /** 角色字符串 → 枚举；忽略大小写，未知返回 null。 */
    public static WorkerRole parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "product" -> PRODUCT;
            case "dev" -> DEV;
            case "test" -> TEST;
            case "ops", "deploy" -> OPS;
            default -> null;
        };
    }
}
