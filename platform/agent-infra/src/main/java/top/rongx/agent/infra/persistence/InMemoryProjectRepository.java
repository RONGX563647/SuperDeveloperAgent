package top.rongx.agent.infra.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectEnv;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Repository;

/**
 * 项目仓储的内存实现（数据来自配置 {@code agent.projects}，不在代码里写死任何项目）。
 *
 * <p>骨架阶段的占位实现：只读配置、常驻内存；接入数据库时替换为本类的 JPA/MyBatis 版本，
 * domain 侧无需改动。重启后项目清单从配置恢复（不再是"重启即丢"）。
 */
@Repository
@EnableConfigurationProperties(ProjectsProperties.class)
public class InMemoryProjectRepository implements ProjectRepository {

    private final Map<String, Project> projects = new ConcurrentHashMap<>();
    private final Map<String, List<ProjectEnv>> envs = new ConcurrentHashMap<>();

    public InMemoryProjectRepository(ProjectsProperties props) {
        for (ProjectsProperties.ProjectDef def : props.items()) {
            Project project = new Project(def.id(), def.name(), def.repoUrl(),
                    def.repoType(), def.type(), def.portRangeStart(), def.portRangeEnd());
            projects.put(project.id(), project);
            envs.put(project.id(), def.envs().stream()
                    .map(e -> new ProjectEnv(def.id(), e.env(), e.appUrl(), e.composeDir()))
                    .toList());
        }
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
