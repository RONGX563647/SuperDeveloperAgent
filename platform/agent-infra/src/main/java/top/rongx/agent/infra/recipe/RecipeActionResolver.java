package top.rongx.agent.infra.recipe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.port.ActionResolver;

/**
 * 配方解析（方案 D）。
 *
 * <p>解析顺序（先命中先用）：
 * <ol>
 *   <li>{@code ci/actions.yml} —— 纯数据，跨平台，推荐；</li>
 *   <li>{@code Taskfile.yml} —— 若声明了对应 target，则执行 {@code task <target>}；</li>
 *   <li>{@code Makefile} —— 若声明了对应 target，则执行 {@code make <target>}；</li>
 *   <li>平台内置默认（仅 backend_build / frontend_build）。</li>
 * </ol>
 *
 * <p><b>安全边界</b>：这里只做"读与校验"。命令内容来自仓库（人写的、review 过的），
 * 模型只能选动作；对 make/task 还要校验 target 确实存在，避免模型拼出任意 target。
 * 来源 commit 一并采集，用于审计"哪个文件@哪个提交"。
 */
@Component
public class RecipeActionResolver implements ActionResolver {

    private static final Logger log = LoggerFactory.getLogger(RecipeActionResolver.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);
    private static final String CI_ACTIONS = "ci/actions.yml";
    private static final String TASKFILE = "Taskfile.yml";
    private static final String MAKEFILE = "Makefile";

    /** Makefile 目标行：行首非空白 + 名字 + 冒号。 */
    private static final Pattern MAKE_TARGET = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9_.-]*)\\s*:(?!=)");

    /** 内置默认配方（仓库没有覆盖时使用）。 */
    private static final Map<ActionId, List<List<String>>> BUILTIN_STEPS = Map.of(
            ActionId.BACKEND_BUILD, List.of(List.of("mvn", "-B", "-q", "-DskipTests", "package")),
            ActionId.FRONTEND_BUILD, List.of(
                    List.of("pnpm", "install", "--frozen-lockfile", "--ignore-scripts"),
                    List.of("pnpm", "build")));

    private static final Map<ActionId, List<String>> BUILTIN_PRODUCE = Map.of(
            ActionId.BACKEND_BUILD, List.of("target/*.jar"),
            ActionId.FRONTEND_BUILD, List.of("dist/index.html"));

    /** make/task 的默认 target 名。 */
    private static final Map<ActionId, String> DEFAULT_TARGET = Map.of(
            ActionId.BACKEND_BUILD, "backend-build",
            ActionId.FRONTEND_BUILD, "frontend-build",
            ActionId.IMAGE_PACKAGE, "image-package");

    @Override
    public Optional<ActionSpec> resolve(Path repoRoot, ActionId action) {
        if (repoRoot == null || action == null) {
            return Optional.empty();
        }
        // 1. 采集来源 commit（仅仓库来源有意义）
        String commit = resolveCommit(repoRoot);
        // 2. 多源并存时告警（保留 actions.yml 优先，但提示用户可能存在漂移）
        detectSourceConflict(repoRoot);
        // 3. 按优先级解析，命中即返回
        Optional<ActionSpec> fromYml = fromCiActions(repoRoot, action, commit);
        if (fromYml.isPresent()) {
            return fromYml;
        }
        Optional<ActionSpec> fromTask = fromTaskfile(repoRoot, action, commit);
        if (fromTask.isPresent()) {
            return fromTask;
        }
        Optional<ActionSpec> fromMake = fromMakefile(repoRoot, action, commit);
        if (fromMake.isPresent()) {
            return fromMake;
        }
        return fromBuiltin(action);
    }

    @Override
    public List<ActionSpec> resolveAll(Path repoRoot) {
        List<ActionSpec> specs = new ArrayList<>();
        for (ActionId action : ActionId.values()) {
            resolve(repoRoot, action).ifPresent(specs::add);
        }
        return List.copyOf(specs);
    }

    @Override
    public boolean hasRecipe(Path repoRoot) {
        return repoRoot != null
                && (Files.isRegularFile(repoRoot.resolve(CI_ACTIONS))
                || Files.isRegularFile(repoRoot.resolve(TASKFILE))
                || Files.isRegularFile(repoRoot.resolve(MAKEFILE)));
    }

    // ---------------------------------------------------------------- ci/actions.yml

    private Optional<ActionSpec> fromCiActions(Path repoRoot, ActionId action, String commit) {
        Path file = repoRoot.resolve(CI_ACTIONS);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Map<?, ?> root = loadYaml(file);
        if (!(root.get("actions") instanceof Map<?, ?> actions)) {
            return Optional.empty();
        }
        if (!(actions.get(action.wireName()) instanceof Map<?, ?> def)) {
            return Optional.empty();
        }
        List<List<String>> steps = parseSteps(def.get("run"), action, CI_ACTIONS);
        // 解析参数声明（含枚举约束与默认值），失败即抛错（fail-closed：配置错误不静默）
        Object rawParams = def.get("params");
        Map<String, String> params = parseParams(rawParams, action);
        List<String> produce = parseStringList(def.get("produce"));
        if (produce.isEmpty()) {
            produce = BUILTIN_PRODUCE.getOrDefault(action, List.of());
        }
        Duration timeout = parseTimeout(def.get("timeout"));
        Map<String, List<String>> allowed = parseParamAllowed(rawParams, action);
        log.debug("配方命中 {}.{} → {}", CI_ACTIONS, action.wireName(), steps);
        return Optional.of(new ActionSpec(action, steps, produce, timeout,
                ActionSource.of(ActionSource.Kind.CI_ACTIONS, CI_ACTIONS + "#" + action.wireName(), commit),
                params, allowed));
    }

    /**
     * 解析动作参数声明：{@code params: [{name, allowed: [...], default}]}。
     * 返回已填默认值的参数表；若默认值不在 allowed 枚举内则抛错。
     */
    private Map<String, String> parseParams(Object raw, ActionId action) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String name = str(m, "name", (String) null);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(CI_ACTIONS + " 中 " + action.wireName()
                        + ".params 的条目必须声明 name");
            }
            List<String> allowed = parseStringList(m.get("allowed"));
            String def = m.get("default") == null ? null : String.valueOf(m.get("default"));
            if (def == null) {
                throw new IllegalArgumentException(CI_ACTIONS + " 中 " + action.wireName()
                        + ".params." + name + " 必须声明 default");
            }
            if (!allowed.isEmpty() && !allowed.contains(def)) {
                throw new IllegalArgumentException(CI_ACTIONS + " 中 " + action.wireName()
                        + ".params." + name + " 默认值 " + def + " 不在 allowed " + allowed);
            }
            out.put(name, def);
        }
        return Map.copyOf(out);
    }

    /** 解析每个参数的允许取值（allowed）；无约束时返回空 list。 */
    private Map<String, List<String>> parseParamAllowed(Object raw, ActionId action) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String name = str(m, "name", (String) null);
            if (name == null || name.isBlank()) {
                continue;
            }
            out.put(name, parseStringList(m.get("allowed")));
        }
        return Map.copyOf(out);
    }

    // ---------------------------------------------------------------- Taskfile / Makefile

    private Optional<ActionSpec> fromTaskfile(Path repoRoot, ActionId action, String commit) {
        Path file = repoRoot.resolve(TASKFILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String target = DEFAULT_TARGET.get(action);
        if (target == null) {
            return Optional.empty();
        }
        Map<?, ?> root = loadYaml(file);
        if (!(root.get("tasks") instanceof Map<?, ?> tasks) || !tasks.containsKey(target)) {
            return Optional.empty();
        }
        // 只执行 task 的 target，不解析其内部命令——命令由人在 Taskfile 里定义
        return Optional.of(new ActionSpec(action,
                List.of(List.of("task", target)),
                BUILTIN_PRODUCE.getOrDefault(action, List.of()),
                DEFAULT_TIMEOUT,
                ActionSource.of(ActionSource.Kind.TASKFILE, TASKFILE + "#" + target, commit)));
    }

    private Optional<ActionSpec> fromMakefile(Path repoRoot, ActionId action, String commit) {
        Path file = repoRoot.resolve(MAKEFILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String target = DEFAULT_TARGET.get(action);
        if (target == null) {
            return Optional.empty();
        }
        if (!makefileTargets(file).contains(target)) {
            return Optional.empty();
        }
        return Optional.of(new ActionSpec(action,
                List.of(List.of("make", target)),
                BUILTIN_PRODUCE.getOrDefault(action, List.of()),
                DEFAULT_TIMEOUT,
                ActionSource.of(ActionSource.Kind.MAKEFILE, MAKEFILE + "#" + target, commit)));
    }

    private List<String> makefileTargets(Path file) {
        try {
            List<String> targets = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (line.isEmpty() || line.charAt(0) == '\t' || line.charAt(0) == '#') {
                    continue;
                }
                Matcher m = MAKE_TARGET.matcher(line);
                if (m.find()) {
                    targets.add(m.group(1));
                }
            }
            return targets;
        } catch (IOException e) {
            log.warn("读取 Makefile 失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ---------------------------------------------------------------- builtin

    private Optional<ActionSpec> fromBuiltin(ActionId action) {
        List<List<String>> steps = BUILTIN_STEPS.get(action);
        if (steps == null) {
            return Optional.empty();
        }
        return Optional.of(new ActionSpec(action, steps,
                BUILTIN_PRODUCE.getOrDefault(action, List.of()),
                DEFAULT_TIMEOUT,
                ActionSource.builtin(action.wireName())));
    }

    // ---------------------------------------------------------------- commit / 冲突

    /** 读取仓库当前 HEAD 的 commit（用于审计@commit）。非 git 仓库或损坏时返回 null。 */
    private String resolveCommit(Path repoRoot) {
        try {
            Path head = repoRoot.resolve(".git/HEAD");
            if (!Files.isRegularFile(head)) {
                return null;
            }
            String content = Files.readString(head).trim();
            if (content.startsWith("ref:")) {
                Path refFile = repoRoot.resolve(".git").resolve(content.substring(4).trim());
                if (Files.isRegularFile(refFile)) {
                    String sha = Files.readString(refFile).trim();
                    return sha.length() >= 40 ? sha.substring(0, 40) : sha;
                }
                return null;
            }
            // detached HEAD：内容即 commit
            return content.length() >= 40 ? content.substring(0, 40) : content;
        } catch (IOException e) {
            log.warn("读取 commit 失败 {}: {}", repoRoot, e.getMessage());
            return null;
        }
    }

    /** 多个配方源并存时告警：行为以 actions.yml 为准，但提示可能存在漂移。 */
    private void detectSourceConflict(Path repoRoot) {
        Set<String> present = Set.of(CI_ACTIONS, TASKFILE, MAKEFILE);
        long count = present.stream()
                .filter(name -> Files.isRegularFile(repoRoot.resolve(name)))
                .count();
        if (count > 1) {
            log.warn("检测到多个配方源并存（{}），解析以 {} 优先；若意图是单一来源请删掉其余，避免漂移",
                    present, CI_ACTIONS);
        }
    }

    // ---------------------------------------------------------------- helpers

    private Map<?, ?> loadYaml(Path file) {
        try {
            Object loaded = new Yaml().load(Files.readString(file));
            return loaded instanceof Map<?, ?> map ? map : Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败: " + file, e);
        } catch (RuntimeException e) {
            log.warn("YAML 解析失败 {}: {}", file, e.getMessage());
            return Map.of();
        }
    }

    /** 支持 `run: [argv...]`（单条）与 `run: [[argv...],[argv...]]`（多条）。 */
    private List<List<String>> parseSteps(Object run, ActionId action, String where) {
        if (!(run instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(where + " 中 " + action.wireName() + ".run 必须是非空数组");
        }
        if (list.get(0) instanceof List<?>) {
            List<List<String>> steps = new ArrayList<>();
            for (Object item : list) {
                steps.add(toStringList(item));
            }
            return List.copyOf(steps);
        }
        return List.of(toStringList(list));
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("命令必须是字符串数组，收到: " + value);
        }
        List<String> argv = new ArrayList<>();
        for (Object o : list) {
            argv.add(String.valueOf(o));
        }
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("命令不能为空数组");
        }
        return List.copyOf(argv);
    }

    private List<String> parseStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            out.add(String.valueOf(o));
        }
        return List.copyOf(out);
    }

    /** 支持 "20m" / "30s" / "1h" / 纯数字（分钟）。 */
    private Duration parseTimeout(Object value) {
        if (value == null) {
            return DEFAULT_TIMEOUT;
        }
        if (value instanceof Number n) {
            return Duration.ofMinutes(n.longValue());
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        try {
            if (text.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2)));
            }
            if (text.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            return Duration.ofMinutes(Long.parseLong(text));
        } catch (NumberFormatException e) {
            log.warn("无法解析 timeout: {}，使用默认 {}", value, DEFAULT_TIMEOUT);
            return DEFAULT_TIMEOUT;
        }
    }

    /** 供测试与诊断：内置默认的动作集合。 */
    public static Map<ActionId, List<List<String>>> builtinSteps() {
        return new LinkedHashMap<>(BUILTIN_STEPS);
    }

    /** 从 {@code Map<?,?>} 里安全取字符串（避开通配符捕获的泛型问题；null 时返回 fallback）。 */
    private static String str(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
