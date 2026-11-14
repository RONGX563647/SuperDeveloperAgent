package top.rongx.agent.domain.policy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 非生产环境的破坏性动作防护。
 *
 * <p>dev/test 的 L2 默认 ALLOW，且对话 Agent 使用 BYPASS 权限模式；一旦提示注入诱导模型
 * 连续调用"重置/删除/迁移"类动作，沙箱只能防宿主逃逸，防不了对环境本身的破坏。这里补一层
 * 平台级约束：
 * <ul>
 *   <li>破坏性动作即使在 dev/test 也必须走人工确认（登记变更 → 确认卡 → 带令牌 apply）；</li>
 *   <li>同一操作人对同一动作，滑动窗口内最多执行 N 次，防止重试风暴。</li>
 * </ul>
 *
 * <p>计数是进程内的：多实例下每个实例各自计数，配合网关限流使用即可。
 * 放在 domain 层是为了让 tools 只依赖 domain（规约 §2.1）。
 */
public class DangerousActionPolicy {

    private static final Set<String> DESTRUCTIVE_KEYWORDS = Set.of(
            "reset", "delete", "drop", "clean", "rollback", "migrate",
            "purge", "truncate", "destroy", "force", "restore", "seed", "fixture");

    /** 配方命令里的破坏性片段：模型即使"挑选"一个正常动作，也拦不住配方本身干坏事。 */
    private static final List<String> DESTRUCTIVE_COMMANDS = List.of(
            "rm -rf", "rm -fr", "rm -f", "rm --recursive", "rmdir ",
            "drop table", "drop database", "truncate table", "delete from",
            "kubectl delete", "docker system prune", "docker volume rm", "docker rm -f",
            "git reset --hard", "git clean -fd", "git clean -fdx",
            "flyway:clean", "liquibase:dropall", "mkfs", "dd if=", "shutdown", "reboot");

    private final int maxPerWindow;
    private final long windowMillis;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public DangerousActionPolicy(int maxPerWindow, long windowSeconds) {
        this.maxPerWindow = Math.max(1, maxPerWindow);
        this.windowMillis = Math.max(10, windowSeconds) * 1000L;
    }

    /** 动作名包含破坏性关键词即视为破坏性动作（如 db_reset / clean_workspace / migrate）。 */
    public boolean isDestructive(String action) {
        if (action == null || action.isBlank()) {
            return false;
        }
        String normalized = action.toLowerCase(Locale.ROOT);
        return DESTRUCTIVE_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    /** 配方命令包含破坏性片段（如 rm -rf / drop table / git reset --hard）。 */
    public boolean isDestructiveCommand(String commands) {
        if (commands == null || commands.isBlank()) {
            return false;
        }
        String normalized = commands.toLowerCase(Locale.ROOT);
        return DESTRUCTIVE_COMMANDS.stream().anyMatch(normalized::contains);
    }

    /** 动作名或配方命令任一命中破坏性规则，就需要人工确认。 */
    public boolean requiresConfirmation(String action, String commands) {
        return isDestructive(action) || isDestructiveCommand(commands);
    }

    /** 频次闸门：同一操作人 + 项目 + 动作在窗口内超过上限直接拒绝。 */
    public void checkFrequency(ProjectContext ctx, String action, String commands) {
        if (!requiresConfirmation(action, commands)) {
            return;
        }
        String key = ctx.projectId() + "|" + ctx.userId() + "|" + action;
        long now = System.currentTimeMillis();
        Deque<Long> window = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > windowMillis) {
                window.pollFirst();
            }
            if (window.size() >= maxPerWindow) {
                throw new PolicyDeniedException("E-709",
                        "破坏性动作 " + action + " 在 " + (windowMillis / 60_000)
                                + " 分钟内已执行 " + window.size() + " 次，已触发频次上限，请稍后再试");
            }
            window.addLast(now);
        }
        // 简单防护：窗口过大的键不会无限增长（每次只保留窗口内数据）
        if (hits.size() > 10_000) {
            hits.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }
    }
}
