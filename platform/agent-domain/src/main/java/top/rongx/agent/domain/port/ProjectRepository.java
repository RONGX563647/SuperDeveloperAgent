package top.rongx.agent.domain.port;

import java.util.List;
import java.util.Optional;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectEnv;

/** 项目仓储（由 infra 实现，domain 只声明契约）。 */
public interface ProjectRepository {

    /** 按 id 查项目。 */
    Optional<Project> findById(String projectId);

    /** 列出全部项目。 */
    List<Project> findAll();

    /** 列出项目下所有环境。 */
    List<ProjectEnv> findEnvs(String projectId);
}
