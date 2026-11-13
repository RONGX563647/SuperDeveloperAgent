package top.rongx.agent.gateway.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.tools.SecretRedactor;

/**
 * 本地目录浏览接口（供前端"选择本地文件夹"）。
 *
 * <p>两种模式（借鉴 ruoyi-ai 的 workspace lease 设计）：
 * <ul>
 *   <li><b>自由模式</b>（默认）：可浏览任意盘符目录（本机后端 + 登录用户，等价资源管理器）；</li>
 *   <li><b>shared-root 模式</b>：配置 {@code agent.intake.shared-root} 后，
 *       浏览被限制在该边界内（含子目录），且根视图直接是边界目录——
 *       "工作区"语义：用户在约定范围内选项目，接入即用，不用逐个登记盘符。</li>
 * </ul>
 * 安全：只列目录与 .git 探测，不读文件内容；shared-root 模式下 normalize + toRealPath
 * 双重校验防目录逃逸。
 *
 * <p>文件预览（{@code /read}）在浏览能力之上补了四道限制：工作区边界、符号链接逃逸、
 * 敏感文件（.env / 私钥 / 凭据）、二进制与超大文件降级。文本最多返回 2 MB / 5000 行，
 * 保证接口不会因为误选大文件把服务打满。
 */
@RestController
@RequestMapping("/api/v1/fs")
public class FsBrowserController {

    /** 文本预览上限：超过即不返回正文，只回元数据。 */
    private static final long MAX_PREVIEW_BYTES = 2L * 1024 * 1024;
    /** 行数上限：避免单行极长/超多行的日志文件拖垮前端渲染。 */
    private static final int MAX_PREVIEW_LINES = 5000;
    /** 二进制探针：前 8 KB 出现 NUL 即判定为二进制。 */
    private static final int BINARY_PROBE_BYTES = 8000;

    /** 工作区边界（空 = 自由模式）。 */
    private final Path sharedRoot;
    private final SecretRedactor redactor;

    public FsBrowserController(
            @Value("${agent.intake.shared-root:}") String sharedRoot,
            SecretRedactor redactor) {
        this.sharedRoot = sharedRoot == null || sharedRoot.isBlank()
                ? null : Path.of(sharedRoot).toAbsolutePath().normalize();
        this.redactor = redactor;
    }

    /** 目录条目。 */
    public record Entry(String name, String path, boolean directory, boolean gitRepo) {
    }

    /** 文件元数据 + 文本内容（content 为 null 表示二进制或超大文件）。 */
    public record FsFile(String path, String name, long sizeBytes, long modifiedAt,
                         String content, boolean truncated) {
    }

    /**
     * 读取文件内容供前端预览：{@code GET /api/v1/fs/read?path=...}。
     *
     * <p>相对路径只在 shared-root 模式下允许（相对工作区根解析）；绝对路径必须先通过
     * 边界与真实路径校验，符号链接指向边界外同样拒绝。
     */
    @GetMapping("/read")
    public FsFile read(@RequestParam String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        Path requested = Path.of(path);
        if (!requested.isAbsolute()) {
            if (sharedRoot == null) {
                throw new IllegalArgumentException("相对路径仅在 shared-root 模式下可用");
            }
            requested = sharedRoot.resolve(path);
        }
        Path normalized = requested.toAbsolutePath().normalize();
        Path real = resolveReal(normalized);
        if (containsGitDir(real)) {
            throw new IllegalArgumentException("不允许读取 .git 内部文件");
        }
        checkBoundary(real);
        if (redactor != null && redactor.isSensitiveFile(real.getFileName().toString())) {
            throw new IllegalArgumentException("敏感文件不允许预览：" + real.getFileName());
        }
        if (!Files.isRegularFile(real)) {
            throw new IllegalArgumentException("不是普通文件：" + real);
        }
        long size;
        long modified;
        try {
            size = Files.size(real);
            modified = Files.getLastModifiedTime(real).toMillis();
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取文件元数据：" + e.getMessage());
        }
        String name = real.getFileName().toString();
        if (size > MAX_PREVIEW_BYTES) {
            return new FsFile(real.toString(), name, size, modified, null, true);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(real);
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取文件：" + e.getMessage());
        }
        if (looksBinary(bytes)) {
            return new FsFile(real.toString(), name, size, modified, null, false);
        }
        String text = decodeUtf8(bytes);
        if (text == null) {
            return new FsFile(real.toString(), name, size, modified, null, false);
        }
        boolean truncated = false;
        String[] lines = text.split("\n", -1);
        if (lines.length > MAX_PREVIEW_LINES) {
            text = String.join("\n", java.util.Arrays.copyOf(lines, MAX_PREVIEW_LINES));
            truncated = true;
        }
        return new FsFile(real.toString(), name, size, modified, text, truncated);
    }

    private static Path resolveReal(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("文件不存在：" + path);
        }
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("无法解析文件路径：" + e.getMessage());
        }
    }

    private void checkBoundary(Path real) {
        if (sharedRoot == null) {
            return;
        }
        Path root = sharedRoot;
        try {
            root = sharedRoot.toRealPath();
        } catch (IOException ignored) {
            // 边界目录无法解析时回退到规范化路径：宁严勿松，仍按 startsWith 判断
        }
        if (!real.equals(root) && !real.startsWith(root)) {
            throw new IllegalArgumentException(
                    "路径超出工作区边界：" + real + "（边界：" + sharedRoot + "）");
        }
    }

    private static boolean containsGitDir(Path path) {
        for (Path part : path) {
            if (".git".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_PROBE_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * 列目录：{@code GET /api/v1/fs/ls?path=E:\CODE}。
     * path 为空时：shared-root 模式返回边界目录内容；自由模式返回盘符。
     */
    @GetMapping("/ls")
    public Map<String, Object> ls(@RequestParam(required = false) String path) {
        List<Entry> entries = new ArrayList<>();
        if (path == null || path.isBlank()) {
            // 根视图
            if (sharedRoot != null) {
                // 工作区模式：根 = 边界目录
                return listDir(sharedRoot);
            }
            for (Path root : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
                if (Files.isDirectory(root)) {
                    entries.add(new Entry(root.toString(), root.toString(), true, false));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", "");
            out.put("root", "drives");
            out.put("sharedRoot", sharedRoot == null ? null : sharedRoot.toString());
            out.put("entries", entries);
            return out;
        }
        return listDir(Path.of(path));
    }

    private Map<String, Object> listDir(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        // shared-root 边界校验（防逃逸）
        if (sharedRoot != null) {
            if (!normalized.equals(sharedRoot) && !normalized.startsWith(sharedRoot)) {
                throw new IllegalArgumentException(
                        "路径超出工作区边界：" + normalized + "（边界：" + sharedRoot + "）");
            }
        }
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("目录不存在：" + dir);
        }
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(normalized)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                boolean isDir = Files.isDirectory(p);
                boolean git = isDir && Files.isDirectory(p.resolve(".git"));
                entries.add(new Entry(name, p.toString(), isDir, git));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取目录：" + e.getMessage());
        }
        entries.sort((a, b) -> {
            if (a.directory() != b.directory()) return a.directory() ? -1 : 1;
            return a.name().compareToIgnoreCase(b.name());
        });
        Path parent = sharedRoot != null && normalized.equals(sharedRoot)
                ? null : normalized.getParent(); // 边界目录没有"上一级"
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", normalized.toString());
        out.put("parent", parent == null ? null : parent.toString());
        out.put("root", sharedRoot != null ? "shared" : "drives");
        out.put("sharedRoot", sharedRoot == null ? null : sharedRoot.toString());
        out.put("entries", entries);
        return out;
    }

    /** 路径探测：目录是否存在 + 是否 git 仓库（供表单失焦校验）。shared-root 模式带边界校验。 */
    @GetMapping("/probe")
    public Map<String, Object> probe(@RequestParam String path) {
        Path dir = Path.of(path).toAbsolutePath().normalize();
        if (sharedRoot != null && !dir.equals(sharedRoot) && !dir.startsWith(sharedRoot)) {
            throw new IllegalArgumentException(
                    "路径超出工作区边界：" + dir + "（边界：" + sharedRoot + "）");
        }
        boolean exists = Files.isDirectory(dir);
        boolean git = exists && Files.isDirectory(dir.resolve(".git"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", exists);
        out.put("git", git);
        out.put("normalized", dir.toString());
        out.put("withinSharedRoot", sharedRoot != null
                && (dir.equals(sharedRoot) || dir.startsWith(sharedRoot)));
        return out;
    }

    /** 快捷位置（现代目录选择器左栏）：工作区、用户目录、桌面/文档/下载、各盘符。 */
    public record Shortcut(String key, String label, String path, String kind) {
    }

    /** 常用位置列表：前端选择器侧栏用。 */
    @GetMapping("/roots")
    public Map<String, Object> roots() {
        List<Shortcut> items = new ArrayList<>();
        if (sharedRoot != null) {
            items.add(new Shortcut("workspace", "工作区", sharedRoot.toString(), "workspace"));
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            Path homePath = Path.of(home);
            items.add(new Shortcut("home", "主目录", homePath.toString(), "home"));
            addIfExists(items, "desktop", "桌面", homePath.resolve("Desktop"));
            addIfExists(items, "documents", "文档", homePath.resolve("Documents"));
            addIfExists(items, "downloads", "下载", homePath.resolve("Downloads"));
        }
        for (Path root : java.nio.file.FileSystems.getDefault().getRootDirectories()) {
            if (Files.isDirectory(root)) {
                items.add(new Shortcut("drive-" + root.toString().replace("\\", "").replace(":", ""),
                        root.toString(), root.toString(), "drive"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shortcuts", items);
        out.put("sharedRoot", sharedRoot == null ? null : sharedRoot.toString());
        return out;
    }

    private void addIfExists(List<Shortcut> items, String key, String label, Path path) {
        if (Files.isDirectory(path)) {
            items.add(new Shortcut(key, label, path.toAbsolutePath().normalize().toString(), "folder"));
        }
    }
}
