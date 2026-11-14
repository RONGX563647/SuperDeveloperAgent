package top.rongx.agent.runtime;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 会话与项目/环境的绑定表。
 *
 * <p>会话状态是由 {@code sessionId} 定位的，但 {@code sessionId} 来自客户端。如果只按
 * {@code (userId, sessionId)} 读写状态，前端切换项目时复用同一个 {@code sessionId}，就会把
 * A 项目的对话上下文带进 B 项目。
 *
 * <p>这里在第一次使用某个会话时记录它所属的项目/环境/用户，后续请求必须一致；
 * 不一致直接拒绝，要求新建会话。绑定只保存在内存中：进程重启后旧会话按首次使用重新绑定。
 */
@Component
public class ChatSessionRegistry {

    /** 会话绑定的最小上下文。 */
    public record Binding(String userId, String projectId, String env) {
    }

    private final ConcurrentHashMap<String, Binding> bindings = new ConcurrentHashMap<>();

    /**
     * 绑定或校验会话。
     *
     * @throws IllegalArgumentException 会话已绑定到其他用户/项目/环境
     */
    public Binding bindOrVerify(String sessionId, ProjectContext ctx) {
        Binding incoming = new Binding(ctx.userId(), ctx.projectId(), ctx.env());
        Binding existing = bindings.putIfAbsent(sessionId, incoming);
        if (existing != null && !existing.equals(incoming)) {
            throw new IllegalArgumentException(
                    "会话 " + sessionId + " 已绑定到其他项目/环境，请新建会话后再试");
        }
        return existing == null ? incoming : existing;
    }

    /** 会话是否已绑定（只读诊断用）。 */
    public boolean contains(String sessionId) {
        return sessionId != null && bindings.containsKey(sessionId);
    }

    /** 删除绑定（会话被删除时调用；未绑定则无操作）。 */
    public void unbind(String sessionId) {
        if (sessionId != null) {
            bindings.remove(sessionId);
        }
    }
}
