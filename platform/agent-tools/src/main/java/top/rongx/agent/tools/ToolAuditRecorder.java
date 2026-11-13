package top.rongx.agent.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.audit.ToolAudit;
import top.rongx.agent.domain.audit.ToolAuditStore;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 工具审计记录器：统一记录"谁在哪个环境调用了什么工具、参数哈希、结果与耗时"。
 *
 * <p>不落明文参数：参数先规范化再取 SHA-256 前 16 位，足以关联同一次调用，
 * 又不会把密钥、令牌或业务数据写进审计表。审计失败不影响主流程。
 */
@Component
public class ToolAuditRecorder {

    private final ToolAuditStore store;

    public ToolAuditRecorder(ToolAuditStore store) {
        this.store = store;
    }

    /** 包裹一次返回文本的工具调用。 */
    public String call(ProjectContext ctx, String tool, Object args, Supplier<String> body) {
        long start = System.nanoTime();
        String resultCode = "OK";
        try {
            String out = body.get();
            resultCode = isError(out) ? "ERROR" : "OK";
            return out;
        } catch (RuntimeException e) {
            resultCode = "EXCEPTION";
            throw e;
        } finally {
            write(ctx, tool, args, resultCode, elapsedMs(start));
        }
    }

    /** 包裹一次返回结构化结果的工具调用（例如需要缓存对象再序列化）。 */
    public <T> T call(ProjectContext ctx, String tool, Object args, Supplier<T> body,
                      java.util.function.Predicate<T> isError) {
        long start = System.nanoTime();
        String resultCode = "OK";
        try {
            T out = body.get();
            resultCode = isError.test(out) ? "ERROR" : "OK";
            return out;
        } catch (RuntimeException e) {
            resultCode = "EXCEPTION";
            throw e;
        } finally {
            write(ctx, tool, args, resultCode, elapsedMs(start));
        }
    }

    /** 记录一次被拒绝的调用（门禁直接拒绝时使用）。 */
    public void denied(ProjectContext ctx, String tool, Object args, String reason) {
        write(ctx, tool, args, "DENIED", 0);
    }

    private void write(ProjectContext ctx, String tool, Object args, String resultCode,
                       long durationMs) {
        store.record(new ToolAudit(
                "ta-" + UUID.randomUUID().toString().substring(0, 12),
                "",
                ctx == null ? "" : ctx.userId(),
                ctx == null ? "" : ctx.projectId(),
                ctx == null ? "" : ctx.env(),
                tool,
                hash(args),
                resultCode,
                durationMs,
                System.currentTimeMillis()));
    }

    /** 参数规范化哈希：Map 按 key 排序，其余按字符串；只取前 16 位十六进制。 */
    static String hash(Object args) {
        String canonical;
        if (args == null) {
            canonical = "";
        } else if (args instanceof Map<?, ?> map) {
            Map<String, String> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), String.valueOf(v)));
            canonical = sorted.toString();
        } else {
            canonical = String.valueOf(args);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(canonical.hashCode());
        }
    }

    private static boolean isError(String out) {
        if (out == null) {
            return false;
        }
        String trimmed = out.stripLeading();
        return trimmed.startsWith("err:") || trimmed.startsWith("error:")
                || trimmed.contains("\nstatus: FAILED") || trimmed.contains("\nstatus: DENIED")
                || trimmed.contains("status: NEED_USER_INPUT");
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
