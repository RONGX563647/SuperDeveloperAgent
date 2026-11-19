package top.rongx.agent.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 构建产物新鲜度自检。
 *
 * <p>事故形态：网关进程长时间不重启，target/classes 被反复编译替换，
 * JVM 里新旧字节码混用 → 请求挂住、既不返回也不报错。这类问题日志里完全看不出来，
 * 所以必须在启动时主动比时间戳；而比对必须<b>真的执行到</b>——写死相对路径时
 * 在另一种工作目录下会静默跳过，等于没有自检（本次实测踩到过）。
 */
class BuildFreshnessCheckTest {

    @TempDir
    Path module;

    private Path classesDir() throws Exception {
        Path classes = module.resolve("target/classes/demo");
        Files.createDirectories(classes);
        return classes.getParent();
    }

    private Path classFile() throws Exception {
        Path file = classesDir().resolve("demo/App.class");
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

        BuildFreshnessCheck.Verdict verdict = BuildFreshnessCheck.inspectClasses(classesDir(), null);

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

        BuildFreshnessCheck.Verdict verdict = BuildFreshnessCheck.inspectClasses(classesDir(), null);

        assertTrue(verdict.checked());
        assertFalse(verdict.stale());
        assertTrue(verdict.detail().contains("抽样"), verdict.detail());
    }

    @Test
    @DisplayName("镜像里没有源码 / 没有产物：跳过而不是报错")
    void skipsWithoutSourcesOrClasses() throws Exception {
        BuildFreshnessCheck.Verdict noClasses = BuildFreshnessCheck.inspectClasses(
                module.resolve("target/classes"), null);
        assertFalse(noClasses.checked(), noClasses.detail());
        assertTrue(noClasses.detail().contains("没有编译产物"), noClasses.detail());

        classFile();
        BuildFreshnessCheck.Verdict noSources = BuildFreshnessCheck.inspectClasses(classesDir(), null);
        assertFalse(noSources.checked(), noSources.detail());
        assertTrue(noSources.detail().contains("没有源码"), noSources.detail());

        assertFalse(BuildFreshnessCheck.inspectClasses(null, null).checked());
    }

    @Test
    @DisplayName("内部类映射回同一个源文件，不会因为 $ 后缀而漏判")
    void innerClassesMapToSameSource() throws Exception {
        Path inner = classesDir().resolve("demo/App$Inner.class");
        Files.writeString(inner, "binary");
        Path src = sourceFile();
        Files.setLastModifiedTime(src, FileTime.fromMillis(2_000_000L));
        Files.setLastModifiedTime(inner, FileTime.fromMillis(1_000_000L));

        BuildFreshnessCheck.Verdict verdict = BuildFreshnessCheck.inspectClasses(classesDir(), null);

        assertTrue(verdict.stale(), "内部类也要参与比对：" + verdict.detail());
    }

    @Test
    @DisplayName("进程启动后产物又被编译：判为过期（编译了没重启）")
    void staleWhenProcessPredatesArtifacts() throws Exception {
        Path cls = classFile();
        Path src = sourceFile();
        Files.setLastModifiedTime(src, FileTime.fromMillis(1_000_000L));
        Files.setLastModifiedTime(cls, FileTime.fromMillis(5_000_000L));

        // 进程启动时间早于产物时间：源码与产物本身一致，但进程里是旧字节码
        BuildFreshnessCheck.Verdict verdict = BuildFreshnessCheck.inspectClasses(
                classesDir(), Instant.ofEpochMilli(2_000_000L));

        assertTrue(verdict.checked());
        assertTrue(verdict.stale(), verdict.detail());
        assertTrue(verdict.detail().contains("进程启动之后"), verdict.detail());
    }

    @Test
    @DisplayName("进程晚于产物：源码与产物一致时判为新鲜")
    void freshWhenProcessIsNewest() throws Exception {
        Path cls = classFile();
        Path src = sourceFile();
        Files.setLastModifiedTime(src, FileTime.fromMillis(1_000_000L));
        Files.setLastModifiedTime(cls, FileTime.fromMillis(2_000_000L));

        BuildFreshnessCheck.Verdict verdict = BuildFreshnessCheck.inspectClasses(
                classesDir(), Instant.ofEpochMilli(9_000_000L));

        assertTrue(verdict.checked());
        assertFalse(verdict.stale(), verdict.detail());
    }

    @Test
    @DisplayName("产物目录反推源码目录：target/classes → src/main/java")
    void sourceRootIsDerivedFromClasses() {
        Path moduleRoot = Path.of("X:/demo/agent-gateway");
        Path classes = moduleRoot.resolve("target/classes");
        assertEquals(moduleRoot.resolve("src/main/java").normalize(),
                BuildFreshnessCheck.sourceRootOf(classes).normalize());
        // 不是 target 布局（例如以 jar 解包运行）时不猜
        assertNull(BuildFreshnessCheck.sourceRootOf(Path.of("X:/demo/classes")));
    }

    @Test
    @DisplayName("运行期过期提示：产物晚于进程启动就给出「请重启网关」")
    void runtimeHintWhenArtifactsAreNewerThanProcess() {
        Instant started = Instant.ofEpochMilli(1_000_000L);
        Instant compiled = Instant.ofEpochMilli(5_000_000L);

        String hint = BuildFreshnessCheck.staleHint(compiled, started);

        assertNotNull(hint, "产物比进程新时必须给提示");
        assertTrue(hint.contains("旧字节码"), hint);
        assertTrue(hint.contains("请重启网关"), "提示要给出动作：" + hint);
    }

    @Test
    @DisplayName("运行期过期提示：进程比产物新或读不到产物时不误报")
    void noHintWhenProcessIsNewest() {
        Instant started = Instant.ofEpochMilli(9_000_000L);
        Instant compiled = Instant.ofEpochMilli(5_000_000L);

        assertNull(BuildFreshnessCheck.staleHint(compiled, started));
        assertNull(BuildFreshnessCheck.staleHint(null, started), "读不到产物时不该告警");
        assertNull(BuildFreshnessCheck.staleHint(compiled, null), "拿不到进程时间时不该告警");
    }
}
