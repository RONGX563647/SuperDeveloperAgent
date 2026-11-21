package top.rongx.agent.domain.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 子进程 I/O 原语：输出裁剪、实时回调与进程树终止。 */
class ProcessIoTest {

    @Test
    @DisplayName("append 超出上限时只保留尾部")
    void appendKeepsTailWithinLimit() {
        StringBuilder sink = new StringBuilder();
        ProcessIo.append(sink, "a".repeat(ProcessIo.MAX_OUTPUT_CHARS));
        ProcessIo.append(sink, "TAIL");
        assertEquals(ProcessIo.MAX_OUTPUT_CHARS, sink.length());
        assertTrue(sink.toString().endsWith("TAIL"));
    }

    @Test
    @DisplayName("pump 按字符解码并把片段同步给实时回调")
    void pumpDecodesAndNotifiesLive() {
        StringBuilder sink = new StringBuilder();
        List<String> chunks = new ArrayList<>();
        byte[] bytes = "构建成功\n".getBytes(StandardCharsets.UTF_8);
        ProcessIo.pump(new ByteArrayInputStream(bytes), sink, chunks::add);
        assertEquals("构建成功\n", sink.toString());
        assertEquals("构建成功\n", String.join("", chunks));
    }

    @Test
    @DisplayName("killTree 能终止一个长期运行的子进程")
    void killTreeTerminatesProcess() throws Exception {
        Process process = new ProcessBuilder("sleep", "30").start();
        ProcessIo.killTree(process);
        assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(!process.isAlive());
    }
}
