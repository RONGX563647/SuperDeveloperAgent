package top.rongx.agent.domain.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** BUG-40/42 共享身份判定 helper：三元组读写、startInstant 容差、legacy 探针、默认不杀。 */
class ProcessIdentityTest {

    private static long currentStartMs() {
        return ProcessHandle.current().info().startInstant()
                .map(Instant::toEpochMilli).orElse(0L);
    }

    @Test
    @DisplayName("JSON roundtrip：pid/startInstant/token/command 全保留；特殊字符转义安全")
    void roundtrip(@TempDir Path dir) throws Exception {
        ProcessIdentity.writePidRecord(dir, "frontend",
                new ProcessIdentity.PidRecord(7, 1_700_000_000_000L,
                "to\"k\\1", "node server \"a b\" \\x"));
        Optional<ProcessIdentity.PidRecord> r = ProcessIdentity.readPidRecord(dir, "frontend");
        assertTrue(r.isPresent());
        assertEquals(7, r.get().pid());
        assertEquals(1_700_000_000_000L, r.get().startInstantEpochMs());
        assertEquals("to\"k\\1", r.get().token());
        assertEquals("node server \"a b\" \\x", r.get().command());
    }

    @Test
    @DisplayName("读取兼容：损坏 JSON / 非数字 legacy 文件返回 empty；两文件都删干净")
    void brokenFilesReturnEmpty(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(ProcessIdentity.pidFile(dir, "backend"), "{\"pid\":");
        assertTrue(ProcessIdentity.readPidRecord(dir, "backend").isEmpty());
        Files.writeString(ProcessIdentity.legacyPidFile(dir, "backend"), "abc");
        assertTrue(ProcessIdentity.readPidRecord(dir, "backend").isEmpty());
        // 负数/零 legacy pid 也按空处理
        Files.writeString(ProcessIdentity.legacyPidFile(dir, "backend"), "0");
        assertTrue(ProcessIdentity.readPidRecord(dir, "backend").isEmpty());
        ProcessIdentity.deletePidFiles(dir, "backend");
        assertFalse(Files.exists(ProcessIdentity.pidFile(dir, "backend")));
        assertFalse(Files.exists(ProcessIdentity.legacyPidFile(dir, "backend")));
    }

    @Test
    @DisplayName("startInstant 匹配：±2s 容差内 true，超出 false（PID 复用判据）")
    void startInstantTolerance() {
        long now = currentStartMs();
        long pid = ProcessHandle.current().pid();
        Optional<Instant> real = ProcessHandle.current().info().startInstant();
        assertTrue(ProcessIdentity.matches(Optional.of("java -jar x.jar"), real,
                new ProcessIdentity.PidRecord(pid, now - 1_000, null, null), List.of()));
        assertTrue(ProcessIdentity.matches(Optional.of("java -jar x.jar"), real,
                new ProcessIdentity.PidRecord(pid, now + 2_000, null, null), List.of()));
        assertFalse(ProcessIdentity.matches(Optional.of("java -jar x.jar"), real,
                new ProcessIdentity.PidRecord(pid, now - 2_001, null, null), List.of()));
        // 记录有 startInstant 但实际读不到 → false（默认不杀）
        assertFalse(ProcessIdentity.matches(Optional.of("java -jar x.jar"), Optional.empty(),
                new ProcessIdentity.PidRecord(pid, now, null, null), List.of()));
    }

    @Test
    @DisplayName("legacy 记录：命令行包含路径片段或注入 token 才 true；读不到命令行 false")
    void legacyProbe() {
        ProcessIdentity.PidRecord legacy = new ProcessIdentity.PidRecord(1, 0, "tok-9", null);
        assertTrue(ProcessIdentity.matchesCommandLine(Optional.of("java -jar /r/.agent/deploy/ry-dev/app.jar"),
                List.of("ry-dev")));
        assertFalse(ProcessIdentity.matchesCommandLine(Optional.empty(), List.of("ry-dev")));
        assertFalse(ProcessIdentity.matchesCommandLine(Optional.of("  "), List.of("ry-dev")));
        assertTrue(ProcessIdentity.tokenMatches(
                Optional.of("java -Dagent.dev.token=tok-9 -jar x.jar"), legacy));
        assertFalse(ProcessIdentity.tokenMatches(Optional.of("java -jar x.jar"), legacy));
        // legacy 记录路径走 matches：token 命中即可
        assertTrue(ProcessIdentity.matches(Optional.of("java -Dagent.dev.token=tok-9"), Optional.empty(),
                legacy, List.of()));
    }

    @Test
    @DisplayName("部署目录命名：与 LocalDevHostDeployer.stateDir 完全一致（双账本同一路径）")
    void deployDirNameMatchesStateDir() {
        assertEquals("ry-dev", ProcessIdentity.deployDirName("RY", "dev"));
        assertEquals("ry", ProcessIdentity.deployDirName("RY", ""));
        assertEquals("p-1--dev", ProcessIdentity.deployDirName("p 1!", "dev"));
    }
}
