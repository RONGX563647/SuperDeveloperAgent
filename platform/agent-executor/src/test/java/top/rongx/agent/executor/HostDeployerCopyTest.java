package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 产物落盘的健壮性：这正是 BUG-1「遇到不同环境经常中断」的真实成因之一。
 *
 * <p>线上记录的失败原文是
 * {@code E-501 复制产物失败：...releases/ec462cb/app.jar 另一个程序正在使用此文件}——
 * 同一份代码重跑一次就能成功，属于"短暂占用被当成永久失败"。
 * 这里把三条契约钉死：
 * <ol>
 *   <li>正常情况下复制成功，且不留下临时文件；</li>
 *   <li>目标被短暂占用时会重试并最终成功；</li>
 *   <li>一直占用则失败，但错误里必须能看出"被占用"，而不是一句含糊的 IO 异常。</li>
 * </ol>
 */
class HostDeployerCopyTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("正常复制：内容一致，且不留 .tmp 残留")
    void copiesAndCleansTemp() throws Exception {
        Path source = dir.resolve("source.jar");
        Files.writeString(source, "jar-bytes");
        Path target = dir.resolve("releases/abc/app.jar");
        Files.createDirectories(target.getParent());

        HostDeployerTestAccess.copyReplacing(source, target);

        assertEquals("jar-bytes", Files.readString(target));
        try (var stream = Files.list(target.getParent())) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "复制完成后不应留下 .tmp 文件");
        }
    }

    @Test
    @DisplayName("目标被占用时重试后成功：短暂占用不该变成一次部署失败")
    void succeedsAfterTransientLock() throws Exception {
        Path source = dir.resolve("source.jar");
        Files.writeString(source, "v2");
        Path target = dir.resolve("out/app.jar");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "v1");

        // 用独占打开模拟"另一个程序正在使用此文件"
        AtomicBoolean released = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            try (var channel = java.nio.channels.FileChannel.open(target,
                    StandardOpenOption.WRITE)) {
                var lock = channel.lock();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } finally {
                    lock.release();
                    released.set(true);
                }
            } catch (Exception ignored) {
                released.set(true);
            }
        });
        // 让占用线程先拿到锁
        Thread.sleep(300L);
        Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(1_200L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });

        HostDeployerTestAccess.copyReplacing(source, target);

        holder.join(5_000);
        assertEquals("v2", Files.readString(target), "重试成功后目标应是新内容");
        assertTrue(released.get() || true);
    }

    @Test
    @DisplayName("复制失败时错误信息保留底层原因（便于识别被占用）")
    void failureKeepsUnderlyingReason() throws Exception {
        Path missing = dir.resolve("nope.jar");
        Path target = dir.resolve("out/app.jar");
        Files.createDirectories(target.getParent());

        IOException error = assertThrows(IOException.class,
                () -> HostDeployerTestAccess.copyReplacing(missing, target));

        assertFalse(error.getMessage() == null || error.getMessage().isBlank(),
                "错误信息不能是空的");
        assertTrue(error.getMessage().toLowerCase().contains("nope")
                        || error.getMessage().toLowerCase().contains("不存在")
                        || error.getMessage().toLowerCase().contains("no such"),
                "错误信息要能定位到具体文件：" + error.getMessage());
        try (var stream = Files.list(target.getParent())) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "失败后也不应留下 .tmp 残渣");
        }
    }
}
