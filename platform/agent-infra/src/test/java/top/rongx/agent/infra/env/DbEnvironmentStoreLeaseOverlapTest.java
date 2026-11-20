package top.rongx.agent.infra.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import top.rongx.agent.domain.env.ServiceLease;
import top.rongx.agent.infra.security.SecretBox;

/**
 * BUG-43：租约覆盖可见性——旧 RUNNING 租约被不同 pid 覆盖时必须出现 warn 日志。
 */
class DbEnvironmentStoreLeaseOverlapTest {

    @TempDir
    Path workspace;

    private DbEnvironmentStore store() {
        return new DbEnvironmentStore(workspace.toString(),
                new SecretBox(workspace.toString(), ""));
    }

    @Test
    @DisplayName("覆盖 RUNNING 租约（pid 变化）→ 出现『本地服务租约被覆盖』warn；STOPPED 覆盖不告警")
    void overwriteRunningLeaseLogsWarn() {
        DbEnvironmentStore store = store();
        Logger logger = (Logger) LoggerFactory.getLogger(DbEnvironmentStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            store.saveServiceLease(new ServiceLease("p1", "dev", "backend", 18080,
                    "RUNNING", 111, 1L));
            // 不同 pid 覆盖 RUNNING → warn
            store.saveServiceLease(new ServiceLease("p1", "dev", "backend", 18081,
                    "RUNNING", 222, 2L));
            List<String> warns = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("本地服务租约被覆盖"))
                    .toList();
            assertEquals(1, warns.size());
            assertTrue(warns.get(0).contains("111") && warns.get(0).contains("222"));
            // STOPPED 覆盖 → 不再告警
            int before = appender.list.size();
            store.saveServiceLease(new ServiceLease("p1", "dev", "backend", 18081,
                    "STOPPED", 0, 3L));
            long newWarns = appender.list.subList(before, appender.list.size()).stream()
                    .filter(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("本地服务租约被覆盖"))
                    .count();
            assertEquals(0, newWarns);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("同 pid 刷新 RUNNING 不告警（探活/状态刷新不是覆盖）")
    void samePidRefreshDoesNotWarn() {
        DbEnvironmentStore store = store();
        Logger logger = (Logger) LoggerFactory.getLogger(DbEnvironmentStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            store.saveServiceLease(new ServiceLease("p1", "dev", "backend", 18080,
                    "RUNNING", 333, 1L));
            store.saveServiceLease(new ServiceLease("p1", "dev", "backend", 18080,
                    "RUNNING", 333, 2L));
            assertEquals(0, appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("本地服务租约被覆盖"))
                    .count());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
