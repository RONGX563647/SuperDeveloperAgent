package top.rongx.agent.tools.cache;

import io.agentscope.core.message.ToolResultBlock;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 只读工具结果的语义缓存（进程内，无外部依赖）。
 *
 * <p>为什么不用向量库：只读工具的参数通常是"文件名 / 关键词 / 环境名"这类短文本，
 * 归一化 + 词元 Jaccard 相似度已经能覆盖"换个说法再问一遍"的场景，且无需额外服务、
 * 不会引入 embedding 延迟。相似度阈值默认 0.9，宁可多查一次也不返回错的结果。
 *
 * <p>失效策略：
 * <ul>
 *   <li>TTL：默认 90 秒，避免长期持有过期状态；</li>
 *   <li>写操作：任何非只读工具成功执行后清空同 scope 的缓存（scope 含 project/env/worktree/user）；</li>
 *   <li>容量：LRU 上限，防止长会话把内存吃满。</li>
 * </ul>
 */
@Component
public class ToolResultCache {

    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9_./:-]+");

    private record CachedResult(String scope, String tool, String normalized, Set<String> tokens,
                                ToolResultBlock block, long expiresAt) {
    }

    private final boolean enabled;
    private final long ttlMillis;
    private final int maxEntries;
    private final double similarity;
    private final int maxOutputChars;
    /** 按工具覆盖输出预算（工具名 -> 字符上限）；未列出的工具用 {@link #maxOutputChars}。 */
    private final Map<String, Integer> outputBudgets;
    /** 结果本身随时间变化、命中会误导决策的工具：不缓存（仍参与输出预算）。 */
    private final Set<String> excluded;

    private final Map<String, CachedResult> entries = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedResult> eldest) {
            return size() > maxEntries;
        }
    };

    private long hits;
    private long misses;
    private long invalidations;

    public ToolResultCache(@Value("${agent.tools.cache.enabled:true}") boolean enabled,
                           @Value("${agent.tools.cache.ttl-seconds:90}") long ttlSeconds,
                           @Value("${agent.tools.cache.max-entries:512}") int maxEntries,
                           @Value("${agent.tools.cache.similarity:0.9}") double similarity,
                           @Value("${agent.tools.max-output-chars:8000}") int maxOutputChars,
                           @Value("${agent.tools.output-budgets:project_info=2000,deploy_env_status=2000,"
                                   + "app_logs=12000,read_run_log=16000,git_diff=16000}")
                           String outputBudgets,
                           @Value("${agent.tools.cache.exclude:conversation_wait,conversation_status,"
                                   + "conversation_summarize,workspace_snapshot,ci_status}")
                           String excludedTools) {
        this.enabled = enabled;
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
        this.maxEntries = Math.max(16, maxEntries);
        this.similarity = Math.min(1.0, Math.max(0.5, similarity));
        this.maxOutputChars = Math.max(1_000, maxOutputChars);
        this.outputBudgets = parseBudgets(outputBudgets);
        this.excluded = new HashSet<>();
        if (excludedTools != null) {
            for (String name : excludedTools.split(",")) {
                if (!name.isBlank()) {
                    excluded.add(name.trim());
                }
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** 运行中/状态类工具结果变化快，缓存命中会返回过期状态，一律不缓存。 */
    public boolean cacheable(String tool) {
        return enabled && tool != null && !excluded.contains(tool);
    }

    public int maxOutputChars() {
        return maxOutputChars;
    }

    /**
     * 单个工具的输出预算：manifest / 状态类小结果不需要几千字，
     * 而 diff / 日志又必须留够上下文——统一阈值会两头都亏。
     */
    public int maxOutputChars(String tool) {
        Integer custom = tool == null ? null : outputBudgets.get(tool);
        return custom == null ? maxOutputChars : custom;
    }

    /** 解析 {@code tool=chars,tool=chars}；坏条目直接忽略，不影响启动。 */
    private static Map<String, Integer> parseBudgets(String config) {
        Map<String, Integer> out = new java.util.HashMap<>();
        if (config == null) {
            return out;
        }
        for (String item : config.split(",")) {
            int eq = item.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = item.substring(0, eq).trim();
            String value = item.substring(eq + 1).trim();
            if (name.isEmpty() || value.isEmpty()) {
                continue;
            }
            try {
                int chars = Integer.parseInt(value);
                if (chars >= 500) {
                    out.put(name, chars);
                }
            } catch (NumberFormatException ignored) {
                // 配置写错时按默认预算处理
            }
        }
        return out;
    }

    /** 命中返回缓存的原始结果（调用方需要重写 id/name），未命中返回 null。 */
    public synchronized ToolResultBlock lookup(String scope, String tool, Map<String, Object> args) {
        if (!enabled || scope == null) {
            return null;
        }
        String normalized = normalize(args);
        Set<String> tokens = tokens(normalized);
        long now = System.currentTimeMillis();
        String bestKey = null;
        double bestScore = 0;
        for (Iterator<Map.Entry<String, CachedResult>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, CachedResult> item = it.next();
            CachedResult entry = item.getValue();
            if (entry.expiresAt() <= now) {
                it.remove();
                continue;
            }
            if (!entry.scope().equals(scope) || !entry.tool().equals(tool)) {
                continue;
            }
            if (entry.normalized().equals(normalized)) {
                hits++;
                return entry.block();
            }
            double score = jaccard(tokens, entry.tokens());
            if (score >= similarity && score > bestScore) {
                bestScore = score;
                bestKey = item.getKey();
            }
        }
        if (bestKey != null) {
            hits++;
            return entries.get(bestKey).block();
        }
        misses++;
        return null;
    }

    public synchronized void store(String scope, String tool, Map<String, Object> args,
                                   ToolResultBlock block) {
        if (!enabled || scope == null || block == null
                || block.getState() == io.agentscope.core.message.ToolResultState.ERROR) {
            return;
        }
        String normalized = normalize(args);
        String key = scope + '|' + tool + '|' + normalized;
        entries.put(key, new CachedResult(scope, tool, normalized, tokens(normalized), block,
                System.currentTimeMillis() + ttlMillis));
    }

    /** 写操作成功后调用：同一 scope 下的只读结果全部作废。 */
    public synchronized void invalidateScope(String scope) {
        if (scope == null) {
            return;
        }
        boolean removed = entries.entrySet().removeIf(item -> item.getValue().scope().equals(scope));
        if (removed) {
            invalidations++;
        }
    }

    /** 观测用：命中率能直接说明语义缓存是否真的在省调用。 */
    public synchronized String stats() {
        long total = hits + misses;
        String rate = total == 0 ? "-" : String.format(Locale.ROOT, "%.2f", (double) hits / total);
        return "hits=" + hits + " misses=" + misses + " hitRate=" + rate
                + " invalidations=" + invalidations + " size=" + entries.size();
    }

    static String normalize(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        args.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (sb.length() > 0) {
                        sb.append('&');
                    }
                    sb.append(entry.getKey()).append('=').append(value(entry.getValue()));
                });
        return sb.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static String value(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            map.forEach((k, v) -> {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                sb.append(k).append(':').append(value(v));
            });
            return sb.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder sb = new StringBuilder("[");
            iterable.forEach(item -> {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                sb.append(value(item));
            });
            return sb.append(']').toString();
        }
        return String.valueOf(value).trim();
    }

    /** ASCII 词 + 中文单字/相邻双字：短参数下比整句相似度稳定。 */
    static Set<String> tokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher matcher = ASCII_WORD.matcher(lower);
        while (matcher.find()) {
            String word = matcher.group();
            if (word.length() >= 2) {
                tokens.add(word);
            }
        }
        char previous = 0;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                tokens.add(String.valueOf(c));
                if (previous != 0) {
                    tokens.add(previous + String.valueOf(c));
                }
                previous = c;
            } else {
                previous = 0;
            }
        }
        return tokens;
    }

    static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0;
        }
        int intersection = 0;
        for (String token : left) {
            if (right.contains(token)) {
                intersection++;
            }
        }
        int union = left.size() + right.size() - intersection;
        return union == 0 ? 1.0 : (double) intersection / union;
    }
}
