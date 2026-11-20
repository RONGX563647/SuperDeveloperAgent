package top.rongx.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvBinding;
import top.rongx.agent.domain.env.EnvResource;
import top.rongx.agent.domain.env.EnvTemplate;
import top.rongx.agent.domain.env.EnvVariable;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.PortLease;
import top.rongx.agent.domain.env.SecretEntry;
import top.rongx.agent.domain.env.ServiceLease;
import top.rongx.agent.domain.port.PipelineDefStore;
import top.rongx.agent.domain.port.ProjectRepoLocator;

/**
 * BUG-44：「生效端口」解析口径。
 *
 * <p>部署器在期望端口被占时会换端口并把真实端口写进 {@code PortLease}；
 * 解析必须租约优先，pipeline.yml 的 deploy.port 只是未部署时的期望值。
 */
class EffectiveEndpointResolverTest {

    private static final String PIPELINE = """
            name: release
            deploy:
              target: docker
              port: 8182
              containerPort: 8080
              healthPath: /actuator/health
            """;

    @TempDir
    Path repo;

    private static EffectiveEnvironment effective(String env, int defaultPort) {
        return new EffectiveEnvironment("SHOP", env, null, defaultPort, "/healthz",
                List.of(), Map.of(), List.of(), 0, 0);
    }

    private static LeaseStore storeWith(ServiceLease... leases) {
        LeaseStore store = new LeaseStore();
        for (ServiceLease lease : leases) {
            store.saveServiceLease(lease);
        }
        return store;
    }


    private EffectiveEndpointResolver resolver(EnvironmentStore store) {
        FakeDefStore defs = new FakeDefStore();
        defs.put("SHOP", PIPELINE);
        FakeLocator locator = new FakeLocator(repo);
        return new EffectiveEndpointResolver(defs, locator, store);
    }

    private void writePipeline() throws IOException {
        Files.createDirectories(repo.resolve("ci"));
        Files.writeString(repo.resolve("ci/pipeline.yml"), PIPELINE);
    }

    @Test
    @DisplayName("有 RUNNING 的本地服务租约：返回租约端口（18081），而非 pipeline.yml 的 8182")
    void runningServiceLeaseWins() {
        EnvironmentStore store = storeWith(
                new ServiceLease("SHOP", "dev", ServiceLease.BACKEND, 18081, "RUNNING", 42, 1L));
        EffectiveEndpointResolver.Endpoint endpoint = resolver(store).resolve("SHOP", "dev");
        assertEquals(18081, endpoint.port());
        assertTrue(endpoint.source().contains("租约"), endpoint.source());
    }

    @Test
    @DisplayName("容器端口租约：hostPort 覆盖 pipeline.yml 的期望端口，source 标注已部署")
    void portLeaseWinsOverPipelineYaml() throws IOException {
        writePipeline();
        LeaseStore store = new LeaseStore();
        store.saveLease(new PortLease("SHOP", "test", 8080, 18173, "run-1", 2L));
        EffectiveEndpointResolver.Endpoint endpoint = resolver(store).resolve("SHOP", "test");
        assertEquals(18173, endpoint.port(), "真实端口以租约为准");
        assertTrue(endpoint.source().contains("租约"), endpoint.source());
        assertEquals("/actuator/health", endpoint.healthPath(), "健康路径沿用定义");
    }

    @Test
    @DisplayName("无任何租约：回退 pipeline.yml 期望端口，source 明确标注未部署")
    void fallsBackToPipelineExpectedPort() throws IOException {
        writePipeline();
        EffectiveEndpointResolver.Endpoint endpoint =
                resolver(new LeaseStore()).resolve("SHOP", "test");
        assertEquals(8182, endpoint.port());
        assertTrue(endpoint.source().contains("未部署"), endpoint.source());
        assertTrue(endpoint.source().contains("期望端口"), endpoint.source());
    }

    @Test
    @DisplayName("dev/test 的 pipeline.yml 相同但环境模板默认端口不同 → env 参数生效")
    void envParamDifferentiatesWhenNoDeployDefinition() {
        EffectiveEndpointResolver resolver = new EffectiveEndpointResolver(
                new FakeDefStore(), new FakeLocator(repo), new LeaseStore());
        assertEquals(18080, resolver.resolve("SHOP", "dev").port());
        assertEquals(18081, resolver.resolve("SHOP", "test").port());
    }

    @Test
    @DisplayName("STOPPED 的本地服务租约不算已部署：回退期望端口")
    void stoppedLeaseDoesNotCount() {
        EnvironmentStore store = storeWith(
                new ServiceLease("SHOP", "dev", ServiceLease.BACKEND, 18081, "STOPPED", 0, 1L));
        EffectiveEndpointResolver.Endpoint endpoint = resolver(store).resolve("SHOP", "dev");
        assertTrue(endpoint.source().contains("未部署"), endpoint.source());
    }

    /** 端口/服务租约入内存，其余接口抛错（不应被解析路径触到）。 */
    private static final class LeaseStore implements EnvironmentStore {
        private final Map<String, PortLease> portLeases = new ConcurrentHashMap<>();
        private final Map<String, ServiceLease> serviceLeases = new ConcurrentHashMap<>();

        @Override
        public Optional<PortLease> lease(String projectId, String env, int containerPort) {
            return Optional.ofNullable(portLeases.get(projectId + "/" + env + "/" + containerPort));
        }

        @Override
        public List<PortLease> leases(String projectId, String env) {
            return portLeases.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(projectId + "/" + env + "/"))
                    .map(java.util.Map.Entry::getValue).toList();
        }

        @Override
        public void saveLease(PortLease lease) {
            portLeases.put(lease.projectId() + "/" + lease.env() + "/" + lease.containerPort(),
                    lease);
        }

        @Override
        public List<ServiceLease> serviceLeases(String projectId, String env) {
            return serviceLeases.values().stream()
                    .filter(l -> l.projectId().equals(projectId) && l.env().equals(env)).toList();
        }

        @Override
        public List<ServiceLease> allServiceLeases(String env) {
            return serviceLeases.values().stream()
                    .filter(l -> l.env().equals(env)).toList();
        }

        @Override
        public void saveServiceLease(ServiceLease lease) {
            serviceLeases.put(lease.projectId() + "/" + lease.env() + "/" + lease.service(),
                    lease);
        }

        @Override
        public void deleteServiceLease(String projectId, String env, String service) {
            serviceLeases.remove(projectId + "/" + env + "/" + service);
        }

        @Override
        public EffectiveEnvironment effective(String projectId, String env) {
            return new EffectiveEnvironment(projectId, env, null,
                    "dev".equals(env) ? 18080 : 18081, "/healthz",
                    List.of(), Map.of(), List.of(), 0, 0);
        }

        @Override
        public List<EnvTemplate> templates() {
            return List.of();
        }

        @Override
        public Optional<EnvTemplate> template(String env) {
            return Optional.empty();
        }

        @Override
        public void saveTemplate(EnvTemplate template) {
        }

        @Override
        public List<EnvResource> resources(String env) {
            return List.of();
        }

        @Override
        public Optional<EnvResource> resource(String id) {
            return Optional.empty();
        }

        @Override
        public EnvResource saveResource(EnvResource resource) {
            return resource;
        }

        @Override
        public void deleteResource(String id) {
        }

        @Override
        public void recordHealth(String id, boolean running, String error) {
        }

        @Override
        public List<EnvVariable> envVars(String env) {
            return List.of();
        }

        @Override
        public EnvVariable saveEnvVar(String env, String key, String value, boolean secret,
                                      String source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> envVarValue(String env, String key) {
            return Optional.empty();
        }

        @Override
        public int syncResourceVars(String env) {
            return 0;
        }

        @Override
        public void deleteEnvVar(String env, String key) {
        }

        @Override
        public List<EnvVariable> overrides(String projectId, String env) {
            return List.of();
        }

        @Override
        public EnvVariable saveOverride(String projectId, String env, String key, String value,
                                        boolean secret) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteOverride(String projectId, String env, String key) {
        }

        @Override
        public List<SecretEntry> secrets(String projectId, String env) {
            return List.of();
        }

        @Override
        public SecretEntry saveSecret(String projectId, String env, String name, String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SecretEntry rotateSecret(String projectId, String env, String name, String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteSecret(String projectId, String env, String name) {
        }

        @Override
        public Optional<String> secretValue(String projectId, String env, String name) {
            return Optional.empty();
        }

        @Override
        public EnvBinding saveBinding(EnvBinding binding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EnvBinding> binding(String id) {
            return Optional.empty();
        }

        @Override
        public Optional<EnvBinding> latestConfirmedBinding(String projectId, String env) {
            return Optional.empty();
        }

        @Override
        public List<EnvBinding> bindings(String projectId, String env, int limit) {
            return List.of();
        }

        @Override
        public void confirmBinding(String id, String confirmedBy, long expiresAt) {
        }

        @Override
        public Optional<EnvBinding> revokeBinding(String id, String actor) {
            return Optional.empty();
        }
    }

    /** 平台侧定义按项目入内存。 */
    private static final class FakeDefStore implements PipelineDefStore {
        private final Map<String, String> defs = new ConcurrentHashMap<>();

        void put(String projectId, String yaml) {
            defs.put(projectId, yaml);
        }

        @Override
        public java.nio.file.Path rootOf(String projectId) {
            return java.nio.file.Path.of("/tmp/defs/" + projectId);
        }

        @Override
        public boolean exists(String projectId) {
            return defs.containsKey(projectId);
        }

        @Override
        public Optional<String> read(String projectId) {
            return Optional.ofNullable(defs.get(projectId));
        }

        @Override
        public boolean write(String projectId, String pipelineYaml, String actionsYaml) {
            defs.put(projectId, pipelineYaml);
            return true;
        }
    }

    /** 仓库定位：都指向同一个临时目录。 */
    private record FakeLocator(Path repo) implements ProjectRepoLocator {
        @Override
        public Optional<Path> repoDir(String projectId) {
            return Optional.of(repo);
        }

        @Override
        public Optional<Path> worktreeRoot(String projectId) {
            return Optional.empty();
        }

        @Override
        public void sync(String projectId) {
        }

        @Override
        public StorageMode modeOf(String projectId) {
            return StorageMode.DIRECT;
        }
    }
}
