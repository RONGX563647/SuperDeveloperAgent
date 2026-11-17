package top.rongx.agent.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.error.ArtifactException;
import top.rongx.agent.domain.error.BuildException;
import top.rongx.agent.domain.error.DeployException;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.ErrorGroup;
import top.rongx.agent.domain.error.IntakeException;
import top.rongx.agent.domain.error.PlatformException;
import top.rongx.agent.domain.error.PromotionException;
import top.rongx.agent.domain.error.RecipeException;
import top.rongx.agent.domain.error.RunException;

/**
 * 异常码强制契约。
 *
 * <p>EC1 码段与业务分组一致；EC2 禁止通用异常携带错误码；EC3 业务异常归位且分组绑定；
 * EC5 通用 throw 清零（基线必须为空，新增即失败）。
 *
 * <p>基线更新：{@code mvn -pl spec-checks -Dtest=ErrorCodeContractTest
 * -Derror.code.baseline.update=true test}
 */
class ErrorCodeContractTest {

    /** 分组 → 业务异常类；新增分组必须先在这里登记。 */
    private static final Map<ErrorGroup, Class<? extends PlatformException>> GROUP_TYPES = Map.of(
            ErrorGroup.BUILD, BuildException.class,
            ErrorGroup.RECIPE, RecipeException.class,
            ErrorGroup.RUN, RunException.class,
            ErrorGroup.ARTIFACT, ArtifactException.class,
            ErrorGroup.DEPLOY, DeployException.class,
            ErrorGroup.PROMOTION, PromotionException.class,
            ErrorGroup.ENVIRONMENT, EnvironmentException.class,
            ErrorGroup.INTAKE, IntakeException.class);

    /** 通用异常不允许携带错误码。 */
    private static final Pattern CODED_GENERIC_THROW = Pattern.compile(
            "new\\s+(?:IllegalArgumentException|IllegalStateException|RuntimeException)"
                    + "\\s*\\(\\s*ErrorCode\\b", Pattern.DOTALL);

    /** 存量通用 throw（基线棘轮计数）。 */
    private static final Pattern GENERIC_THROW = Pattern.compile(
            "throw\\s+new\\s+(?:IllegalArgumentException|IllegalStateException|RuntimeException)\\s*\\(");

    private static final Pattern IGNORE = Pattern.compile("spec-ignore:\\s*R15\\b");

    /** 允许直接继承 PlatformException 的包（策略异常携带额外上下文，保留原包）。 */
    private static final List<String> ALLOWED_PACKAGES = List.of(
            "agent-domain/src/main/java/top/rongx/agent/domain/error/",
            "agent-domain/src/main/java/top/rongx/agent/domain/policy/");

    private static final Path BASELINE = Path.of("error-code-baseline.txt").toAbsolutePath().normalize();

    @Test
    @DisplayName("EC1：错误码码段与业务分组一致，且分组都有异常类")
    void errorCodesBelongToBusinessGroups() {
        for (ErrorCode code : ErrorCode.values()) {
            assertNotNull(code.group(), "错误码没有业务分组：" + code);
            assertTrue(GROUP_TYPES.containsKey(code.group()),
                    "业务分组缺少异常类：" + code + " -> " + code.group());
        }
        assertEquals(ErrorGroup.values().length, GROUP_TYPES.size(),
                "每个业务分组必须且只能绑定一个异常类");
    }

    @Test
    @DisplayName("EC3：业务异常归位、分组绑定")
    void platformExceptionsAreGrouped() throws Exception {
        for (Map.Entry<ErrorGroup, Class<? extends PlatformException>> entry : GROUP_TYPES.entrySet()) {
            Class<?> type = entry.getValue();
            assertEquals(PlatformException.class, type.getSuperclass(),
                    type.getSimpleName() + " 必须直接继承 PlatformException");
            assertEquals(entry.getKey(), type.getSimpleName().replace("Exception", "").toUpperCase()
                    .transform(name -> ErrorGroup.valueOf(name)),
                    type.getSimpleName() + " 与分组不一致");
        }

        Path root = specRoot();
        List<String> misplaced = new ArrayList<>();
        try (Stream<Path> files = mainJavaFiles(root)) {
            for (Path file : files.toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains("extends PlatformException")) {
                    continue;
                }
                String path = root.relativize(file).toString().replace('\\', '/');
                if (ALLOWED_PACKAGES.stream().noneMatch(path::startsWith)) {
                    misplaced.add(path);
                }
            }
        }
        assertTrue(misplaced.isEmpty(),
                "业务异常必须放在 domain/error（或 domain/policy）：\n  " + String.join("\n  ", misplaced));
    }

    @Test
    @DisplayName("EC2：禁止通用异常携带 ErrorCode")
    void genericExceptionsMustNotCarryErrorCodes() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = mainJavaFiles(specRoot())) {
            for (Path file : files.toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = CODED_GENERIC_THROW.matcher(source);
                while (matcher.find()) {
                    violations.add(specRoot().relativize(file) + ":"
                            + lineOf(source, matcher.start())
                            + "  请改用分组业务异常，例如 new EnvironmentException(ErrorCode.X, msg)");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "发现通用异常携带错误码：\n  " + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("EC5：通用 throw 已清零，基线只允许为空")
    void genericThrowBaselineOnlyShrinks() throws IOException {
        Map<String, Integer> actual = countGenericThrows(specRoot());
        if (Boolean.getBoolean("error.code.baseline.update")) {
            writeBaseline(actual);
            return;
        }
        Map<String, Integer> known = readBaseline();

        List<String> grown = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : actual.entrySet()) {
            int allowed = known.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() > allowed) {
                grown.add(entry.getKey() + "：基线 " + allowed + "，实际 " + entry.getValue());
            }
        }
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : known.entrySet()) {
            int current = actual.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() > current) {
                stale.add(entry.getKey() + "：基线 " + entry.getValue() + "，实际 " + current);
            }
        }

        List<String> problems = new ArrayList<>();
        if (!grown.isEmpty()) {
            problems.add("新增未带错误码的通用 throw（请改用分组业务异常）：\n    "
                    + String.join("\n    ", grown));
        }
        if (!stale.isEmpty()) {
            problems.add("基线已过期（存量已减少，请删除对应基线行）：\n    "
                    + String.join("\n    ", stale));
        }
        assertTrue(problems.isEmpty(), String.join("\n\n", problems)
                + "\n\n重新生成基线：mvn -pl spec-checks -Dtest=ErrorCodeContractTest "
                + "-Derror.code.baseline.update=true test");
    }

    private static Map<String, Integer> countGenericThrows(Path root) throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        try (Stream<Path> files = mainJavaFiles(root)) {
            for (Path file : files.toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                int count = 0;
                for (int i = 0; i < lines.size(); i++) {
                    if (!GENERIC_THROW.matcher(lines.get(i)).find()) {
                        continue;
                    }
                    if (isIgnored(lines, i)) {
                        continue;
                    }
                    count++;
                }
                if (count > 0) {
                    counts.put(root.relativize(file).toString().replace('\\', '/'), count);
                }
            }
        }
        return counts;
    }

    private static boolean isIgnored(List<String> lines, int index) {
        if (IGNORE.matcher(lines.get(index)).find()) {
            return true;
        }
        return index > 0 && IGNORE.matcher(lines.get(index - 1)).find();
    }

    private static Map<String, Integer> readBaseline() throws IOException {
        if (!Files.isRegularFile(BASELINE)) {
            return Map.of();
        }
        Map<String, Integer> known = new LinkedHashMap<>();
        for (String line : Files.readAllLines(BASELINE, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int split = trimmed.lastIndexOf(' ');
            assertTrue(split > 0, "基线行格式应为 <路径> <数量>：" + line);
            known.put(trimmed.substring(0, split), Integer.parseInt(trimmed.substring(split + 1)));
        }
        return known;
    }

    private static void writeBaseline(Map<String, Integer> counts) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# 异常码契约存量基线：<相对路径> <数量>；只允许减少，迁移后请重新生成");
        counts.forEach((path, count) -> lines.add(path + " " + count));
        Files.write(BASELINE, lines, StandardCharsets.UTF_8);
    }

    /** 只扫描 Maven 模块的 src/main/java，避开 node_modules/target。 */
    private static Stream<Path> mainJavaFiles(Path root) throws IOException {
        Set<Path> roots = new LinkedHashSet<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.toList()) {
                Path java = module.resolve("src/main/java");
                if (Files.isDirectory(java) && Files.exists(module.resolve("pom.xml"))) {
                    roots.add(java);
                }
            }
        }
        List<Path> files = new ArrayList<>();
        for (Path javaRoot : roots) {
            try (Stream<Path> walk = Files.walk(javaRoot)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .forEach(files::add);
            }
        }
        return files.stream();
    }

    private static Path specRoot() {
        return Path.of(System.getProperty("spec.root", ".")).toAbsolutePath().normalize();
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
