package top.rongx.agent.domain.port;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存审计实现（测试用）：线程安全，保留全部记录供断言。
 */
public class InMemoryAuditTrail implements AuditTrail {

    /** 一条已记录的审计事件（字段平铺，便于断言）。 */
    public record Event(String actor, String role, String action, String targetType,
                        String targetId, String projectId, String env, String result, String detail) {
    }

    private final List<Event> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(String actor, String role, String action, String targetType,
                       String targetId, String projectId, String env, String result, String detail) {
        events.add(new Event(actor, role, action, targetType, targetId, projectId, env, result, detail));
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public int count(String action, String targetId) {
        return (int) events.stream()
                .filter(e -> e.action().equals(action) && targetId.equals(e.targetId()))
                .count();
    }

    public void clear() {
        events.clear();
    }
}
