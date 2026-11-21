package top.rongx.agent.runtime;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.fs.PathGuard;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 把会话工作树映射成 Harness 的 Docker 沙箱。
 *
 * <p>这里只做“路径 + 容器参数”的翻译，容器生命周期、状态持久化、exec 超时由
 * Harness 的 {@code SandboxLifecycleMiddleware}/{@code SessionSandboxStateStore} 负责。
 * 不复制流水线沙箱的镜像预热、缓存卷和日志采集。
 */
@Component
public class AgentSandboxFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentSandboxFactory.class);

    /** 沙箱描述：filesystem 给 Harness；workspace 给 builder 的 workspace 上下文。 */
    public record Sandbox(DockerFilesystemSpec filesystem, Path workspace) {
    }

    private final ProjectRepoLocator repoLocator;
    private final AgentSandboxProperties props;

    public AgentSandboxFactory(ProjectRepoLocator repoLocator, AgentSandboxProperties props) {
        this.repoLocator = repoLocator;
        this.props = props;
        for (String problem : props.normalize()) {
            log.warn("Agent 沙箱配置：{}", problem);
        }
    }

    public boolean enabled() {
        return props.isEnabled();
    }

    /**
     * 为当前会话构造沙箱；工作树不存在或路径不合法时返回 empty，由调用方保持无 shell 的工具面。
     */
    public Optional<Sandbox> create(ProjectContext ctx) {
        if (!props.isEnabled() || ctx.worktree() == null || ctx.worktree().isBlank()) {
            return Optional.empty();
        }
        Path worktreeRoot = repoLocator.worktreeRoot(ctx.projectId()).orElse(null);
        if (worktreeRoot == null) {
            log.warn("项目 {} 没有工作树根目录，跳过 Agent 沙箱", ctx.projectId());
            return Optional.empty();
        }
        Path worktree = worktreeRoot.resolve(ctx.worktree()).toAbsolutePath().normalize();
        if (!Files.isDirectory(worktree)) {
            log.warn("会话工作树不存在，跳过 Agent 沙箱：{}", worktree);
            return Optional.empty();
        }
        Path repo = repoLocator.repoDir(ctx.projectId()).orElse(null);
        if (repo == null) {
            log.warn("项目 {} 没有可用的仓库目录，跳过 Agent 沙箱", ctx.projectId());
            return Optional.empty();
        }
        Path gitDir = Files.isDirectory(repo.resolve(".git")) ? repo.resolve(".git") : repo;

        BindMountEntry mount = new BindMountEntry();
        mount.setHostPath(posix(worktree));
        mount.setReadOnly(false);
        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot(props.getWorkspaceRoot());
        workspaceSpec.setEntries(Map.<String, WorkspaceEntry>of("", mount));

        DockerFilesystemSpec spec = new DockerFilesystemSpec()
                .image(props.getImage())
                .workspaceRoot(props.getWorkspaceRoot())
                .workspaceSpec(workspaceSpec)
                .network(props.getNetwork())
                .additionalRunArgs(runArgs(gitDir));
        return Optional.of(new Sandbox(spec, worktree));
    }

    /**
     * 容器安全参数。git 目录必须按宿主绝对路径再挂一次：worktree 的 {@code .git} 文件
     * 指向宿主仓库的 git 目录，只挂工作树会让容器内所有 git 命令失败。
     */
    private List<String> runArgs(Path gitDir) {
        List<String> args = new ArrayList<>();
        args.add("--cap-drop=ALL");
        args.add("--security-opt=no-new-privileges");
        args.add("--pids-limit=" + props.getPidsLimit());
        // 不启用 --read-only：Maven/npm 会写 $HOME 缓存；隔离由独立容器 + cap-drop +
        // 只挂工作树保证，容器随会话释放，不共享宿主根文件系统。
        if (props.getMemory() != null && !props.getMemory().isBlank()) {
            args.add("--memory");
            args.add(props.getMemory().trim());
        }
        if (props.getCpus() != null && !props.getCpus().isBlank()) {
            args.add("--cpus");
            args.add(props.getCpus().trim());
        }
        if (props.getUser() != null && !props.getUser().isBlank()) {
            args.add("--user");
            args.add(props.getUser().trim());
        }
        String git = posix(gitDir);
        args.add("-v");
        args.add(git + ":" + git + ":rw");
        return args;
    }

    /** Docker 只接受 POSIX 风格路径，Windows 反斜杠在这里归一。 */
    private static String posix(Path path) {
        return PathGuard.absoluteUnix(path);
    }
}
