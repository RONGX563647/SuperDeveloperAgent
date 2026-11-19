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
        assertTrue(updated.contains("[\"docker\", \"buildx\", \"build\", \"--load\", \"-f\", \"Dockerfile\", \"-t\", \"${tag}\", \".\"]"));
        assertNull(DockerfileTools.addImageRecipe(updated, "Dockerfile"));
    }

    @Test
    void imageRecipeCreatesFileFromScratch() {
        String updated = DockerfileTools.addImageRecipe("", "deploy/backend.Dockerfile");

        assertTrue(updated.startsWith("actions:\n"));
        assertTrue(updated.contains("\"deploy/backend.Dockerfile\""));
        assertFalse(updated.contains("image_package:\n  image_package:"));
    }

    // ---------------------------------------------------------------- 可执行包

    /** 造一个带/不带 Main-Class 的 jar。 */
    private static void writeJar(Path target, String mainClass) throws IOException {
        Files.createDirectories(target.getParent());
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (mainClass != null) {
            manifest.getMainAttributes().putValue("Main-Class", mainClass);
        }
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(
                Files.newOutputStream(target), manifest)) {
            out.putNextEntry(new java.util.jar.JarEntry("placeholder.txt"));
            out.write("x".getBytes());
            out.closeEntry();
        }
    }

    /** 造一个声明了 classifier 的 Spring Boot 模块。 */
    private static Path springBootModuleWithClassifier(Path repo, String classifier) throws IOException {
        Path module = repo.resolve("platform/agent-gateway");
        Files.createDirectories(module.resolve("src/main/java/top/x"));
        Files.writeString(module.resolve("src/main/java/top/x/AgentApplication.java"),
                "class AgentApplication {}");
        Files.writeString(module.resolve("pom.xml"), """
                <project>
                  <build><plugins>
                    <plugin>
                      <artifactId>spring-boot-maven-plugin</artifactId>
                      <executions><execution>
                        <goals><goal>repackage</goal></goals>
                        <configuration>
                          <classifier>%s</classifier>
                        </configuration>
                      </execution></executions>
                    </plugin>
                  </plugins></build>
                </project>
                """.formatted(classifier));
        return module;
    }

    @Test
    void readsRepackageClassifier(@TempDir Path repo) throws IOException {
        Path module = springBootModuleWithClassifier(repo, "exec");
        assertEquals("exec", DockerfileTools.springBootJarClassifier(module));
    }

    @Test
    void noClassifierWhenPluginAbsent(@TempDir Path repo) throws IOException {
        Path module = repo.resolve("platform/app");
        Files.createDirectories(module);
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        assertNull(DockerfileTools.springBootJarClassifier(module));
    }

    @Test
    void copyTargetsClassifierJarSoContainerCanStart(@TempDir Path repo) throws IOException {
        // 事故复现：repackaged 包带 classifier，普通 jar 没有 Main-Class。
        // Dockerfile 若写 target/*.jar 就会拷到普通包 → 容器报 no main manifest attribute。
        springBootModuleWithClassifier(repo, "exec");
        DockerfileTools.Plan plan = DockerfileTools.detect(repo, null);

        assertTrue(plan.content().contains("COPY platform/agent-gateway/target/*-exec.jar /app/app.jar"),
                "必须锁定 *-exec.jar，实际：\n" + plan.content());
        assertFalse(plan.content().contains("target/*.jar /app/app.jar"),
                "不能再用通配 *，否则会拷到无 Main-Class 的普通包");
        assertTrue(plan.content().contains("classifier=exec"), "生成物里要写明原因");
    }

    @Test
    void preflightRejectsNonExecutableJars(@TempDir Path repo) throws IOException {
        Path module = springBootModuleWithClassifier(repo, "exec");
        // 只有普通包，缺 -exec.jar：正是线上那次失败的状态
        writeJar(module.resolve("target/agent-gateway-1.0.jar"), null);

        String note = DockerfileTools.checkRunnableJar(repo);

        assertTrue(note.startsWith("err:"), note);
        assertTrue(note.contains("no main manifest attribute"), "要指明容器会怎么炸：" + note);
        assertTrue(note.contains("*-exec.jar"), "要给出可执行包的确切文件名模式：" + note);
    }

    @Test
    void preflightAcceptsExecutableJar(@TempDir Path repo) throws IOException {
        Path module = springBootModuleWithClassifier(repo, "exec");
        writeJar(module.resolve("target/agent-gateway-1.0.jar"), null);
        writeJar(module.resolve("target/agent-gateway-1.0-exec.jar"),
                "org.springframework.boot.loader.launch.JarLauncher");

        String note = DockerfileTools.checkRunnableJar(repo);

        assertFalse(note.startsWith("err:"), note);
        assertTrue(note.contains("agent-gateway-1.0-exec.jar"), note);
    }

    @Test
    void preflightSkipsWhenNotBuiltYet(@TempDir Path repo) throws IOException {
        springBootModuleWithClassifier(repo, "exec");
        // target 还没生成：无从判断，不该误报
        assertEquals("", DockerfileTools.checkRunnableJar(repo));
    }

    @Test
    void hasMainClassDistinguishesBothKinds(@TempDir Path repo) throws IOException {
        Path plain = repo.resolve("plain.jar");
        Path boot = repo.resolve("boot.jar");
        writeJar(plain, null);
        writeJar(boot, "org.springframework.boot.loader.launch.JarLauncher");

        assertFalse(DockerfileTools.hasMainClass(plain));
        assertTrue(DockerfileTools.hasMainClass(boot));
    }
}
