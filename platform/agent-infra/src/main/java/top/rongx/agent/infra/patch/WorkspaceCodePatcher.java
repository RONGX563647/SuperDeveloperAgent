package top.rongx.agent.infra.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.patch.CodePatch;
import top.rongx.agent.domain.port.CodePatcher;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.infra.git.GitWorktreeManager;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.PatchPathDeniedException;
import top.rongx.agent.domain.error.RunException;

/**
 * 受限补丁器：Agent 能改动的仓库内容全部经这里落地。
 *
 * <p>安全边界（实现层保证，调用方无法绕过）：
 * <ul>
 *   <li>路径白名单：业务源码（{@code src/main/**}、{@code platform/<模块>/src/main/**}）、
 *       测试代码（{@code src/test/**}）、{@code ci/}、{@code Jenkinsfile}、构建配置
 *       （pom.xml / build.gradle*）、部署形态文件（Dockerfile / compose / nginx / 应用配置）；</li>
 *   <li>路径黑名单（优先级高于白名单）：编译产物（{@code target/}、{@code build/}、{@code dist/}、
 *       {@code node_modules/}）、{@code .git/}、密钥与凭证文件（{@code .env}、
 *       {@code *.pem}、{@code *.key}、{@code id_rsa*}、{@code .npmrc} 等）；</li>
 *   <li>路径逃逸校验：拒绝绝对路径与 {@code ..}；</li>
 *   <li>不写用户当前分支：应用时从 baseSha 建临时 worktree，提交到独立分支后清理 worktree；</li>
 *   <li>单文件大小与文件数上限，避免把整个仓库塞进一次提交。</li>
 * </ul>
 *
 * <p>拒绝时不抛笼统错误：抛 {@link PatchPathDeniedException}，带上被拒路径、命中的规则与
 * 完整允许清单，工具层转成结构化的 {@code PATH_DENIED} 返回给模型。
 */
@Component
public class WorkspaceCodePatcher implements CodePatcher {

    /** 允许清单（同时用于提示词与错误信息）。 */
    private static final List<String> ALLOWED_PATTERNS = List.of(
            // 业务源码与测试代码：Agent 要真正能改代码，这两条必须放行
            "src/main/**", "**/src/main/**",
            "src/test/**", "**/src/test/**",
            "ci/**", "Jenkinsfile",
            "**/pom.xml", "**/build.gradle", "**/build.gradle.kts",
            // 部署/运行形态相关：Dockerfile、compose、nginx、应用配置、前端构建脚本
            "Dockerfile", "**/Dockerfile", "**/*.Dockerfile", "**/.dockerignore",
            "deploy/**", "**/docker-compose*.yml", "**/docker-compose*.yaml",
            "**/nginx*.conf", "**/nginx/**",
            "**/application*.yml", "**/application*.yaml", "**/application*.properties",
            "**/package.json",
            "**/.env.example", "**/.env.sample", "**/.env.template");

    /** 拒绝清单：命中即拒，即使同时命中允许规则。 */
    private static final List<String> DENIED_PATTERNS = List.of(
            ".git/**", "**/.git/**",
            "**/target/**", "**/build/**", "**/dist/**", "**/node_modules/**",
            "**/.idea/**", "**/.gradle/**", "**/.mvn/**",
            "**/.env", "**/.env.*", "!**/.env.example", "!**/.env.sample", "!**/.env.template",
            "**/*.pem", "**/*.key", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore",
            "**/id_rsa*", "**/id_ed25519*", "**/.npmrc", "**/.netrc", "**/.pgpass",
            "**/credentials*", "**/secrets*");

    private static final int MAX_FILES = 10;
    private static final int MAX_CONTENT_CHARS = 400_000;

    /** 目录名级别的硬拒绝：任何层级出现即拒（与 glob 双保险）。 */
    private static final List<String> DENIED_SEGMENTS = List.of(
            ".git", "target", "build", "dist", "node_modules", ".idea", ".gradle", ".mvn");

    private final GitWorktreeManager git;
    private final ProjectRepoLocator locator;

    public WorkspaceCodePatcher(GitWorktreeManager git, ProjectRepoLocator locator) {
        this.git = git;
        this.locator = locator;
    }

    @Override
    public List<String> allowedPatterns() {
        return ALLOWED_PATTERNS;
    }

    @Override
    public List<String> deniedPatterns() {
        return DENIED_PATTERNS;
    }

    @Override
    public Staged stage(String projectId, String baseSha, List<CodePatch.FileEdit> edits) {
        if (baseSha == null || !baseSha.matches("[0-9a-f]{7,40}")) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "base_sha 需 7-40 位小写十六进制（失败运行的提交）");
        }
        if (edits == null || edits.isEmpty()) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "至少需要一个文件改动");
        }
        if (edits.size() > MAX_FILES) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "一次最多修改 " + MAX_FILES + " 个文件，收到 " + edits.size());
        }
        Path repo = requireRepo(projectId);
        if (!hasCommit(repo, baseSha)) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "提交不存在：" + baseSha);
        }

        StringBuilder diff = new StringBuilder();
        List<String> files = new ArrayList<>();
        int added = 0;
        int removed = 0;
        for (CodePatch.FileEdit edit : edits) {
            String path = normalize(edit.path());
            // 先过守卫：路径被拒时立刻抛结构化异常，不进入 diff 生成（省一次 git 子进程）
            guard(path);
            if (edit.content().length() > MAX_CONTENT_CHARS) {
                throw new RunException(ErrorCode.RUN_INVALID_INPUT, "文件内容过大：" + path
                        + "（上限 " + MAX_CONTENT_CHARS + " 字符）");
            }
            String oldText = git.showFile(repo, baseSha, path);
            String one = git.diffNoIndex(repo, path, oldText, edit.content());
            for (String line : one.split("\\R")) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    added++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    removed++;
                }
            }
            diff.append(one);
            files.add(path);
        }
        if (added == 0 && removed == 0) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "补丁没有产生任何改动：内容与 base_sha 一致");
        }
        String text = diff.toString();
        return new Staged(text, sha256(text), added, removed, files);
    }

    @Override
    public String apply(CodePatch patch) {
        Path repo = requireRepo(patch.projectId());
        String name = "wt-patch-" + patch.patchId();
        ProjectRepoLocator.StorageMode mode = locator.modeOf(patch.projectId());
        Path worktree = mode == ProjectRepoLocator.StorageMode.DIRECT
                ? git.addWorktreeAt(repo, name, patch.baseSha())
                : git.addWorktree(patch.projectId(), name, patch.baseSha());
        try {
            for (CodePatch.FileEdit edit : patch.edits()) {
                String relative = normalize(edit.path());
                // 应用阶段再校一次：补丁可能来自持久化存储，不能只信 stage 时的判断
                guard(relative);
                Path target = resolveInside(worktree, relative);
                try {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, edit.content(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new RunException(ErrorCode.RUN_INVALID_STATE, "写入补丁文件失败：" + edit.path()
                            + "（" + e.getMessage() + "）");
                }
            }
            git.createBranch(worktree, patch.branch());
            return git.commitAll(worktree,
                    "agent: 修复流水线失败（" + patch.patchId() + "，人工确认后应用）");
        } finally {
            if (mode == ProjectRepoLocator.StorageMode.DIRECT) {
                git.removeWorktreeAt(repo, name);
            } else {
                git.removeWorktree(patch.projectId(), name);
            }
        }
    }

    /**
     * 路径守卫：拒绝时抛 {@link PatchPathDeniedException}（带允许清单）。
     *
     * <p>顺序有意义：逃逸检查 → 目录段拒绝 → 密钥/产物拒绝 → 允许清单。
     * 先拒后允，避免白名单里的宽泛规则把危险路径放进来。
     */
    private static void guard(String path) {
        if (path.isBlank()) {
            throw denied(path, "路径为空");
        }
        if (path.startsWith("/") || path.contains("..") || path.contains(":")) {
            throw denied(path, "绝对路径或路径逃逸");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String segment : DENIED_SEGMENTS) {
            if (lower.equals(segment) || lower.startsWith(segment + "/") || lower.contains("/" + segment + "/")
                    || lower.endsWith("/" + segment)) {
                throw denied(path, "命中禁止目录 " + segment + "/");
            }
        }
        if (isSecretFile(lower)) {
            throw denied(path, "命中凭证/密钥文件规则");
        }
        if (!allowed(path)) {
            throw denied(path, "不在允许清单内");
        }
    }

    /** 凭证与密钥文件：任何层级出现即拒（.env.example 这种模板不算）。 */
    private static boolean isSecretFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.equals(".env.example") || name.equals(".env.sample") || name.equals(".env.template")) {
            return false;
        }
        if (name.equals(".env") || name.startsWith(".env.")) {
            return true;
        }
        return name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12")
                || name.endsWith(".pfx") || name.endsWith(".jks") || name.endsWith(".keystore")
                || name.startsWith("id_rsa") || name.startsWith("id_ed25519")
                || name.equals(".npmrc") || name.equals(".netrc") || name.equals(".pgpass")
                || name.startsWith("credentials") || name.startsWith("secret");
    }

    private static PatchPathDeniedException denied(String path, String rule) {
        return new PatchPathDeniedException(path, rule, ALLOWED_PATTERNS, DENIED_PATTERNS);
    }

    private Path requireRepo(String projectId) {
        return locator.repoDir(projectId).orElseThrow(() ->
                new RunException(ErrorCode.RUN_INVALID_INPUT, "项目尚未接入 git 仓库：" + projectId));
    }

    private boolean hasCommit(Path repo, String sha) {
        try {
            return !git.revParse(repo, sha + "^{commit}").isBlank();
        } catch (RuntimeException notFound) {
            return false;
        }
    }

    private static String normalize(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        return value;
    }

    private static boolean allowed(String path) {
        if (path.isBlank() || path.startsWith("/") || path.contains("..")) {
            return false;
        }
        return path.startsWith("src/main/") || path.contains("/src/main/")
                || path.startsWith("src/test/") || path.contains("/src/test/")
                || path.startsWith("ci/")
                || path.equals("Jenkinsfile")
                || path.equals("pom.xml") || path.endsWith("/pom.xml")
                || path.equals("build.gradle") || path.endsWith("/build.gradle")
                || path.equals("build.gradle.kts") || path.endsWith("/build.gradle.kts")
                || path.equals("Dockerfile") || path.endsWith("/Dockerfile")
                || path.endsWith(".Dockerfile")
                || path.equals(".dockerignore") || path.endsWith("/.dockerignore")
                || path.startsWith("deploy/") || path.contains("/deploy/")
                || path.endsWith("/docker-compose.yml") || path.endsWith("/docker-compose.yaml")
                || path.equals("docker-compose.yml") || path.equals("docker-compose.yaml")
                || path.endsWith(".conf") && path.contains("nginx")
                || path.contains("/nginx/")
                || isApplicationConfig(path)
                || path.equals("package.json") || path.endsWith("/package.json")
                || path.endsWith("/.env.example") || path.endsWith("/.env.sample")
                || path.endsWith("/.env.template")
                || path.equals(".env.example") || path.equals(".env.sample")
                || path.equals(".env.template");
    }

    /** application*.yml / properties（只允许这三类扩展名，避免误放业务配置）。 */
    private static boolean isApplicationConfig(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.startsWith("application")
                && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"));
    }

    private static Path resolveInside(Path root, String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new RunException(ErrorCode.RUN_INVALID_INPUT, "路径超出仓库范围：" + relative);
        }
        return resolved;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString();
        }
    }
}
