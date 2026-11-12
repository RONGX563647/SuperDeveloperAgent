package top.rongx.agent.executor;

import java.nio.file.Path;
import top.rongx.agent.domain.env.EnvProvisioner;
import top.rongx.agent.domain.env.EnvRuntime;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.port.Deployer;
import top.rongx.agent.domain.port.ProjectRepoLocator;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.domain.run.RunRecoveryAdvisor;
import top.rongx.agent.domain.settings.ProjectEnvVarRepository;

/**
 * {@link BuiltInPipelineEngine} 的装配参数。
 *
 * <p>替代原先 13 个逐层加参数的伸缩构造器：必填项随 {@link #builder} 传入，
 * 其余依赖按需链式设置，未设置时沿用引擎既有的退化语义（空 = 不启用该能力）。
 */
public final class PipelineEngineConfig {

    private final ActionResolver recipes;
    private final ActionRunner sandboxed;
    private final ActionRunner host;
    private final Path workspaceRoot;
    private final RunHistoryStore history;
    private final String imagePrefix;
    private final ProjectRepoLocator repoLocator;
    private final Deployer deployers;
    private final PipelineDefinitionResolver resolver;
    private final RecipeStore recipeStore;
    private final ProjectEnvVarRepository envVars;
    private final EnvironmentStore envStore;
    private final EnvRuntime envRuntime;
    private final EnvProvisioner envProvisioner;
    private final RunRecoveryAdvisor recoveryAdvisor;
    private final LocalDevHostDeployer localDevHost;

    private PipelineEngineConfig(Builder builder) {
        this.recipes = builder.recipes;
        this.sandboxed = builder.sandboxed;
        this.host = builder.host;
        this.workspaceRoot = builder.workspaceRoot;
        this.history = builder.history;
        this.imagePrefix = builder.imagePrefix;
        this.repoLocator = builder.repoLocator;
        this.deployers = builder.deployers;
        this.resolver = builder.resolver;
        this.recipeStore = builder.recipeStore;
        this.envVars = builder.envVars;
        this.envStore = builder.envStore;
        this.envRuntime = builder.envRuntime;
        this.envProvisioner = builder.envProvisioner;
        this.recoveryAdvisor = builder.recoveryAdvisor;
        this.localDevHost = builder.localDevHost;
    }

    public static Builder builder(ActionResolver recipes, ActionRunner sandboxed,
                                  ActionRunner host, Path workspaceRoot) {
        return new Builder(recipes, sandboxed, host, workspaceRoot);
    }

    public ActionResolver recipes() {
        return recipes;
    }

    public ActionRunner sandboxed() {
        return sandboxed;
    }

    public ActionRunner host() {
        return host;
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public RunHistoryStore history() {
        return history;
    }

    public String imagePrefix() {
        return imagePrefix;
    }

    public ProjectRepoLocator repoLocator() {
        return repoLocator;
    }

    public Deployer deployers() {
        return deployers;
    }

    public PipelineDefinitionResolver resolver() {
        return resolver;
    }

    public RecipeStore recipeStore() {
        return recipeStore;
    }

    public ProjectEnvVarRepository envVars() {
        return envVars;
    }

    public EnvironmentStore envStore() {
        return envStore;
    }

    public EnvRuntime envRuntime() {
        return envRuntime;
    }

    public EnvProvisioner envProvisioner() {
        return envProvisioner;
    }

    public RunRecoveryAdvisor recoveryAdvisor() {
        return recoveryAdvisor;
    }

    public LocalDevHostDeployer localDevHost() {
        return localDevHost;
    }

    public static final class Builder {

        private final ActionResolver recipes;
        private final ActionRunner sandboxed;
        private final ActionRunner host;
        private final Path workspaceRoot;
        private RunHistoryStore history = RunHistoryStore.disabled();
        private String imagePrefix = "ci";
        private ProjectRepoLocator repoLocator;
        private Deployer deployers = Deployers.builtin();
        private PipelineDefinitionResolver resolver;
        private RecipeStore recipeStore;
        private ProjectEnvVarRepository envVars;
        private EnvironmentStore envStore;
        private EnvRuntime envRuntime;
        private EnvProvisioner envProvisioner;
        private RunRecoveryAdvisor recoveryAdvisor;
        private LocalDevHostDeployer localDevHost;

        private Builder(ActionResolver recipes, ActionRunner sandboxed, ActionRunner host,
                        Path workspaceRoot) {
            this.recipes = recipes;
            this.sandboxed = sandboxed;
            this.host = host;
            this.workspaceRoot = workspaceRoot;
        }

        public Builder history(RunHistoryStore value) {
            this.history = value;
            return this;
        }

        public Builder imagePrefix(String value) {
            this.imagePrefix = value;
            return this;
        }

        public Builder repoLocator(ProjectRepoLocator value) {
            this.repoLocator = value;
            return this;
        }

        public Builder deployers(Deployer value) {
            this.deployers = value;
            return this;
        }

        public Builder resolver(PipelineDefinitionResolver value) {
            this.resolver = value;
            return this;
        }

        public Builder recipeStore(RecipeStore value) {
            this.recipeStore = value;
            return this;
        }

        public Builder envVars(ProjectEnvVarRepository value) {
            this.envVars = value;
            return this;
        }

        public Builder envStore(EnvironmentStore value) {
            this.envStore = value;
            return this;
        }

        public Builder envRuntime(EnvRuntime value) {
            this.envRuntime = value;
            return this;
        }

        public Builder envProvisioner(EnvProvisioner value) {
            this.envProvisioner = value;
            return this;
        }

        public Builder recoveryAdvisor(RunRecoveryAdvisor value) {
            this.recoveryAdvisor = value;
            return this;
        }

        public Builder localDevHost(LocalDevHostDeployer value) {
            this.localDevHost = value;
            return this;
        }

        public PipelineEngineConfig build() {
            return new PipelineEngineConfig(this);
        }
    }
}
