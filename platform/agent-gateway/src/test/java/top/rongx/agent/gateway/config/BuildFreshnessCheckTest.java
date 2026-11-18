package top.rongx.agent.gateway.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 构建产物新鲜度自检。
 *
 * <p>事故形态：网关进程长时间不重启，target/classes 被反复编译替换，
 * JVM 里新旧字节码混用 → 请求挂住、既不返回也不报错。这类问题日志里完全看不出来，
 * 所以必须在启动时主动比一次时间戳。
 */
class BuildFreshnessCheckTest {

    @TempDir
    Path module;

    private Path classFile() throws Exception {
        Path classes = module.resolve("target/classes/demo");
        Files.createDirectories(classes);
        Path file = classes.resolve("App.class");
        Files.writeString(file, "binary");
        return file;
    }

    private Path sourceFile() throws Exception {
        Path sources = module.resolve("src/main/java/demo");
        Files.createDirectories(sources);
        Path file = sources.resolve("App.java");
        Files.writeString(file, "class App {}");
        return file;
    }

    @Test
    @DisplayName("源码比产物新：判为过期，并给出可操作细节")
    void staleWhenSourceIsNewer() throws Exception {
        Path cls = classFile();
        Path src = sourceFile();
        Files.setLastModifiedTime(src, FileTime.fromMillis(2_000_000L));
        Files.setLastModifiedTime(cls, FileTime.fromMillis(1_000_000L));

        BuildFreshnessCheck.Verdict verdict =
                BuildFreshnessCheck.inspect(module, Path.of("target/classes"));

        assertTrue(verdict.checked());
        assertTrue(verdict.stale());
        assertTrue(verdict.detail().contains("App.java"), verdict.detail());
    }

    @Test
    @DisplayName("产物比源码新：判为新鲜")
    void freshWhenClassIsNewer() throws Exception {
        Path cls = classFile();
        Path src = sourceFile();
        Files.setLastModifiedTime(src, FileTime.fromMillis(1_000_000L));
        Files.setLastModifiedTime(cls, FileTime.fromMillis(2_000_000L));

        BuildFreshnessCheck.Verdict verdict =
                BuildFreshnessCheck.inspect(module, Path.of("target/classes"));

        assertTrue(verdict.checked());
        assertFalse(verdict.stale());
        assertTrue(verdict.detail().contains("抽样"), verdict.detail());
    }

    @Test
    @DisplayName("镜像里没有源码 / 没有产物：跳过而不是报错")
    void skipsWithoutSourcesOrClasses() throws Exception {
        BuildFreshnessCheck.Verdict noClasses =
                BuildFreshnessCheck.inspect(module, Path.of("target/classes"));
        assertFalse(noClasses.checked(), noClasses.detail());
        assertTrue(noClasses.detail().contains("没有编译产物"), noClasses.detail());

        classFile();
        BuildFreshnessCheck.Verdict noSources =
                BuildFreshnessCheck.inspect(module, Path.of("target/classes"));
        assertFalse(noSources.checked(), noSources.detail());
        assertTrue(noSources.detail().contains("没有源码"), noSources.detail());

        assertFalse(BuildFreshnessCheck.inspect(null, Path.of("target/classes")).checked());
        assertFalse(BuildFreshnessCheck.inspect(module, null).checked());
    }

    @Test
    @DisplayName("内部类映射回同一个源文件，不会因为 $ 后缀而漏判")
    void innerClassesMapToSameSource() throws Exception {
        Path classes = module.resolve("target/classes/demo");
        Files.createDirectories(classes);
        Path inner = classes.resolve("App$Inner.class");
        Files.writeString(inner, "binary");
        Path src = sourceFile();
        Files.setLastModifiedTime(src, FileTime.fromMillis(2_000_000L));
        Files.setLastModifiedTime(inner, FileTime.fromMillis(1_000_000L));

        BuildFreshnessCheck.Verdict verdict =
                BuildFreshnessCheck.inspect(module, Path.of("target/classes"));

        assertTrue(verdict.stale(), "内部类也要参与比对：" + verdict.detail());
    }
}
