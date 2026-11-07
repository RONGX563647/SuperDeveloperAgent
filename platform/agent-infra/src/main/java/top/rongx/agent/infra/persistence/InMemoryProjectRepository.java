package top.rongx.agent.infra.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectEnv;
import org.springframework.stereotype.Repository;

/**
 * 项目仓储的内存实现。
 *
 * <p>骨架阶段用它跑通链路；接入数据库时替换为 JPA/MyBatis 实现，domain 侧无需改动。
 */
@Repository
public class InMemoryProjectRepository implements ProjectRepository {

    private final Map<String, Project> projects = new ConcurrentHashMap<>();
    private final Map<String, List<ProjectEnv>> envs = new ConcurrentHashMap<>();

    public InMemoryProjectRepository() {
        register(new Project("RY", "RuoYi-Vue-Plus", "https://gitee.com/dromara/RuoYi-Vue-Plus.git",
                "gitee", "ruoyi-plus", 8080, 8099));
        envs.put("RY", List.of(
                new ProjectEnv("RY", "dev", "http://127.0.0.1:8080", "/opt/apps/ry"),
                new ProjectEnv("RY", "test", "http://127.0.0.1:8081", "/opt/apps/ry"),
                new ProjectEnv("RY", "prod", "http://127.0.0.1:80", "/opt/apps/ry")));
    }

    public void register(Project project) {
        projects.put(project.id(), project);
    }

    @Override
    public Optional<Project> findById(String projectId) {
        return Optional.ofNullable(projects.get(projectId));
    }

    @Override
    public List<Project> findAll() {
        return List.copyOf(projects.values());
    }

    @Override
    public List<ProjectEnv> findEnvs(String projectId) {
        return envs.getOrDefault(projectId, List.of());
    }
}
