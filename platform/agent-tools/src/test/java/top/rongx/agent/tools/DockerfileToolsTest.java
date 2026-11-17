package top.rongx.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.error.PlatformException;

class DockerfileToolsTest {

    @Test
    void detectsJavaModuleWithFrontendDist(@TempDir Path repo) throws IOException {
        Path module = repo.resolve("platform/agent-gateway");
        Files.createDirectories(module.resolve("src/main/java/top/x"));
        Files.writeString(module.resolve("src/main/java/top/x/AgentApplication.java"), "class AgentApplication {}");
        Files.writeString(repo.resolve("platform/pom.xml"),
                "<project><properties><java.version>21</java.version></properties></project>");
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Files.createDirectories(module.resolve("src/main/resources"));
        Files.writeString(module.resolve("src/main/resources/application.yml"), "server:\n  port: 8090\n");
        Files.createDirectories(repo.resolve("platform/web/dist"));
        Files.writeString(repo.resolve("platform/web/dist/index.html"), "<html></html>");

        DockerfileTools.Plan plan = DockerfileTools.detect(repo, null);

        assertEquals("java", plan.stack());
        assertTrue(plan.content().contains("FROM eclipse-temurin:21-jre"));
        assertTrue(plan.content().contains("COPY platform/agent-gateway/target/*.jar /app/app.jar"));
        assertTrue(plan.content().contains("COPY platform/web/dist/ /app/web/"));
        assertTrue(plan.content().contains("ENV AGENT_WEB_DIST=/app/web"));
        assertTrue(plan.content().contains("ENV SERVER_PORT=8090"));
        assertTrue(plan.content().contains("EXPOSE 8090"));
    }

    @Test
    void detectsFrontendOnlyDist(@TempDir Path repo) throws IOException {
        Files.createDirectories(repo.resolve("web/dist"));
        Files.writeString(repo.resolve("web/dist/index.html"), "<html></html>");

        DockerfileTools.Plan plan = DockerfileTools.detect(repo, null);

        assertEquals("frontend", plan.stack());
        assertTrue(plan.content().contains("FROM nginx:1.27-alpine"));
        assertTrue(plan.content().contains("COPY web/dist/ /usr/share/nginx/html/"));
    }

    @Test
    void unknownStackFailsWithActionableMessage(@TempDir Path repo) {
        PlatformException error = assertThrows(PlatformException.class,
                () -> DockerfileTools.detect(repo, null));
        assertTrue(error.getMessage().contains("未能识别技术栈"));
    }

    @Test
    void imageStageIsInsertedBeforeDeploy() {
        String yaml = """
                name: release
                deploy: {target: docker, port: 8182, containerPort: 8090}
                stages:
                - {id: build, type: action, action: backend_build, runner: container, workdir: platform}
                - {id: deploy, type: deploy}
                - {id: health, type: health, url: 'http://127.0.0.1:8182/actuator/health'}
                on-failure:
                - {id: rollback, type: rollback}
                """;

        String updated = DockerfileTools.addImageStage(yaml);

        assertTrue(updated.contains("action: image_package"));
        assertTrue(updated.indexOf("image_package") < updated.indexOf("type: deploy"));
        // 幂等：已经包含镜像阶段时不再改动
        assertNull(DockerfileTools.addImageStage(updated));
    }

    @Test
    void imageStageKeepsBlockStyleIndent() {
        String yaml = """
                stages:
                  - id: build
                    type: action
                    action: backend_build
                  - id: deploy
                    type: deploy
                """;

        String updated = DockerfileTools.addImageStage(yaml);

        assertTrue(updated.contains("\n  - {id: image, type: action, action: image_package, runner: host}\n"));
        assertTrue(updated.indexOf("image_package") < updated.indexOf("id: deploy"));
    }

    @Test
    void imageRecipeIsAppendedAndIdempotent() {
        String actions = """
                actions:
                  backend_build:
                    run:
                      - ["mvn", "-B", "-q", "-DskipTests", "package"]
                    timeout: 10m
                """;

        String updated = DockerfileTools.addImageRecipe(actions, "Dockerfile");

        assertTrue(updated.contains("image_package:"));
        assertTrue(updated.contains("[\"docker\", \"build\", \"-f\", \"Dockerfile\", \"-t\", \"${tag}\", \".\"]"));
        assertNull(DockerfileTools.addImageRecipe(updated, "Dockerfile"));
    }

    @Test
    void imageRecipeCreatesFileFromScratch() {
        String updated = DockerfileTools.addImageRecipe("", "deploy/backend.Dockerfile");

        assertTrue(updated.startsWith("actions:\n"));
        assertTrue(updated.contains("\"deploy/backend.Dockerfile\""));
        assertFalse(updated.contains("image_package:\n  image_package:"));
    }
}
