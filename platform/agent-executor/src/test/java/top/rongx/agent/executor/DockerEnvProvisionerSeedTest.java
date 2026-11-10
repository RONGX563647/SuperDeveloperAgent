package top.rongx.agent.executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.rongx.agent.domain.env.EnvironmentStore;

class DockerEnvProvisionerSeedTest {

    @Test
    void seedDeclaredInjectsDotEnvValuesAndSkipsOptionalBlanks(@TempDir Path root)
            throws IOException {
        Path docker = Files.createDirectories(root.resolve("docker"));
        write(docker.resolve(".env.example"), """
                # ── 邮件（可选）──
                SMTP_USERNAME=

                # ── 外部 MySQL（必填）──
                DB_HOST=
                """);
        write(docker.resolve(".env"), """
                DB_HOST=111.230.60.71
                DB_PASSWORD=mysql_secret
                SMTP_USERNAME=
                """);

        List<String> saved = new ArrayList<>();
        EnvironmentStore store = (EnvironmentStore) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{EnvironmentStore.class},
                (proxy, method, args) -> {
                    if ("saveOverride".equals(method.getName())) {
                        saved.add(args[2] + "=" + args[3]);
                    }
                    if ("effective".equals(method.getName())) {
                        return effective(Map.of());
                    }
                    return defaultValue(method.getReturnType());
                });
        DockerEnvProvisioner provisioner = new DockerEnvProvisioner(store,
                new top.rongx.agent.domain.port.ProjectRepoLocator() {
                    @Override
                    public Optional<Path> repoDir(String projectId) {
                        return Optional.of(root);
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
                });

        List<String> seeded = provisioner.seedDeclared("RUOYIWNZ", "dev");

        assertTrue(seeded.contains("DB_HOST"));
        assertTrue(seeded.contains("DB_PASSWORD"));
        assertFalse(seeded.contains("SMTP_USERNAME"), "空值不应写入覆盖");
        assertTrue(saved.contains("DB_HOST=111.230.60.71"));
    }

    @Test
    void seedDeclaredDoesNotOverwriteExistingOverrides(@TempDir Path root) throws IOException {
        Path docker = Files.createDirectories(root.resolve("docker"));
        write(docker.resolve(".env"), """
                DB_HOST=111.230.60.71
                REDIS_HOST=111.230.60.71
                """);

        List<String> saved = new ArrayList<>();
        EnvironmentStore store = (EnvironmentStore) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{EnvironmentStore.class},
                (proxy, method, args) -> {
                    if ("saveOverride".equals(method.getName())) {
                        saved.add(args[2] + "=" + args[3]);
                    }
                    if ("effective".equals(method.getName())) {
                        // 设置页里已经配过 DB_HOST：陈旧 .env 不允许把它改回去
                        return effective(Map.of("DB_HOST", "agent-dev-ruoyiwnz-mysql"));
                    }
                    return defaultValue(method.getReturnType());
                });
        DockerEnvProvisioner provisioner = new DockerEnvProvisioner(store,
                new top.rongx.agent.domain.port.ProjectRepoLocator() {
                    @Override
                    public Optional<Path> repoDir(String projectId) {
                        return Optional.of(root);
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
                });

        List<String> seeded = provisioner.seedDeclared("RUOYIWNZ", "dev");

        assertFalse(seeded.contains("DB_HOST"), "已配置的键不应被 .env 覆盖");
        assertTrue(seeded.contains("REDIS_HOST"), "缺失的键仍应由 .env 补齐");
        assertFalse(saved.contains("DB_HOST=111.230.60.71"));
    }

    private static top.rongx.agent.domain.env.EffectiveEnvironment effective(Map<String, String> values) {
        return new top.rongx.agent.domain.env.EffectiveEnvironment(
                "RUOYIWNZ", "dev", "agent-dev-net", 8688, "",
                List.of(), values, List.of(), 0, 0);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
