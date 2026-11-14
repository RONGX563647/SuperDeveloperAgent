package top.rongx.agent.infra.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agent Skill 仓库（设置页「Skill」）。
 *
 * <p>目录约定对齐 Agent Skills 开源规范：每个技能一个目录，必需 {@code SKILL.md}，
 * 其 YAML frontmatter 的 {@code name} / {@code description} 是唯一的发现依据；
 * 可附带 {@code scripts/}、{@code references/}、{@code assets/}。
 *
 * <p>根目录：{@code <workspace>/_platform/skills}（平台侧，不污染用户仓库）。
 */
@Component
public class SkillsStore implements top.rongx.agent.domain.settings.SkillCatalog {

    private static final String SKILL_FILE = "SKILL.md";
    /** 启用状态文件：与技能目录同级，避免在技能目录里塞标记文件被当成资源。 */
    private static final String STATE_FILE = "skills-enabled.properties";

    private final Path root;
    private final Path stateFile;
    /** 状态文件读写锁：设置页与对话侧可能并发读取。 */
    private final Object stateLock = new Object();

    public SkillsStore(@Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.root = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("skills");
        this.stateFile = this.root.getParent().resolve(STATE_FILE);
    }

    @Override
    public Path root() {
        return root;
    }

    /** 技能条目（列表视图）：dirName 是目录名（接口标识），name 是 frontmatter 里的展示名。 */
    public record Skill(String name, String description, String dirName, String dir,
                        int fileCount, boolean enabled, long updatedAt) {
    }

    public List<Skill> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            List<Skill> out = new ArrayList<>();
            dirs.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(SKILL_FILE)))
                    .sorted()
                    .forEach(dir -> out.add(read(dir)));
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("技能目录读取失败：" + e.getMessage(), e);
        }
    }

    /** 读取 SKILL.md（预览用）；不存在返回 empty。 */
    public Optional<String> content(String name) {
        Path file = skillFile(name);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("技能内容读取失败：" + e.getMessage(), e);
        }
    }

    /** 新建或覆盖：content 已带 frontmatter 时原样落盘，否则自动补 name/description。 */
    public Skill create(String name, String description, String content) {
        String safe = requireName(name);
        Path dir = root.resolve(safe);
        try {
            Files.createDirectories(dir);
            String body = content == null ? "" : content;
            Files.writeString(dir.resolve(SKILL_FILE),
                    withFrontmatter(body, safe, description), StandardCharsets.UTF_8);
            return read(dir);
        } catch (IOException e) {
            throw new IllegalStateException("技能写入失败：" + e.getMessage(), e);
        }
    }

    /** 从本地目录导入：源目录必须含 SKILL.md，复制到技能根目录（同名拒绝覆盖）。 */
    public Skill importFrom(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("目录路径不能为空");
        }
        Path source = Path.of(sourcePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("目录不存在：" + source);
        }
        if (!Files.isRegularFile(source.resolve(SKILL_FILE))) {
            throw new IllegalArgumentException("该目录不是技能：缺少 " + SKILL_FILE);
        }
        String name = source.getFileName() == null ? "" : source.getFileName().toString();
        String safe = requireName(name.toLowerCase(Locale.ROOT));
        Path target = root.resolve(safe);
        if (Files.exists(target)) {
            throw new IllegalArgumentException("同名技能已存在：" + safe);
        }
        try {
            Files.createDirectories(root);
            copyTree(source, target);
            return read(target);
        } catch (IOException e) {
            throw new IllegalStateException("技能导入失败：" + e.getMessage(), e);
        }
    }

    public void delete(String name) {
        Path dir = root.resolve(requireName(name));
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("技能不存在：" + name);
        }
        try {
            deleteTree(dir);
        } catch (IOException e) {
            throw new IllegalStateException("技能删除失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- internals

    private Path skillFile(String name) {
        return root.resolve(requireName(name)).resolve(SKILL_FILE);
    }

    private Skill read(Path dir) {
        String content;
        try {
            content = Files.readString(dir.resolve(SKILL_FILE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            content = "";
        }
        Frontmatter fm = parseFrontmatter(content);
        String dirName = dir.getFileName().toString();
        String name = fm.name() == null || fm.name().isBlank() ? dirName : fm.name();
        long updatedAt;
        try {
            updatedAt = Files.getLastModifiedTime(dir.resolve(SKILL_FILE)).toMillis();
        } catch (IOException e) {
            updatedAt = 0L;
        }
        return new Skill(name, fm.description() == null ? "" : fm.description(),
                dirName, dir.toString(), countFiles(dir), isEnabled(dirName), updatedAt);
    }

    // ---------------------------------------------------------------- 启用开关

    /** 技能是否启用；无记录时默认启用（保持老数据行为）。 */
    public boolean isEnabled(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        synchronized (stateLock) {
            Properties properties = loadState();
            return !"false".equalsIgnoreCase(properties.getProperty(name.trim()));
        }
    }

    /** 设置启用状态（只允许已存在的技能）。 */
    public boolean setEnabled(String name, boolean enabled) {
        String safe = requireName(name);
        if (!Files.isRegularFile(root.resolve(safe).resolve(SKILL_FILE))) {
            throw new IllegalArgumentException("技能不存在：" + safe);
        }
        synchronized (stateLock) {
            Properties properties = loadState();
            properties.setProperty(safe, String.valueOf(enabled));
            storeState(properties);
        }
        return enabled;
    }

    /** 默认启用的技能目录名（对话未显式选择时使用）。 */
    @Override
    public Set<String> enabledNames() {
        Set<String> out = new LinkedHashSet<>();
        for (Skill skill : list()) {
            if (skill.enabled()) {
                out.add(skill.dirName());
            }
        }
        return out;
    }

    private Properties loadState() {
        Properties properties = new Properties();
        if (!Files.isRegularFile(stateFile)) {
            return properties;
        }
        try (InputStream in = Files.newInputStream(stateFile)) {
            properties.load(in);
        } catch (IOException e) {
            // 状态文件损坏不应阻塞技能使用：按默认（启用）处理
            return new Properties();
        }
        return properties;
    }

    private void storeState(Properties properties) {
        try {
            Files.createDirectories(stateFile.getParent());
            try (OutputStream out = Files.newOutputStream(stateFile)) {
                properties.store(out, "skill enabled state");
            }
        } catch (IOException e) {
            throw new IllegalStateException("技能启用状态保存失败：" + e.getMessage(), e);
        }
    }

    private static int countFiles(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return (int) walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (!n.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(
                    "技能目录名只能是小写字母、数字与短横线（1-64 字符），例如 java-review");
        }
        return n;
    }

    private static String withFrontmatter(String content, String name, String description) {
        if (content.stripLeading().startsWith("---")) {
            return content;
        }
        String desc = description == null ? "" : description.trim();
        String body = content.strip().isEmpty() ? "# " + name + "\n" : content;
        return "---\nname: " + name + "\ndescription: " + desc + "\n---\n\n" + body;
    }

    private record Frontmatter(String name, String description) {
    }

    /** 单行 YAML frontmatter 解析：足够覆盖 name / description（规范只读这两个字段）。 */
    static Frontmatter parseFrontmatter(String content) {
        if (content == null || !content.startsWith("---")) {
            return new Frontmatter(null, null);
        }
        String[] lines = content.split("\\R");
        String name = null;
        String description = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().equals("---")) {
                break;
            }
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim();
            String value = stripQuotes(line.substring(idx + 1).trim());
            if (key.equals("name")) {
                name = value;
            } else if (key.equals("description")) {
                description = value;
            }
        }
        return new Frontmatter(name, description);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 复制目录：只复制普通文件与子目录，跳过符号链接（防越界）。 */
    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Path rel = source.relativize(dir);
                Files.createDirectories(target.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (attrs.isRegularFile()) {
                    Path rel = source.relativize(file);
                    Files.copy(file, target.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
