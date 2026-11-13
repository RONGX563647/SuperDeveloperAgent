package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.tools.SecretRedactor;

/**
 * {@code GET /api/v1/fs/read} 的边界与降级行为。
 *
 * <p>重点不是"能读"，而是"不该读的读不到"：目录逃逸、符号链接、.git、敏感文件、
 * 二进制与超大文件都必须按约定降级或拒绝。
 */
class FsBrowserControllerTest {

    @TempDir
    Path root;

    private FsBrowserController controller() {
        return new FsBrowserController(root.toString(), new SecretRedactor(null));
    }

    @Test
    void readsTextFileWithMetadata() throws IOException {
        Path file = root.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Main {}\n", StandardCharsets.UTF_8);

        FsBrowserController.FsFile result = controller().read(file.toString());

        assertEquals("Main.java", result.name());
        assertEquals("class Main {}\n", result.content());
        assertFalse(result.truncated());
        assertTrue(result.sizeBytes() > 0);
        assertTrue(result.modifiedAt() > 0);
    }

    @Test
    void resolvesRelativePathInsideSharedRoot() throws IOException {
        Files.writeString(root.resolve("README.md"), "# demo\n");

        FsBrowserController.FsFile result = controller().read("README.md");

        assertEquals("# demo\n", result.content());
    }

    @Test
    void rejectsTraversalOutsideSharedRoot() throws IOException {
        Path outside = root.getParent().resolve("outside-" + System.nanoTime() + ".txt");
        Files.writeString(outside, "secret");
        try {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> controller().read("../" + outside.getFileName()));
            assertTrue(error.getMessage().contains("边界"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsAbsolutePathOutsideSharedRoot() throws IOException {
        Path outside = root.getParent().resolve("abs-" + System.nanoTime() + ".txt");
        Files.writeString(outside, "secret");
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> controller().read(outside.toString()));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsSymlinkEscapingSharedRoot() throws IOException {
        Path outside = root.getParent().resolve("link-target-" + System.nanoTime() + ".txt");
        Files.writeString(outside, "secret");
        Path link = root.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("当前文件系统不支持符号链接：" + e.getMessage());
        }
        try {
            assertThrows(IllegalArgumentException.class, () -> controller().read(link.toString()));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsSensitiveFile() throws IOException {
        Files.writeString(root.resolve(".env"), "TOKEN=abc");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller().read(".env"));
        assertTrue(error.getMessage().contains("敏感文件"));
    }

    @Test
    void rejectsGitInternals() throws IOException {
        Path config = root.resolve(".git/config");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "[remote]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller().read(".git/config"));
        assertTrue(error.getMessage().contains(".git"));
    }

    @Test
    void rejectsDirectory() throws IOException {
        Files.createDirectories(root.resolve("src"));

        assertThrows(IllegalArgumentException.class, () -> controller().read("src"));
    }

    @Test
    void returnsNullContentForBinaryFile() throws IOException {
        Files.write(root.resolve("logo.bin"), new byte[] {1, 2, 0, 4, 5});

        FsBrowserController.FsFile result = controller().read("logo.bin");

        assertNull(result.content());
        assertFalse(result.truncated());
    }

    @Test
    void returnsNullContentForOversizedFile() throws IOException {
        byte[] big = new byte[(int) (2L * 1024 * 1024 + 1)];
        java.util.Arrays.fill(big, (byte) 'a');
        Files.write(root.resolve("big.txt"), big);

        FsBrowserController.FsFile result = controller().read("big.txt");

        assertNull(result.content());
        assertTrue(result.truncated());
    }

    @Test
    void truncatesVeryLongTextByLines() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 5005; i++) {
            text.append("line-").append(i).append('\n');
        }
        Files.writeString(root.resolve("access.log"), text.toString());

        FsBrowserController.FsFile result = controller().read("access.log");

        assertNotNull(result.content());
        assertTrue(result.truncated());
        assertEquals(5000, result.content().split("\n", -1).length);
    }

    @Test
    void freeModeAllowsAbsolutePath() throws IOException {
        Path file = root.resolve("free.txt");
        Files.writeString(file, "ok");
        FsBrowserController free = new FsBrowserController("", new SecretRedactor(null));

        assertEquals("ok", free.read(file.toString()).content());
        assertThrows(IllegalArgumentException.class, () -> free.read("free.txt"));
    }

    @Test
    void rejectsMissingFile() {
        assertThrows(IllegalArgumentException.class, () -> controller().read("missing.txt"));
    }
}
