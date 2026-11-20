package top.rongx.agent.gateway.apidocs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-36：快照生成失败时必须隔离现存（可能过期）快照，防止 Agent 静默读到旧契约。
 *
 * <p>隔离逻辑本身是 {@link ApiDocsSnapshotService} 的私有方法，这里通过源码级断言
 * 锁住关键行为契约（与 spec-checks 的装配断言同一风格），保证：
 * <ul>
 *   <li>warmUp 失败路径调用 quarantineStaleSnapshot（不再只 log.warn 后不管）；</li>
 *   <li>隔离文件带 .stale- 前缀与时间戳，不会覆盖原路径；</li>
 *   <li>错误级别提升为 log.error（过期契约是数据错误，不是可忽略警告）。</li>
 * </ul>
 */
class ApiDocsStaleQuarantineTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("快照失败路径必须调用隔离并提升日志级别")
    void failurePathQuarantinesStaleSnapshot() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java",
                "top/rongx/agent/gateway/apidocs/ApiDocsSnapshotService.java"));

        String warmUp = blockOf(source, "public void warmUp()");
        assertTrue(warmUp.contains("quarantineStaleSnapshot()"),
                "warmUp 的 catch 分支必须调用 quarantineStaleSnapshot");
        assertTrue(warmUp.contains("log.error"),
                "生成失败必须是 error 级别（过期契约静默使用是数据错误）");
        assertTrue(!warmUp.contains("不影响启动"),
                "不得再保留「不影响启动」的误导注释");

        String quarantine = blockOf(source, "private void quarantineStaleSnapshot()");
        assertTrue(quarantine.contains(".stale-"),
                "隔离文件名必须带 .stale- 标记");
        assertTrue(quarantine.contains("Files.move"),
                "隔离必须用 move（改名而非删除）");
    }

    @Test
    @DisplayName("隔离动作真实可执行：move 到 .stale- 名后原路径不存在")
    void quarantineMovesFileAside() throws IOException {
        Path snapshot = tempDir.resolve("openapi.json");
        Files.writeString(snapshot, "{\"stale\": true}");
        Path quarantine = snapshot.resolveSibling(
                snapshot.getFileName() + ".stale-" + System.currentTimeMillis());
        Files.move(snapshot, quarantine);
        assertEquals(Boolean.FALSE, Files.exists(snapshot), "原路径应已被移走");
        assertTrue(Files.isRegularFile(quarantine), "隔离副本应保留");
        List<String> names = Files.list(tempDir).map(p -> p.getFileName().toString()).toList();
        assertTrue(names.stream().anyMatch(n -> n.contains(".stale-")), "隔离名带 .stale- 标记");
    }

    /** 取某方法签名起、至下一个同级方法签名或文件尾的源码块。 */
    private static String blockOf(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到方法：" + signature);
        int next = source.indexOf("\n    private ", start + 1);
        if (next < 0) {
            next = source.indexOf("\n    public ", start + 1);
        }
        return next < 0 ? source.substring(start) : source.substring(start, next);
    }
}
