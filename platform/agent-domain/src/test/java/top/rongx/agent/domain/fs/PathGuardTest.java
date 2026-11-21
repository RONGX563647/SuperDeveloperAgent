package top.rongx.agent.domain.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.error.RunException;

/**
 * 仓库内路径边界的唯一实现。
 *
 * <p>守住的回归：各模块曾各自 {@code normalize + startsWith}，挡得住 {@code ../}，
 * 挡不住符号链接——仓库里一个 {@code link -> /etc} 就能读到仓库外。
 */
class PathGuardTest {

    @Test
    @DisplayName("普通相对路径解析到根内")
    void resolvesInside(@TempDir Path root) {
        Path resolved = PathGuard.resolveInside(root, "src/main/App.java");
        assertTrue(resolved.startsWith(root.toAbsolutePath().normalize()));
        assertEquals(root.toAbsolutePath().normalize().resolve("src/main/App.java"), resolved);
    }

    @Test
    @DisplayName("空路径返回根自身")
    void emptyReturnsRoot(@TempDir Path root) {
        assertEquals(root.toAbsolutePath().normalize(), PathGuard.resolveInside(root, "  "));
    }

    @Test
    @DisplayName("../ 逃逸被拒绝")
    void rejectsParentTraversal(@TempDir Path root) {
        assertThrows(RunException.class, () -> PathGuard.resolveInside(root, "../outside.txt"));
    }

    @Test
    @DisplayName("绝对路径被拒绝（绝对路径会直接丢弃根）")
    void rejectsAbsolute(@TempDir Path root) {
        Path absolute = root.resolveSibling("elsewhere.txt").toAbsolutePath();
        assertThrows(RunException.class, () -> PathGuard.resolveInside(root, absolute.toString()));
    }

    @Test
    @DisplayName("符号链接指向根外时被拒绝")
    void rejectsSymlinkEscape(@TempDir Path root, @TempDir Path outside) throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "top-secret");
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            // 本机不支持创建符号链接（Windows 非管理员等）：跳过该用例
            return;
        }
        assertThrows(RunException.class,
                () -> PathGuard.resolveInside(root, "link/" + secret.getFileName()));
    }

    @Test
    @DisplayName("relative 返回根内相对路径，越界返回 null")
    void relativeComputesInsideOnly(@TempDir Path root) {
        Path inside = root.resolve("a/b.txt");
        assertEquals("a/b.txt", PathGuard.relative(root, inside));
        assertNull(PathGuard.relative(root, root.resolveSibling("outside.txt")));
    }

    @Test
    @DisplayName("isInside 含根自身，越界为 false")
    void isInsideIncludesRoot(@TempDir Path root) {
        assertTrue(PathGuard.isInside(root, root));
        assertTrue(PathGuard.isInside(root, root.resolve("a/b.txt")));
        assertFalse(PathGuard.isInside(root, root.resolveSibling("outside.txt")));
    }
}
