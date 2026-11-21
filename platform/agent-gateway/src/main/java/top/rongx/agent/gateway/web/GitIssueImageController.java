package top.rongx.agent.gateway.web;

import top.rongx.agent.domain.workspace.WorkspacePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 任务配图读取（匿名可访问）。
 *
 * <p>浏览器 {@code <img>} 不会带 Authorization，所以图片单独放在这个路径并在
 * {@link top.rongx.agent.gateway.auth.SaTokenConfig} 白名单里放行。安全性依赖两点：
 * 文件名是上传时生成、不可猜的 UUID，且只允许图片后缀；上传接口本身仍需登录。
 */
@RestController
@RequestMapping("/api/v1/git-issue-images")
public class GitIssueImageController {

    private static final String NAME_PATTERN = "[a-f0-9-]{36}\\.(png|jpg|jpeg|gif|webp)";

    private final Path imageDir;

    public GitIssueImageController(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot) {
        this.imageDir = WorkspacePaths.issueImages(Path.of(workspaceRoot));
    }

    /** 按文件名读取一张任务配图；未知或非法文件名一律 404。 */
    @GetMapping("/{name}")
    public ResponseEntity<Resource> image(@PathVariable String name) {
        if (name == null || !name.matches(NAME_PATTERN)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在");
        }
        Path file = imageDir.resolve(name).normalize();
        if (!file.startsWith(imageDir) || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "图片不存在");
        }
        MediaType type = switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.IMAGE_JPEG;
        };
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePrivate())
                .body(new FileSystemResource(file));
    }
}
