package top.rongx.agent.tools;

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
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.EnvVariable;
import top.rongx.agent.domain.env.ProjectEnvFiles;
import top.rongx.agent.domain.env.ScenarioCatalog;
import top.rongx.agent.domain.env.ValueState;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 配置解析：优先项目环境配置，其次仓库声明，最后全量扫描文档/compose。
 *
 * <p>扫描结果只作为「候选」返回，不自动写环境；缺失或冲突时返回
 * {@code NEED_USER_INPUT}，由 Agent 把摘要和问题交给用户并等待回答。
 * 任何密钥值都不会出现在返回值里。
 */
@Service
public class ConfigResolver {

    private static final Set<String> SKIP_DIRS = Set.of(".git", ".svn", "node_modules", "target",
            "build", "dist", ".idea", ".gradle", ".agent", "worktrees", ".mvn", "vendor");

    private static final Pattern ASSIGN = Pattern.compile(
            "^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*[:=]\\s*(.+?)\\s*$");
    private static final Pattern IMAGE = Pattern.compile("^\\s*image:\\s*([^\\s#]+)");
    private static final Pattern ENV_HINT = Pattern.compile(
            "(?i)(?:^|[/_.-])(dev|test|prod|production|staging)(?:[/_.-]|$)");

    private final EnvironmentStore envStore;
    private final ProjectRepoLocator repoLocator;

    public ConfigResolver(EnvironmentStore envStore, ProjectRepoLocator repoLocator) {
        this.envStore = envStore;
        this.repoLocator = repoLocator;
    }

    /** 单条扫描候选。 */
    public record Candidate(String key, String value, String source, boolean secret, String envHint) {
    }

    /** 解析结果。 */
    public record Resolution(
            String projectId,
            String env,
            Map<String, String> resolved,
            Map<String, String> resolvedSource,
            Map<String, ValueState> states,
            List<String> missing,
            Map<String, List<Candidate>> candidates,
            List<String> conflicts,
            int scannedFiles,
            boolean needsUserInput) {

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("project: ").append(projectId).append('\n')
                    .append("env: ").append(env).append('\n')
                    .append("resolved: ").append(resolved.size())
                    .append("（来自项目环境配置）\n")
                    .append("missing: ").append(missing.isEmpty() ? "无" : String.join(", ", missing))
                    .append('\n');
            List<String> placeholders = states.entrySet().stream()
                    .filter(e -> e.getValue() == ValueState.PLACEHOLDER)
                    .map(Map.Entry::getKey).toList();
            if (!placeholders.isEmpty()) {
                sb.append("placeholders: ").append(String.join(", ", placeholders))
                        .append("（疑似示例值/掩码，等同于没配）\n");
            }
            sb.append("scannedFiles: ").append(scannedFiles).append('\n');
            if (!candidates.isEmpty()) {
                sb.append("candidates:\n");
                candidates.entrySet().stream().limit(30).forEach(e -> {
                    sb.append("  ").append(e.getKey()).append(" = ");
                    List<Candidate> list = e.getValue();
                    sb.append(list.stream().limit(4)
                            .map(c -> (c.secret() ? "****" : c.value()) + " @ " + c.source()
                                    + (c.envHint().isBlank() ? "" : " [" + c.envHint() + "]"))
                            .toList());
                    sb.append('\n');
                });
            }
            if (!conflicts.isEmpty()) {
                sb.append("conflicts: ").append(String.join("；", conflicts)).append('\n');
            }
            if (needsUserInput) {
                sb.append("status: NEED_USER_INPUT\n")
                        .append("ask: 项目环境里还缺配置或存在多个候选。请告诉我要使用哪一组值，")
                        .append("或先在「设置 → 项目环境」补齐；确认前我不会继续执行。\n")
                        .append("catalog: 缺失项若属于 DeepSeek / 阿里云 OSS / SMTP 邮箱等内置场景，")
                        .append("可先调 config_catalog 查看键名、含义与默认值，再让用户补值。");
            } else {
                sb.append("status: READY");
            }
            return sb.toString();
        }
    }

    public Resolution resolve(ProjectContext ctx, String env, List<String> requestedKeys) {
        String target = normalize(env);
        EffectiveEnvironment effective = envStore.effective(ctx.projectId(), target);
        Map<String, EnvVariable> known = new LinkedHashMap<>();
        for (EnvVariable v : effective.vars()) {
            known.put(v.key(), v);
        }
        Path repo = repo(ctx.projectId());
        ProjectEnvFiles.Declared declared = repo == null
                ? ProjectEnvFiles.Declared.empty() : ProjectEnvFiles.scan(repo);
        Scan scan = repo == null ? new Scan(List.of(), 0) : scanAll(repo);

        Set<String> keys = new LinkedHashSet<>();
        if (requestedKeys != null) {
            requestedKeys.stream().filter(k -> k != null && !k.isBlank()).forEach(keys::add);
        }
        if (keys.isEmpty()) {
            keys.addAll(known.keySet());
            keys.addAll(declared.keys());
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, ValueState> states = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        Map<String, List<Candidate>> candidates = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();

        Map<String, List<Candidate>> scanned = new LinkedHashMap<>();
        for (Candidate c : scan.candidates()) {
            scanned.computeIfAbsent(c.key(), k -> new ArrayList<>()).add(c);
        }

        for (String key : keys) {
            EnvVariable v = known.get(key);
            if (v != null && !v.value().isBlank()) {
                resolved.put(key, v.value());
                sources.put(key, v.source());
                // 敏感项的展示值本身就是掩码，不能按字面判断占位；有值即视为已配置。
                boolean masked = v.secret()
                        || EnvVariable.KIND_SECRET_REF.equals(v.valueKind());
                states.put(key, masked ? ValueState.PRESENT : ValueState.of(key, v.value()));
                continue;
            }
            String declaredValue = declared.values().get(key);
            if (declaredValue != null && !declaredValue.isBlank()) {
                boolean secretValue = looksSecret(key) || looksSecretValue(declaredValue);
                resolved.put(key, secretValue ? "****" : declaredValue);
                sources.put(key, "REPO_DECLARED");
                states.put(key, secretValue ? ValueState.PRESENT
                        : ValueState.of(key, declaredValue));
                continue;
            }
            List<Candidate> found = scanned.getOrDefault(key, List.of());
            if (!found.isEmpty()) {
                candidates.put(key, found);
                long distinct = found.stream().map(Candidate::value).distinct().count();
                if (distinct > 1) {
                    conflicts.add(key + " 有 " + distinct + " 个不同候选");
                }
            }
            if (declared.required().contains(key) || known.containsKey(key)
                    || ScenarioCatalog.key(key).isPresent()) {
                missing.add(key);
                states.putIfAbsent(key, ValueState.MISSING);
            }
        }

        // 扫描到但未被请求的 key 也进入候选，便于用户补录；限制数量避免刷屏。
        scanned.entrySet().stream()
                .filter(e -> !keys.contains(e.getKey()))
                .limit(40)
                .forEach(e -> candidates.put(e.getKey(), e.getValue()));

        boolean needsUserInput = !missing.isEmpty() || !conflicts.isEmpty()
                || states.values().stream().anyMatch(s -> s == ValueState.PLACEHOLDER)
                || candidates.entrySet().stream().anyMatch(e -> declared.required().contains(e.getKey()))
                // 项目环境与仓库声明都没有可用值时，扫描候选不能自行拍板：交给用户选。
                || (keys.isEmpty() && resolved.isEmpty() && !candidates.isEmpty());
        return new Resolution(ctx.projectId(), target, resolved, sources, states, missing,
                candidates, conflicts, scan.files(), needsUserInput);
    }

    private Path repo(String projectId) {
        return repoLocator.repoDir(projectId).orElse(null);
    }

    private record Scan(List<Candidate> candidates, int files) {
    }

    private Scan scanAll(Path root) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, Set.of(), 6, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!dir.equals(root) && SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (interesting(file.getFileName().toString())) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 用已收集到的部分
        }
        files.sort(Comparator.comparing(Path::toString));
        List<Candidate> out = new ArrayList<>();
        for (Path file : files) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String hint = hint(rel);
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            for (int i = 0; i < lines.size() && out.size() < 300; i++) {
                String line = lines.get(i);
                Matcher assign = ASSIGN.matcher(line);
                if (assign.matches()) {
                    String key = assign.group(1);
                    String value = strip(assign.group(2));
                    if (looksConfigKey(key) && !value.isBlank() && !value.startsWith("#")) {
                        out.add(candidate(key, value, rel + ":" + (i + 1), hint));
                    }
                    continue;
                }
                Matcher image = IMAGE.matcher(line);
                if (image.find()) {
                    out.add(new Candidate("IMAGE_TAG", image.group(1), rel + ":" + (i + 1),
                            false, hint));
                }
            }
        }
        return new Scan(List.copyOf(out), files.size());
    }

    private static Candidate candidate(String key, String value, String source, String hint) {
        boolean secret = looksSecret(key) || looksSecretValue(value);
        return new Candidate(key, secret ? "" : value, source, secret, hint);
    }

    private static boolean interesting(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("dockerfile") || lower.startsWith("readme")
                || (lower.startsWith("docker-compose") && (lower.endsWith(".yml") || lower.endsWith(".yaml")))
                || (lower.startsWith("compose") && (lower.endsWith(".yml") || lower.endsWith(".yaml")))
                || lower.endsWith(".md")
                || lower.startsWith("application")
                || (lower.endsWith(".yml") || lower.endsWith(".yaml"));
    }

    private static boolean looksConfigKey(String key) {
        String upper = key.toUpperCase(Locale.ROOT);
        return upper.contains("_") || upper.endsWith("HOST") || upper.endsWith("PORT")
                || upper.endsWith("URL") || upper.endsWith("URI") || upper.contains("TOKEN")
                || upper.contains("SECRET") || upper.contains("PASSWORD") || upper.endsWith("KEY")
                || upper.endsWith("VERSION") || upper.endsWith("DATABASE") || upper.endsWith("REDIS");
    }

    private static boolean looksSecret(String key) {
        String upper = key.toUpperCase(Locale.ROOT);
        return upper.contains("PASSWORD") || upper.contains("SECRET") || upper.contains("TOKEN")
                || upper.endsWith("_KEY") || upper.contains("PRIVATE_KEY") || upper.contains("CREDENTIAL");
    }

    /** 值本身的凭据特征：连接串内嵌用户名/密码，或高熵长串。 */
    private static boolean looksSecretValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.matches("(?i)^[a-z][a-z0-9+.-]*://[^/\\s:@]+:[^/\\s]+@.*")) {
            return true;
        }
        return highEntropy(value);
    }

    private static boolean highEntropy(String value) {
        if (value.length() < 24 || value.contains(" ") || value.startsWith("${")) {
            return false;
        }
        long classes = value.chars().filter(Character::isDigit).count() > 0 ? 1 : 0;
        classes += value.chars().filter(Character::isUpperCase).count() > 0 ? 1 : 0;
        classes += value.chars().filter(Character::isLowerCase).count() > 0 ? 1 : 0;
        classes += value.chars().anyMatch(c -> !Character.isLetterOrDigit(c)) ? 1 : 0;
        return classes >= 3 && value.length() >= 32;
    }

    private static String strip(String value) {
        String v = value.trim();
        int comment = v.indexOf(" #");
        if (comment > 0) {
            v = v.substring(0, comment).trim();
        }
        if (v.length() > 1) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                v = v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    private static String hint(String path) {
        Matcher m = ENV_HINT.matcher(path.toLowerCase(Locale.ROOT));
        if (m.find()) {
            String h = m.group(1);
            return "production".equals(h) ? "prod" : h;
        }
        return "";
    }

    public static String normalize(String env) {
        String value = env == null || env.isBlank() ? "test" : env.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "dev", "test", "prod" -> value;
            default -> "test";
        };
    }
}
