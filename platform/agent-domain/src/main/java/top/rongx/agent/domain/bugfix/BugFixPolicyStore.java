package top.rongx.agent.domain.bugfix;

/**
 * 自动修复策略的持久化端口。
 *
 * <p>{@code load()} / {@code save()} 读写平台级默认值；带 {@code projectId} 的重载读写
 * 项目级覆盖，项目未配置时回退平台默认值（见实现）。
 */
public interface BugFixPolicyStore {

    BugFixPolicy load();

    BugFixPolicy save(BugFixPolicy policy);

    default BugFixPolicy load(String projectId) {
        return load();
    }

    default BugFixPolicy save(String projectId, BugFixPolicy policy) {
        return save(policy);
    }
}
