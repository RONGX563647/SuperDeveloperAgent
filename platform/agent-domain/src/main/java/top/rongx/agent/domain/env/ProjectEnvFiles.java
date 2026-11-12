package top.rongx.agent.domain.env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从项目源码里读取「环境变量声明」，统一网关自检与执行层注入的口径。
 *
 * <p>为什么要放在 domain：设置页的"注入前自检"和部署时的"变量注入"必须看到
 * 同一份数据，否则会出现"页面说缺、部署其实有"或反过来的假象。
 *
 * <p>三条规则：
 * <ul>
 *   <li>{@code .env} 优先于 {@code .env.example}；空值不占位、不覆盖非空值；</li>
 *   <li>必填项以项目自己的声明为准：{@code entrypoint.sh} 的 {@code require VAR}、
 *       compose 的 {@code ${VAR:?}}；都没有时才回退"模板里空值且未标注可选"；</li>
 *   <li>输出与文件系统遍历顺序无关（固定排序），保证自检与注入可复现。</li>
 * </ul>
 */
public final class ProjectEnvFiles {

    /** 依赖/构建目录：里面的 .env 与本项目运行无关。 */
    private static final Set<String> SKIP_DIRS = Set.of(".git", ".svn", "node_modules", "target",
            "build", "dist", ".idea", ".gradle", ".agent", "worktrees", ".mvn");

    /** .env 最多看 5 层（docker/.env 这类）；application 配置埋得深，放宽到 8 层。 */
    private static final int ENV_MAX_DEPTH = 5;
    private static final int CONFIG_MAX_DEPTH = 8;

    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern REQUIRE = Pattern.compile("^\\s*require\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern COMPOSE_REQUIRED =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*):\\?");
    private static final Pattern PLACEHOLDER_DEFAULT =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*):([^}]*)}");
    private static final Pattern OPTIONAL_MARK =
            Pattern.compile("(可选|选填|非必填|optional|optinal)", Pattern.CASE_INSENSITIVE);

    private ProjectEnvFiles() {
    }

    /**
     * 扫描结果。
     *
     * @param values   变量名 → 非空值（.env 优先，空值不参与）
     * @param keys     所有声明过的变量名（含模板里的空值项，用于映射草案展示）
     * @param required 项目真正必需的变量名（自检只拦截这些）
     */
    public record Declared(Map<String, String> values, Set<String> keys, Set<String> required) {

        public Declared {
            values = values == null ? Map.of() : Map.copyOf(values);
            keys = keys == null ? Set.of() : Set.copyOf(keys);
            required = required == null ? Set.of() : Set.copyOf(required);
        }

        public static Declared empty() {
            return new Declared(Map.of(), Set.of(), Set.of());
        }
    }

    /** 一个 .env 行的解析结果。 */
    private record Entry(String key, String value, boolean optional) {
    }

    /** 扫描仓库；目录不存在时返回空结果。 */
    public static Declared scan(Path repoRoot) {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return Declared.empty();
        }
        List<Path> envFiles = new ArrayList<>();
        List<Path> entrypoints = new ArrayList<>();
        List<Path> composeFiles = new ArrayList<>();
        List<Path> appConfigs = new ArrayList<>();
        collect(repoRoot, envFiles, entrypoints, composeFiles, appConfigs);
        envFiles.sort(Comparator
                .comparingInt((Path p) -> isExample(p) ? 1 : 0)
                .thenComparingInt(p -> depth(repoRoot, p))
                .thenComparing(p -> p.toString()));

        Map<String, String> values = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        Set<String> exampleBlank = new LinkedHashSet<>();
        Set<String> exampleBlankOptional = new LinkedHashSet<>();
        Set<String> anyBlank = new LinkedHashSet<>();
        for (Path file : envFiles) {
            for (Entry entry : parseEnv(file)) {
                keys.add(entry.key());
                if (entry.value().isEmpty()) {
                    anyBlank.add(entry.key());
                    if (isExample(file)) {
                        exampleBlank.add(entry.key());
                        if (entry.optional()) {
                            exampleBlankOptional.add(entry.key());
                        }
                    }
                    continue;
                }
                values.putIfAbsent(entry.key(), entry.value());
            }
        }

        Set<String> required = requireFromEntrypoints(entrypoints);
        required.addAll(requireFromCompose(composeFiles));
        if (required.isEmpty()) {
            required = fallbackRequired(exampleBlank.isEmpty() ? anyBlank : exampleBlank,
                    exampleBlankOptional, values, appConfigs);
        }
        // 源码真实的 key 才允许成为必填项（entrypoint/compose 里的变量名本身就是真实声明）
        required.removeIf(key -> !KEY.matcher(key).matches());
        return new Declared(values, keys, required);
    }

    private static Set<String> requireFromEntrypoints(List<Path> entrypoints) {
        Set<String> out = new LinkedHashSet<>();
        for (Path file : entrypoints) {
            for (String raw : lines(file)) {
                Matcher m = REQUIRE.matcher(raw);
                if (m.find()) {
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }

    private static Set<String> requireFromCompose(List<Path> composeFiles) {
        Set<String> out = new LinkedHashSet<>();
        for (Path file : composeFiles) {
            Matcher m = COMPOSE_REQUIRED.matcher(String.join("\n", lines(file)));
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /**
     * 没有 entrypoint/compose 声明时的回退：模板空值 + 未标注可选 + 源码配置里没有默认值。
     */
    private static Set<String> fallbackRequired(Set<String> blankKeys, Set<String> optionalKeys,
                                                Map<String, String> values,
                                                List<Path> appConfigs) {
        Set<String> hasDefault = new LinkedHashSet<>();
        for (Path file : appConfigs) {
            Matcher m = PLACEHOLDER_DEFAULT.matcher(String.join("\n", lines(file)));
            while (m.find()) {
                if (!m.group(2).isBlank()) {
                    hasDefault.add(m.group(1));
                }
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (String key : blankKeys) {
            if (optionalKeys.contains(key) || values.containsKey(key) || hasDefault.contains(key)) {
                continue;
            }
            out.add(key);
        }
        return out;
    }

    /** 解析单个 .env：记录 key/value 与"上方注释块是否标注可选"。 */
    private static List<Entry> parseEnv(Path file) {
        List<Entry> out = new ArrayList<>();
        List<String> pendingComments = new ArrayList<>();
        boolean inComment = false;
        for (String raw : lines(file)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                pendingComments.clear();
                inComment = false;
                continue;
            }
            if (line.startsWith("#")) {
                if (!inComment && !pendingComments.isEmpty()) {
                    pendingComments.clear();
                }
                pendingComments.add(line);
                inComment = true;
                continue;
            }
            inComment = false;
            String text = line.startsWith("export ") ? line.substring("export ".length()).trim() : line;
            int eq = text.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = text.substring(0, eq).trim();
            if (!KEY.matcher(key).matches()) {
                continue;
            }
            String value = stripQuotes(text.substring(eq + 1).trim());
            boolean optional = pendingComments.stream().anyMatch(c -> OPTIONAL_MARK.matcher(c).find());
            out.add(new Entry(key, value, optional));
        }
        return out;
    }

    private static String stripQuotes(String value) {
        if (value.length() > 1) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void collect(Path root, List<Path> envFiles, List<Path> entrypoints,
                                List<Path> composeFiles, List<Path> appConfigs) {
        try {
            Files.walkFileTree(root, Set.of(), CONFIG_MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir,
                                                         BasicFileAttributes attrs) {
                    if (!dir.equals(root)
                            && SKIP_DIRS.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    int depth = depth(root, file);
                    if (isEnvFile(name) && depth <= ENV_MAX_DEPTH) {
                        envFiles.add(file);
                    } else if ("entrypoint.sh".equals(name) && depth <= ENV_MAX_DEPTH) {
                        entrypoints.add(file);
                    } else if (isCompose(name) && depth <= ENV_MAX_DEPTH) {
                        composeFiles.add(file);
                    } else if (isAppConfig(name)) {
                        appConfigs.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 单个目录不可读时用已收集到的部分
        }
    }

    private static boolean isExample(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".example") || name.endsWith(".sample") || name.endsWith(".template");
    }

    /** .env 的常见声明文件名：真实值、示例值、模板值。 */
    private static boolean isEnvFile(String name) {
        return ".env".equals(name) || ".env.example".equals(name)
                || ".env.sample".equals(name) || ".env.template".equals(name);
    }

    private static boolean isCompose(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return (lower.startsWith("docker-compose") || lower.startsWith("compose"))
                && (lower.endsWith(".yml") || lower.endsWith(".yaml"));
    }

    private static boolean isAppConfig(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("application")
                && (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties"));
    }

    private static int depth(Path root, Path file) {
        return root.relativize(file).getNameCount();
    }
}
