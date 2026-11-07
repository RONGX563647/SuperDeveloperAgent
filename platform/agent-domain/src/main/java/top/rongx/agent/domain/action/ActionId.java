package top.rongx.agent.domain.action;

import java.util.Arrays;
import java.util.Optional;
import top.rongx.agent.domain.tool.ToolLevel;

/**
 * 动作目录（方案 D 的"词汇表"）。
 *
 * <p>这是流水线的"词汇表"：stage 只能引用这些动作，不能写任意命令。
 */
public enum ActionId {

    BACKEND_BUILD("backend_build", ToolLevel.L2_EXECUTE_ISOLATED),
    BACKEND_TEST("backend_test", ToolLevel.L2_EXECUTE_ISOLATED),
    FRONTEND_BUILD("frontend_build", ToolLevel.L2_EXECUTE_ISOLATED),
    IMAGE_PACKAGE("image_package", ToolLevel.L2_EXECUTE_ISOLATED),
    STACK_DEPLOY("stack_deploy", ToolLevel.L3_EXECUTE_PROD),
    STACK_ROLLBACK("stack_rollback", ToolLevel.L3_EXECUTE_PROD);

    private final String wireName;
    private final ToolLevel level;

    ActionId(String wireName, ToolLevel level) {
        this.wireName = wireName;
        this.level = level;
    }

    /** 对外（工具参数、配方文件）使用的名字。 */
    public String wireName() {
        return wireName;
    }

    public ToolLevel level() {
        return level;
    }

    /** 是否允许 agent 直接执行（L3 必须走变更确认）。 */
    public boolean autoExecutable() {
        return level != ToolLevel.L3_EXECUTE_PROD;
    }

    /** 解析外部输入；非法名字返回空（不抛异常，便于工具返回可读错误）。 */
    public static Optional<ActionId> fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase();
        return Arrays.stream(values()).filter(a -> a.wireName.equals(normalized)).findFirst();
    }
}
