package top.rongx.agent.gateway.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 构建产物新鲜度自检：启动时发现「进程里的类比磁盘上的源文件更旧」就明确告警。
 *
 * <p><b>真实事故</b>：8090 网关进程从 08:56 起没重启，期间 {@code target/classes} 被
 * 反复编译替换，JVM 里是新旧字节码混用。表现是请求挂住——既不返回也不报错，
 * 前端只能一直转圈。这类问题用日志完全看不出来，因为进程本身认为自己一切正常。
 *
 * <p><b>做法</b>：只在启动时比一次「自己的 class 文件时间 vs 同名源文件时间」。
 * 不一致不阻止启动（生产环境的镜像里通常没有源码，比不出来就跳过），
 * 但会在日志里写一条可操作的警告：请重启网关，否则会出现"改了不生效 / 请求挂住"。
 */
@Component
public class BuildFreshnessCheck {

    private static final Logger log = LoggerFactory.getLogger(BuildFreshnessCheck.class);

    /** 最多检查多少个 class：只是抽样告警，不需要全量扫描拖慢启动。 */
    private static final int MAX_SAMPLES = 200;

    /** 自检结论。 */
    public record Verdict(boolean checked, boolean stale, String detail) {

        static Verdict skipped(String why) {
            return new Verdict(false, false, why);
        }

        static Verdict fresh(int compared) {
            return new Verdict(true, false, "构建产物与源码一致（抽样 " + compared + " 个类）");
        }

        static Verdict stale(String detail) {
            return new Verdict(true, true, detail);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Verdict verdict = inspect(Path.of("").toAbsolutePath(), Path.of("target", "classes"));
        if (!verdict.checked()) {
            log.debug("构建产物新鲜度自检跳过：{}", verdict.detail());
            return;
        }
        if (verdict.stale()) {
            // 这是可操作的告警：重启即可修复，不重启就会出现"改了不生效 / 请求挂住"
            log.warn("[freshness] {}；请重启网关进程，否则可能出现"
                    + "「改了不生效」或「请求挂住不返回」", verdict.detail());
            return;
        }
        log.info("[freshness] {}", verdict.detail());
    }

    /**
     * 抽样比对：{@code classesRoot} 下的 .class 是否比 {@code sourceRoot} 下同名 .java 更新。
     *
     * <p>纯函数式实现（不读 Spring 上下文），便于直接单测。
     *
     * @param moduleRoot  模块根目录（含 src/main/java 与 target/classes）
     * @param classesRoot 相对 {@code moduleRoot} 的 class 输出目录
     */
    public static Verdict inspect(Path moduleRoot, Path classesRoot) {
        if (moduleRoot == null || classesRoot == null) {
            return Verdict.skipped("缺少路径");
        }
        Path classes = moduleRoot.resolve(classesRoot).normalize();
        if (!Files.isDirectory(classes)) {
            return Verdict.skipped("没有编译产物目录：" + classes);
        }
        Path sources = moduleRoot.resolve("src/main/java").normalize();
        if (!Files.isDirectory(sources)) {
            return Verdict.skipped("没有源码目录（镜像内通常如此）：" + sources);
        }
        List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(classes)) {
            classFiles = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .limit(MAX_SAMPLES)
                    .toList();
        } catch (IOException e) {
            return Verdict.skipped("读取编译产物失败：" + e.getMessage());
        }
        List<String> stale = new ArrayList<>();
        int compared = 0;
        for (Path classFile : classFiles) {
            Path source = sources.resolve(classes.relativize(classFile).toString()
                    .replaceAll("\\$.*\\.class$", ".java").replaceAll("\\.class$", ".java"));
            if (!Files.isRegularFile(source)) {
                continue;
            }
            compared++;
            try {
                if (Files.getLastModifiedTime(source).toMillis()
                        > Files.getLastModifiedTime(classFile).toMillis()) {
                    stale.add(source.getFileName().toString());
                }
            } catch (IOException ignored) {
                // 时间戳读不到就跳过这一条，不影响其它抽样
            }
        }
        if (compared == 0) {
            return Verdict.skipped("没有可对比的源码/产物对");
        }
        if (stale.isEmpty()) {
            return Verdict.fresh(compared);
        }
        stale.sort(Comparator.naturalOrder());
        return Verdict.stale("源码比编译产物新（" + stale.size() + "/" + compared
                + " 个类，例如 " + String.join(", ", stale.stream().limit(3).toList())
                + "）");
    }
}
