package top.rongx.agent.domain.policy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import top.rongx.agent.domain.error.ErrorCode;
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

    /**
     * 归一化后的破坏性命令片段：匹配基于「token 归一化」后的命令串，
     * 不再是原始拼接串的 contains。归一化规则见 {@link #normalizeCommand}：
     * 压多空格 / 合并 rm 的短旗标（rm -r -f → rm -rf）/ --force ≡ -f 等价 / 大小写折叠。
     */
    private static final List<String> DESTRUCTIVE_COMMANDS = List.of(
            // rm：r/f 任意顺序、长短旗标全部归一到 "rm -rf"
            "rm -rf", "rm -r", "rm -f", "rm --recursive", "rmdir",
            // SQL
            "drop table", "drop database", "truncate table", "delete from",
            // 编排 / 容器
            "kubectl delete", "docker system prune", "docker volume rm", "docker rm -f",
            "docker rmi", "docker rm",
            // git
            "git reset --hard", "git clean -fd", "git clean -fdx", "git clean -f",
            "git push -f", "git push -uf",
            // find 的删除形态：-delete 归一成 find-delete 标记；-exec rm 原样命中
            "find-delete", "-exec rm",
            // 权限 / 磁盘 / 系统
            "chmod -r 777 /", "chmod 777 /", "mkfs", "dd if=", "shutdown", "reboot",
            // 数据库迁移清理
            "flyway:clean", "liquibase:dropall");

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
        // 词边界匹配：分隔符含下划线/连字符（db_reset、clean_workspace 照样命中），
        // 但 "deleted_flag" 这种"关键词是更长单词一部分"的形态不再误报
        return DESTRUCTIVE_KEYWORDS.stream().anyMatch(kw ->
                java.util.regex.Pattern.compile("(^|[^a-z0-9])" + java.util.regex.Pattern.quote(kw)
                                + "([^a-z0-9]|$)")
                        .matcher(normalized).find());
    }

    /**
     * 配方命令包含破坏性片段（如 rm -rf / drop table / git reset --hard）。
     *
     * <p>判定基于<b>归一化后的命令串</b>：先压空白、合并等价旗标（rm -r -f → rm -rf、
     * --force → -f、git push 的 -f/--force、大小写折叠），再做片段匹配。
     * 这样 {@code rm -r -f /tmp}、{@code git push --force}、{@code find . -delete}、
     * {@code DROP  TABLE}（多空格）这些等价写法都能命中，而不再依赖字面相邻。
     */
    public boolean isDestructiveCommand(String commands) {
        if (commands == null || commands.isBlank()) {
            return false;
        }
        String normalized = normalizeCommand(commands);
        return DESTRUCTIVE_COMMANDS.stream().anyMatch(normalized::contains);
    }

    /**
     * 命令串归一化（每行独立归一，行间用 "\n" 保留边界）：
     * <ol>
     *   <li>大小写折叠；</li>
     *   <li>合并连续空白为单空格（含全角空格与 tab）；</li>
     *   <li>rm 的短旗标按字母序合并：{@code rm -r -f} → {@code rm -rf}；</li>
     *   <li>等价旗标统一：{@code --force} ≡ {@code -f}（git push / rm / docker rm 语义一致）；
     *       {@code --recursive} ≡ {@code -r}；{@code --directory}（rmdir）不变；</li>
     * </ol>
     */
    static String normalizeCommand(String commands) {
        StringBuilder out = new StringBuilder();
        for (String line : commands.toLowerCase(Locale.ROOT).split("\\R", -1)) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(normalizeLine(line));
        }
        return out.toString();
    }

    private static String normalizeLine(String line) {
        // 折叠空白
        String compact = line.replaceAll("[\\s\\u3000]+", " ").trim();
        String[] tokens = compact.split(" ", -1);
        // rm 系：合并短旗标（rm -r -f → rm -rf；rm -f -r → rm -rf）
        if (tokens.length > 1 && (tokens[0].equals("rm") || tokens[0].equals("rmdir"))) {
            StringBuilder flags = new StringBuilder();
            java.util.List<String> rest = new java.util.ArrayList<>();
            for (int i = 1; i < tokens.length; i++) {
                String t = tokens[i];
                if (t.equals("--force")) {
                    flags.append('f');
                } else if (t.equals("--recursive")) {
                    flags.append('r');
                } else if (t.length() >= 2 && t.charAt(0) == '-' && t.charAt(1) != '-'
                        && t.matches("-[a-zA-Z]+")) {
                    // 短旗标簇：把每个字母收集起来（rm -rf 本身也是簇）
                    for (char c : t.substring(1).toCharArray()) {
                        flags.append(c);
                    }
                } else {
                    rest.add(t);
                }
            }
            StringBuilder merged = new StringBuilder(tokens[0]);
            if (flags.length() > 0) {
                // 排序后拼接，保证 -f -r 与 -r -f 归一到同一形态
                char[] chars = flags.toString().toCharArray();
                java.util.Arrays.sort(chars);
                merged.append(" -").append(chars);
            }
            for (String r : rest) {
                merged.append(' ').append(r);
            }
            compact = merged.toString();
        } else {
            // 通用等价归一：--force → -f（git push --force、docker rm --force 等）
            StringBuilder merged = new StringBuilder();
            for (int i = 0; i < tokens.length; i++) {
                if (i > 0) {
                    merged.append(' ');
                }
                String t = tokens[i];
                if ("--force".equals(t) || "--recursive".equals(t)) {
                    merged.append(t.equals("--force") ? "-f" : "-r");
                } else if ("-uf".equals(t) || "-fu".equals(t)) {
                    // git push 的 upstream+force 缩写：归一到含 -f 的形态
                    merged.append("-uf");
                } else if (t.length() > 2 && t.startsWith("--") && !t.contains("=")) {
                    // 其余长旗标按原样保留（git push --force-with-lease 等不做归一）
                    merged.append(t);
                } else {
                    merged.append(t);
                }
            }
            compact = merged.toString();
        }
        // find … -delete：-delete 一定跟在 find 命令行里，片段 "find -delete" 命中不了
        // 中间参数的形态，所以追加一条 "<find 行> 含 -delete" 的判定在调用侧做；
        // 这里只补一个可匹配的固定形态：把 " -delete" 规范为 " find-delete" 标记
        if (compact.startsWith("find ") && (compact.contains(" -delete ")
                || compact.endsWith(" -delete"))) {
            compact = compact + " find-delete";
        }
        return compact;
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
                throw new PolicyDeniedException(ErrorCode.RATE_LIMITED,
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
