package top.rongx.agent.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.deploy.DeployRecipe;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.port.PipelineDefStore;
import top.rongx.agent.domain.port.PipelineEngine;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.domain.project.ProjectEnv;
import top.rongx.agent.domain.settings.DeployServerRepository;

/** 首次发布就绪清单：只验证聚合规则（缺什么 → 哪个修复入口）。 */
class ProjectReadinessControllerTest {

    @TempDir
    Path repo;

    private final ProjectRepoLocator locator = mock(ProjectRepoLocator.class);
    private final PipelineDefStore defStore = mock(PipelineDefStore.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final RecipeStore recipes = mock(RecipeStore.class);
    private final DeployServerRepository servers = mock(DeployServerRepository.class);
    private final EnvironmentStore envStore = mock(EnvironmentStore.class);
    private final PipelineEngine engine = mock(PipelineEngine.class);

    private ProjectReadinessController controller(String legacyServerHost) {
        return new ProjectReadinessController(
                locator, defStore, projects, recipes, servers, envStore, engine,
                new top.rongx.agent.executor.PipelineDefinitionInspectorImpl(),
                legacyServerHost);
    }

    @Test
    void dockerProjectWithEverythingConfiguredIsReadyButNotYetRun() throws Exception {
        Files.writeString(repo.resolve("Dockerfile"), "FROM eclipse-temurin:21");
        Path ci = Files.createDirectories(repo.resolve("ci"));
        Files.writeString(ci.resolve("pipeline.yml"), """
                name: release
                stages:
                  - id: build
                    type: action
                    action: backend_build
                  - id: deploy
                    type: deploy
                deploy:
                  target: docker
                  port: 8080
                """);
        when(locator.repoDir("SHOP")).thenReturn(Optional.of(repo));
        when(defStore.exists("SHOP")).thenReturn(false);
        when(projects.findEnvs("SHOP")).thenReturn(List.of(new ProjectEnv("SHOP", "test", "", "")));
        when(engine.dockerAvailable()).thenReturn(true);
        when(recipes.current("SHOP", "test")).thenReturn(Optional.empty());

        Map<String, Object> out = controller("").readiness("SHOP", "test");

        assertThat(out.get("ready")).isEqualTo(true);
        assertThat(out.get("readyCount")).isEqualTo(5);
        assertThat(out.get("totalCount")).isEqualTo(5);
        assertThat(step(out, "first-run").get("ok")).isEqualTo(false);
        assertThat(step(out, "first-run").get("required")).isEqualTo(false);
    }

    @Test
    void missingPipelineAsksForScaffold() {
        when(locator.repoDir("SHOP")).thenReturn(Optional.of(repo));
        when(defStore.exists("SHOP")).thenReturn(false);
        when(projects.findEnvs("SHOP")).thenReturn(List.of(new ProjectEnv("SHOP", "test", "", "")));

        Map<String, Object> out = controller("").readiness("SHOP", "test");

        assertThat(out.get("ready")).isEqualTo(false);
        Map<String, Object> pipeline = step(out, "pipeline");
        assertThat(pipeline.get("ok")).isEqualTo(false);
        assertThat(((Map<?, ?>) pipeline.get("fix")).get("kind")).isEqualTo("scaffold");
    }

    @Test
    void serverTargetWithoutServerPointsAtSettings() throws Exception {
        Path ci = Files.createDirectories(repo.resolve("ci"));
        Files.writeString(ci.resolve("pipeline.yml"), """
                name: release
                stages:
                  - id: deploy
                    type: deploy
                deploy:
                  target: server
                """);
        when(locator.repoDir("SHOP")).thenReturn(Optional.of(repo));
        when(defStore.exists("SHOP")).thenReturn(false);
        when(projects.findEnvs("SHOP")).thenReturn(List.of(new ProjectEnv("SHOP", "test", "", "")));
        when(servers.findDefault()).thenReturn(Optional.empty());

        Map<String, Object> out = controller("").readiness("SHOP", "test");

        Map<String, Object> runtime = step(out, "runtime");
        assertThat(runtime.get("ok")).isEqualTo(false);
        assertThat(((Map<?, ?>) runtime.get("fix")).get("target")).isEqualTo("/settings?section=servers");
    }

    @Test
    void missingRequiredEnvVarIsReported() throws Exception {
        Path ci = Files.createDirectories(repo.resolve("ci"));
        Files.writeString(ci.resolve("pipeline.yml"), """
                name: release
                stages:
                  - id: deploy
                    type: deploy
                deploy:
                  target: host
                  env-required:
                    - DB_PASSWORD
                """);
        when(locator.repoDir("SHOP")).thenReturn(Optional.of(repo));
        when(defStore.exists("SHOP")).thenReturn(false);
        when(projects.findEnvs("SHOP")).thenReturn(List.of(new ProjectEnv("SHOP", "test", "", "")));
        when(envStore.effective("SHOP", "test")).thenReturn(new EffectiveEnvironment(
                "SHOP", "test", null, 0, null, List.of(), Map.of(), List.of(), 0, 0));
        when(recipes.current("SHOP", "test")).thenReturn(Optional.of(
                new DeployRecipe("SHOP", "test", 1, "yaml", null, "run-1", "abc1234", 1L, "u")));

        Map<String, Object> out = controller("").readiness("SHOP", "test");

        Map<String, Object> vars = step(out, "envvars");
        assertThat(vars.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(vars.get("detail"))).contains("DB_PASSWORD");
        assertThat(out.get("ready")).isEqualTo(false);
    }

    private static Map<String, Object> step(Map<String, Object> out, String key) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) out.get("steps");
        return steps.stream()
                .filter(s -> key.equals(s.get("key")))
                .findFirst()
                .orElseThrow();
    }
}
