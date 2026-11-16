package top.rongx.agent.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

/** 任务配图读取：文件名白名单与 Content-Type。 */
class GitIssueImageControllerTest {

    @TempDir
    Path workspace;

    @Test
    @DisplayName("非法文件名与目录穿越一律 404")
    void rejectsInvalidNames() {
        GitIssueImageController controller = new GitIssueImageController(workspace.toString());

        assertThrows(ResponseStatusException.class, () -> controller.image("../openapi.json"));
        assertThrows(ResponseStatusException.class, () -> controller.image("avatar.png"));
        assertThrows(ResponseStatusException.class, () -> controller.image(UUID.randomUUID() + ".svg"));
    }

    @Test
    @DisplayName("存在的小写 UUID png 按图片类型返回")
    void servesPng() throws Exception {
        Path dir = workspace.resolve("_platform/issues/images");
        Files.createDirectories(dir);
        String name = UUID.randomUUID() + ".png";
        Files.write(dir.resolve(name), new byte[] {1, 2, 3});
        GitIssueImageController controller = new GitIssueImageController(workspace.toString());

        var response = controller.image(name);

        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertTrue(response.getBody().exists());
    }
}
