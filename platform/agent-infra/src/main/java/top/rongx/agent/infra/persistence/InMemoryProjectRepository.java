package top.rongx.agent.infra.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectEnv;

/**
 * 预置项目源（application.yml 的 {@code agent.projects}）：只读，作为两源合并中的"配置预置"层。
 *
 * <p>对外唯一视图见 {@link CompositeProjectRepository}（预置 ∪ SQLite 接入项）。
 * Bean 装配在 {@link ProjectStoreConfig}。
 */
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
