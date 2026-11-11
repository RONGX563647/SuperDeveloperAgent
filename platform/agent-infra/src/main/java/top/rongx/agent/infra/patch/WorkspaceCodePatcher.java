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
import java.util.UUID;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.patch.CodePatch;
import top.rongx.agent.domain.port.CodePatcher;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.infra.git.GitWorktreeManager;

/**
 * 受限补丁器：Agent 能改动的仓库内容全部经这里落地。
 *
 * <p>安全边界（实现层保证，调用方无法绕过）：
 * <ul>
 *   <li>路径白名单：{@code ci/}、{@code Jenkinsfile}、构建配置（pom.xml / build.gradle*）、
 *       {@code src/test/**}；业务代码、配置文件、凭证都不在范围内；</li>
 *   <li>路径逃逸校验：拒绝绝对路径与 {@code ..}；</li>
 *   <li>不写用户当前分支：应用时从 baseSha 建临时 worktree，提交到独立分支后清理 worktree；</li>
 *   <li>单文件大小与文件数上限，避免把整个仓库塞进一次提交。</li>
 * </ul>
 */
@Component
public class WorkspaceCodePatcher implements CodePatcher {

    /** 白名单说明（同时用于提示词与错误信息）。 */
    private static final List<String> ALLOWED_PATTERNS = List.of(
            "ci/**", "Jenkinsfile", "**/pom.xml", "**/build.gradle", "**/build.gradle.kts",
            "**/src/test/**");

    private static final int MAX_FILES = 10;
    private static final int MAX_CONTENT_CHARS = 400_000;

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
    public Staged stage(String projectId, String baseSha, List<CodePatch.FileEdit> edits) {
        if (baseSha == null || !baseSha.matches("[0-9a-f]{7,40}")) {
            throw new IllegalArgumentException("base_sha 需 7-40 位小写十六进制（失败运行的提交）");
        }
        if (edits == null || edits.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个文件改动");
        }
        if (edits.size() > MAX_FILES) {
            throw new IllegalArgumentException("一次最多修改 " + MAX_FILES + " 个文件，收到 " + edits.size());
        }
        Path repo = requireRepo(projectId);
        if (!hasCommit(repo, baseSha)) {
            throw new IllegalArgumentException("提交不存在：" + baseSha);
        }

        StringBuilder diff = new StringBuilder();
        List<String> files = new ArrayList<>();
        int added = 0;
        int removed = 0;
        for (CodePatch.FileEdit edit : edits) {
            String path = normalize(edit.path());
            if (!allowed(path)) {
                throw new IllegalArgumentException("不允许修改该路径：" + path
                        + "；允许范围：" + String.join(", ", ALLOWED_PATTERNS));
            }
            if (edit.content().length() > MAX_CONTENT_CHARS) {
                throw new IllegalArgumentException("文件内容过大：" + path
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
            throw new IllegalArgumentException("补丁没有产生任何改动：内容与 base_sha 一致");
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
                Path target = resolveInside(worktree, normalize(edit.path()));
                try {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, edit.content(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("写入补丁文件失败：" + edit.path()
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

    private Path requireRepo(String projectId) {
        return locator.repoDir(projectId).orElseThrow(() ->
                new IllegalArgumentException("项目尚未接入 git 仓库：" + projectId));
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
        return path.startsWith("ci/")
                || path.equals("Jenkinsfile")
                || path.equals("pom.xml") || path.endsWith("/pom.xml")
                || path.equals("build.gradle") || path.endsWith("/build.gradle")
                || path.equals("build.gradle.kts") || path.endsWith("/build.gradle.kts")
                || path.startsWith("src/test/") || path.contains("/src/test/");
    }

    private static Path resolveInside(Path root, String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IllegalArgumentException("路径超出仓库范围：" + relative);
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
