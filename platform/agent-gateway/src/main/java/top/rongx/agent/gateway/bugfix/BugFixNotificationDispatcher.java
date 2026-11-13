package top.rongx.agent.gateway.bugfix;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.bugfix.BugFixNotification;
import top.rongx.agent.domain.bugfix.BugFixNotificationStore;
import top.rongx.agent.domain.bugfix.BugFixNotifier;
import top.rongx.agent.domain.bugfix.BugFixRunStore;

/**
 * 通知发件箱调度器：每 10 秒把到期的 Issue 评论投递出去，失败按指数退避重试。
 *
 * <p>用单线程虚拟线程轮询而不是 {@code @Scheduled}，避免和业务线程争用调度器，
 * 也方便在测试里直接调用 {@link #drainOnce()}。
 */
@Component
public class BugFixNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BugFixNotificationDispatcher.class);
    private static final int BATCH = 20;
    private static final int MAX_ATTEMPTS = 5;

    private final BugFixNotificationStore notifications;
    private final BugFixRunStore runs;
    private final BugFixNotifier notifier;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;

    public BugFixNotificationDispatcher(BugFixNotificationStore notifications,
                                        BugFixRunStore runs, BugFixNotifier notifier) {
        this.notifications = notifications;
        this.runs = runs;
        this.notifier = notifier;
    }

    @PostConstruct
    void start() {
        running.set(true);
        worker = Thread.ofVirtual().name("bugfix-notifier").start(() -> {
            while (running.get()) {
                try {
                    drainOnce();
                } catch (RuntimeException e) {
                    log.warn("通知投递轮询异常：{}", e.getMessage());
                }
                try {
                    Thread.sleep(10_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    /** 投递一批到期通知；返回成功条数（供测试断言）。 */
    public int drainOnce() {
        List<BugFixNotification> due = notifications.due(BATCH);
        int sent = 0;
        for (BugFixNotification notification : due) {
            var run = runs.find(notification.runId()).orElse(null);
            if (run == null) {
                // 运行已不存在（被清理）：直接标记完成，避免永远重试
                notifications.markSent(notification.id());
                continue;
            }
            try {
                notifier.comment(run, notification.message());
                notifications.markSent(notification.id());
                sent++;
            } catch (RuntimeException e) {
                notifications.markFailed(notification.id(), e.getMessage(), MAX_ATTEMPTS);
            }
        }
        return sent;
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
    }
}
