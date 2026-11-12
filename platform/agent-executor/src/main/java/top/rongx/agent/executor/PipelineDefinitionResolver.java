package top.rongx.agent.executor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.port.PipelineDefAuthor;
import top.rongx.agent.domain.port.PipelineDefStore;
import top.rongx.agent.domain.port.ProjectScaffolder;
import top.rongx.agent.domain.port.RecipeStore;
import top.rongx.agent.domain.scaffold.ScaffoldPlan;

/**
 * 流水线定义解析：决定**这次用哪份定义**。
 *
 * <p>优先级（越靠前越优先）：
 * <ol>
 *   <li><b>仓库提交里的 {@code ci/pipeline.yml}</b>——团队想随代码 review 时用，
 *       检出目录里有才算数（不会去读工作区未提交的文件）；</li>
 *   <li><b>平台侧保存的定义</b>（{@code <workspace>/<id>/ci/pipeline.yml}）——默认位置，
 *       由接入时按规则自动生成，也可人工编辑；</li>
 *   <li><b>规则自动生成</b>——按仓库结构（pom.xml / package.json / Dockerfile）生成并保存到平台侧；</li>
 *   <li><b>agent 兜底</b>——规则识别不出技术栈时，请模型按同一契约给候选定义
 *       （只允许引用白名单动作），校验不过就退回规则骨架。</li>
 * </ol>
 *
 * <p>这样"仓库里没提交 ci/"不再是一个错误，而是"用平台定义"的正常分支。
 */
public final class PipelineDefinitionResolver {

    private static final Logger log = LoggerFactory.getLogger(PipelineDefinitionResolver.class);

    /** 定义来源（用于日志、部署受审校验与前端展示）。 */
    public enum Source {
        /** 来自本次检出的提交。 */
        REPO,
        /** 来自该环境已固化的可复现脚本。 */
        RECIPE,
        /** 来自平台侧保存。 */
        PLATFORM,
        /** 本次按规则/agent 新生成。 */
        GENERATED
    }

    /**
     * @param recipeRoot 配方与定义的根目录（其下有 ci/）；stage 的工作目录仍是检出目录
     * @param definition 解析出的定义
     * @param source     来源
     * @param note       给人看的一句话（如何生成、是否用了 agent 兜底）
     */
    public record Resolved(Path recipeRoot, PipelineDefinition definition, Source source, String note) {
    }

    private final PipelineDefStore store;
    private final ProjectScaffolder scaffolder;
    private final PipelineDefAuthor author;
    /** 环境级可复现脚本（可为空：关闭 recipe 链路）。 */
    private final RecipeStore recipes;

    public PipelineDefinitionResolver(PipelineDefStore store, ProjectScaffolder scaffolder,
                                      PipelineDefAuthor author) {
        this(store, scaffolder, author, null);
    }

    public PipelineDefinitionResolver(PipelineDefStore store, ProjectScaffolder scaffolder,
                                      PipelineDefAuthor author, RecipeStore recipes) {
        this.store = store;
        this.scaffolder = scaffolder;
        this.author = author;
        this.recipes = recipes;
    }

    /** 没有环境上下文时的读路径（等价于 dev/test 通用定义）。 */
    public Resolved resolveForRead(String projectId, Path repoRoot) {
        return resolveForRead(projectId, repoRoot, null);
    }

    /**
     * 读路径：用于白名单/触发前，不检出代码。
     *
     * @param repoRoot 仓库工作目录（DIRECT = 用户目录）；可为 null
     * @param env      环境（有 recipe 时优先用它，保证下拉里显示的是实际会跑的脚本名）
     */
    public Resolved resolveForRead(String projectId, Path repoRoot, String env) {
        Optional<Resolved> recipe = fromRecipe(projectId, env);
        if (recipe.isPresent()) {
            return recipe.get();
        }
        if (repoRoot != null) {
            Optional<Resolved> fromRepo = fromRepo(repoRoot);
            if (fromRepo.isPresent()) {
                return fromRepo.get();
            }
        }
        if (store.exists(projectId)) {
            return fromPlatform(projectId);
        }
        return generate(projectId, repoRoot);
    }

    /**
     * 执行路径：**该环境已固化的脚本优先**（它就是"在这个环境上被证明能跑通"的那一份），
     * 其次才是仓库提交里的定义、平台定义、自动生成。
     *
     * <p>生产环境的特殊之处：本环境还没有脚本时，直接用**测试环境的脚本**（晋升），
     * 这与"必须先在测试机跑通"是同一条规则的两种表述。
     */
    public Resolved resolveForRun(String projectId, Path worktree, Path repoRootForGeneration, String env) {
        Optional<Resolved> recipe = fromRecipe(projectId, env);
        if (recipe.isPresent()) {
            return recipe.get();
        }
        Optional<Resolved> fromRepo = fromRepo(worktree);
        if (fromRepo.isPresent()) {
            return fromRepo.get();
        }
        if (store.exists(projectId)) {
            return fromPlatform(projectId);
        }
        return generate(projectId, repoRootForGeneration != null ? repoRootForGeneration : worktree);
    }

    /** 旧签名（无环境）：等价于 env=null。 */
    public Resolved resolveForRun(String projectId, Path worktree, Path repoRootForGeneration) {
        return resolveForRun(projectId, worktree, repoRootForGeneration, null);
    }

    // ---------------------------------------------------------------- 三种来源

    private Optional<Resolved> fromRecipe(String projectId, String env) {
        if (recipes == null || env == null || env.isBlank()) {
            return Optional.empty();
        }
        var current = recipes.current(projectId, env);
        String sourceEnv = env;
        if (current.isEmpty() && "prod".equals(env)) {
            // 生产还没跑过：用测试环境固化过的脚本（晋升的前提已在 EnvironmentPromotion 里校验）
            current = recipes.current(projectId, "test");
            sourceEnv = "test";
        }
        if (current.isEmpty()) {
            return Optional.empty();
        }
        var recipe = current.get();
        Path root = recipes.rootOf(projectId, sourceEnv, recipe.version());
        PipelineDefinition def = PipelineDefinition.load(root);
        String note = "prod".equals(env) && !"prod".equals(sourceEnv)
                ? "晋升：使用测试环境 v" + recipe.version() + " 的脚本（证据 " + recipe.evidenceRunId() + "）"
                : "环境 " + sourceEnv + " 的可复现脚本 v" + recipe.version()
                + "（证据 " + recipe.evidenceRunId() + "）";
        return Optional.of(new Resolved(root, def, Source.RECIPE, note));
    }

    private Optional<Resolved> fromRepo(Path root) {
        if (root == null) {
            return Optional.empty();
        }
        Path file = root.resolve("ci/pipeline.yml");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        PipelineDefinition def = PipelineDefinition.load(root);
        return Optional.of(new Resolved(root, def, Source.REPO, "定义来自本次提交的 ci/pipeline.yml"));
    }

    private Resolved fromPlatform(String projectId) {
        Path root = store.rootOf(projectId);
        PipelineDefinition def = PipelineDefinition.load(root);
        return new Resolved(root, def, Source.PLATFORM, "定义来自平台（" + root + "）");
    }

    private Resolved generate(String projectId, Path repoRoot) {
        Path root = store.rootOf(projectId);

        // 0. 迁移：仓库工作区里已经有一份（可能是历史流程生成的、还没提交）定义时，
        //    直接收进平台，而不是用规则覆盖掉用户可能已经改过的内容。
        Optional<Resolved> adopted = adoptFromWorktree(projectId, repoRoot, root);
        if (adopted.isPresent()) {
            return adopted.get();
        }

        // preferredTarget=null：按仓库能力选目标（无 Dockerfile 的纯 Java 自动降级 HOST，
        // 选不了时定义里会留阻塞项，而不是生成一个必然 E-501 的 docker 目标）
        ScaffoldPlan plan = scaffolder.plan(projectId, repoRoot != null && Files.isDirectory(repoRoot)
                ? repoRoot : root, null);
        String pipelineYaml = fileContent(plan, "ci/pipeline.yml");
        String actionsYaml = fileContent(plan, "ci/actions.yml");
        String note = "按仓库结构自动生成（stack=" + plan.stack() + "）";

        boolean unknownStack = "UNKNOWN".equals(plan.stack());
        // 规则认不出技术栈，或规则给出了不可部署的阻塞项（如无 Dockerfile 却选了 DOCKER）时，
        // 都请模型按同一契约给候选定义；否则生成的定义一看就必然失败。
        boolean blocked = Boolean.FALSE.equals(plan.meta().get("deployable"));
        if ((unknownStack || blocked) && author != null) {
            String reason = unknownStack
                    ? "规则未能识别技术栈（没有任何 pom.xml / package.json / Dockerfile 线索）"
                    : "规则生成的部署目标不可用（" + String.join("；", plan.notes()) + "）";
            Optional<String> authored = author.author(projectId, repoRoot, plan, reason);
            if (authored.isPresent() && isValidDefinition(authored.get())) {
                pipelineYaml = authored.get();
                note = unknownStack
                        ? "规则未识别，已用 agent 生成（请人工确认）"
                        : "规则定义存在阻塞项，已用 agent 生成候选（请人工确认）";
                log.info("项目 {} 流水线定义由 agent 兜底生成（unknownStack={}, blocked={}）",
                        projectId, unknownStack, blocked);
            } else if (authored.isPresent()) {
                log.warn("项目 {} 的 agent 兜底定义不合契约（引用了未知动作/结构非法），退回规则骨架", projectId);
                note = note + "；agent 兜底输出不合契约，已退回骨架";
            } else {
                note = note + "；agent 兜底不可用，已退回骨架";
            }
        }

        store.write(projectId, pipelineYaml, actionsYaml);
        PipelineDefinition def = PipelineDefinition.parse(pipelineYaml, "平台自动生成的 ci/pipeline.yml");
        return new Resolved(root, def, Source.GENERATED, note);
    }

    /**
     * 把仓库工作区里已有的 {@code ci/pipeline.yml} 收进平台（一次性迁移）。
     *
     * <p>场景：早期版本是"生成到仓库、用户自己提交"，升级后这些项目的工作区里往往躺着一份
     * 没提交的定义。直接按规则重生成会丢掉用户改过的内容，所以先认它。
     */
    private Optional<Resolved> adoptFromWorktree(String projectId, Path repoRoot, Path platformRoot) {
        if (repoRoot == null) {
            return Optional.empty();
        }
        Path file = repoRoot.resolve("ci/pipeline.yml");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String yaml = Files.readString(file);
            if (!isValidDefinition(yaml)) {
                return Optional.empty(); // 工作区那份本身坏了：交给规则重新生成
            }
            Path actionsFile = repoRoot.resolve("ci/actions.yml");
            String actions = Files.isRegularFile(actionsFile) ? Files.readString(actionsFile) : null;
            store.write(projectId, yaml, actions);
            PipelineDefinition def = PipelineDefinition.parse(yaml, "平台侧定义（沿用仓库工作区版本）");
            log.info("项目 {} 的流水线定义已从仓库工作区收进平台：{}", projectId, platformRoot.resolve("ci"));
            return Optional.of(new Resolved(platformRoot, def, Source.GENERATED,
                    "沿用仓库工作区里的 ci/pipeline.yml（已收进平台，仓库里那份可删）"));
        } catch (Exception e) {
            log.warn("项目 {} 迁移工作区定义失败：{}", projectId, e.getMessage());
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- 校验

    /**
     * 校验模型给出的候选定义：能被解析、stage 类型合法、动作必须来自白名单。
     *
     * <p>这是"agent 兜底"的安全边界：模型只能决定<b>编排</b>（用哪些动作、什么顺序），
     * 不能引入新命令——命令永远来自 {@code ci/actions.yml} 或平台内置默认。
     */
    static boolean isValidDefinition(String yaml) {
        try {
            PipelineDefinition def = PipelineDefinition.parse(yaml, "agent 兜底定义");
            Set<String> stageTypes = Set.of("action", "deploy", "health", "rollback", "git-checkout");
            for (PipelineDefinition.StageDef stage : def.stages()) {
                if (!stageTypes.contains(stage.type())) {
                    return false;
                }
                if ("action".equals(stage.type())
                        && ActionId.fromWire(stage.action()).isEmpty()) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String fileContent(ScaffoldPlan plan, String path) {
        return plan.files().stream()
                .filter(f -> f.path().equals(path))
                .map(ScaffoldPlan.ScaffoldFile::content)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "脚手架没有生成 " + path + "（项目 " + plan.projectId() + "）"));
    }
}
