package top.rongx.agent.infra.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectEnv;

/**
 * 两源合并的项目仓储（对外唯一视图）：
 * <ul>
 *   <li>预置源（yml → InMemoryProjectRepository）：只读；</li>
 *   <li>接入源（SQLite → IntakeProjectStore）：可增删。</li>
 * </ul>
 * id 冲突时预置源优先（配置是明确的意图），并告警。
 */
public class CompositeProjectRepository implements ProjectRepository {

    private static final Logger log = LoggerFactory.getLogger(CompositeProjectRepository.class);

    private final ProjectRepository preset;
    private final IntakeProjectStore intake;

    public CompositeProjectRepository(ProjectRepository preset, IntakeProjectStore intake) {
        this.preset = preset;
        this.intake = intake;
    }

    @Override
    public Optional<Project> findById(String projectId) {
        Optional<Project> fromPreset = preset.findById(projectId);
        if (fromPreset.isPresent()) {
            return fromPreset;
        }
        return intake.find(projectId).map(IntakeProjectStore.IntakeProject::toProject);
    }

    @Override
    public List<Project> findAll() {
        List<Project> out = new ArrayList<>(preset.findAll());
        for (IntakeProjectStore.IntakeProject p : intake.findAll()) {
            if (preset.findById(p.id()).isPresent()) {
                log.warn("接入项目 {} 与预置项目 id 冲突：以预置配置为准", p.id());
                continue;
            }
            out.add(p.toProject());
        }
        return out;
    }

    @Override
    public List<ProjectEnv> findEnvs(String projectId) {
        List<ProjectEnv> fromPreset = preset.findEnvs(projectId);
        if (!fromPreset.isEmpty()) {
            return fromPreset;
        }
        return intake.findEnvs(projectId);
    }

    /** 接入来源的本地目录（LOCAL 项目构建前 fetch 用）；预置项目返回其 repoUrl 判断。 */
    public Optional<String> localPathOf(String projectId) {
        return intake.find(projectId)
                .filter(IntakeProjectStore.IntakeProject::isLocal)
                .map(IntakeProjectStore.IntakeProject::localPath);
    }

    /** 是否为接入项目（可下线）。 */
    public boolean isIntake(String projectId) {
        return intake.find(projectId).isPresent();
    }
}
