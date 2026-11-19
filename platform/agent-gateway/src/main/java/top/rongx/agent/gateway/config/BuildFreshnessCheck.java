package top.rongx.agent.gateway.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
        // 产物目录必须从「本类是从哪里加载的」反推，不能写死相对路径：
        // 同一份代码可能以仓库根为工作目录启动（IDE 多模块），也可能是模块目录，
        // 写死 target/classes 时在另一种工作目录下会静默跳过——等于没有自检。
        Path classesRoot = classesLocation();
        if (classesRoot == null) {
            log.debug("构建产物新鲜度自检跳过：定位不到本类的 class 目录（可能以 jar 运行）");
            return;
        }
        // 两件事都要比：
        // 1) 源码 vs 产物 —— 产物过期（改了没编译）；
        // 2) 进程 vs 产物 —— 进程启动后又编译过（编译了没重启）。
        // 第 2 类正是本次会话实测到的形态：进程启动于 23:13，产物 12:12 又被重新编译，
        // 进程继续用旧字节码服务，表现为「改了不生效 / 请求挂住」。
        Verdict verdict = inspectClasses(classesRoot,
                ProcessHandle.current().info().startInstant().orElse(null));
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
     * 本类所在 class 目录（即 {@code target/classes}）。
     *
     * <p>以 jar 运行时返回 null：那种部署形态下没有源码可比，本来就不该告警。
     */
    static Path classesLocation() {
        try {
            java.security.CodeSource source = BuildFreshnessCheck.class
                    .getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path path = Path.of(source.getLocation().toURI());
            return Files.isDirectory(path) ? path : null;
        } catch (java.net.URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 抽样比对：{@code moduleRoot} 下的产物与源码。
     *
     * <p>纯函数式实现（不读 Spring 上下文），便于直接单测。
     *
     * @param moduleRoot  模块根目录（含 src/main/java 与 target/classes）
     * @param classesRoot 相对 {@code moduleRoot} 的 class 输出目录
     */
    public static Verdict inspect(Path moduleRoot, Path classesRoot) {
        return inspectClasses(moduleRoot.resolve(classesRoot).normalize(), null);
    }

    /**
     * 以「产物目录」为入口做比对：源码目录由产物目录反推
     * （{@code .../target/classes} → {@code .../src/main/java}）。
     *
     * @param classes          实际被加载的 class 目录
     * @param processStartedAt 本进程启动时间；null 表示跳过这一项比较
     */
    public static Verdict inspectClasses(Path classes, Instant processStartedAt) {
        if (classes == null) {
            return Verdict.skipped("缺少产物目录");
        }
        if (!Files.isDirectory(classes)) {
            return Verdict.skipped("没有编译产物目录：" + classes);
        }
        // 进程比产物旧：这是"编译了但没重启"，比源码过期更隐蔽（源码可能已经一致）
        if (processStartedAt != null) {
            Instant newest = newestClassTime(classes);
            if (newest != null && newest.isAfter(processStartedAt)) {
                return Verdict.stale("编译产物在进程启动之后又被更新（产物 " + newest
                        + "，进程启动于 " + processStartedAt + "）");
            }
        }
        Path sources = sourceRootOf(classes);
        if (sources == null || !Files.isDirectory(sources)) {
            return Verdict.skipped("没有源码目录（镜像内通常如此）：" + classes);
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

    /** 产物目录反推源码目录：{@code <module>/target/classes} → {@code <module>/src/main/java}。 */
    static Path sourceRootOf(Path classes) {
        Path target = classes.getParent();
        if (target == null || !"target".equals(String.valueOf(target.getFileName()))) {
            return null;
        }
        Path module = target.getParent();
        return module == null ? null : module.resolve("src/main/java");
    }

    /** 产物目录里最新的一个 .class 时间；目录为空或读不到返回 null。 */
    private static Instant newestClassTime(Path classes) {
        try (Stream<Path> walk = Files.walk(classes)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .map(BuildFreshnessCheck::modifiedOrNull)
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Instant modifiedOrNull(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return null;
        }
    }
}
