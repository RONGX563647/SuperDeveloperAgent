package top.rongx.agent.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.rongx.agent.domain.action.ActionId;
import top.rongx.agent.domain.action.ActionResult;
import top.rongx.agent.domain.action.ActionSource;
import top.rongx.agent.domain.action.ActionSpec;
import top.rongx.agent.domain.change.ChangePlan;
import top.rongx.agent.domain.change.PlanHasher;
import top.rongx.agent.domain.port.ActionResolver;
import top.rongx.agent.domain.port.ActionRunner;
import top.rongx.agent.domain.port.ChangeStore;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.domain.tool.ToolLevel;
import top.rongx.agent.domain.policy.PolicyPort;
import top.rongx.agent.domain.policy.ConfirmationRequiredException;
import top.rongx.agent.domain.policy.DangerousActionPolicy;

/**
 * 动作编排服务（工具与 HTTP 共用同一套门禁，避免两条路径行为不一致）。
 *
 * <p>术语统一：{@code ActionSpec} 是执行层唯一表示；规约里提到的 {@code PipelineSpec}
 * 仅是文档概念，运行时一个 pipeline stage 就规约为一个 {@code ActionSpec}。
 * 编排层（{@code ci_run}→{@code PipelineEngine}）与动作层（{@code run_action}→本服务）
 * 共用同一套 ActionRunner 与门禁，二者是组合关系，不是两条竞争路径。
 *
 * <p>门禁：
 * <ul>
 *   <li>L2：可直接执行，但**仅非生产环境**；</li>
 *   <li>L3（stack_deploy / stack_rollback）：必须先登记变更 → 人确认拿令牌 → 带令牌 apply，
 *       且令牌绑定的 planHash 必须等于**当前实际配方**的哈希（配方改了就失效）。</li>
 * </ul>
 *
 * <p>fail-closed：宿主进程无隔离时，拒绝不可审计（make/task）或影响生产（L3）的动作，
 * 防止"无 Docker 回退宿主"绕过沙箱不变量 S1-S7。
 */
@Service
public class ActionService {

    private final ActionResolver resolver;
    private final ActionRunner runner;
    private final ChangeStore changes;
    private final PolicyPort policy;
    private final DangerousActionPolicy dangerous;
    private final String workspaceRoot;
    /** 仅供测试：让可用性测试在无 Docker 环境也能验证 L3 令牌闭环。生产恒为 false。 */
    private final boolean allowTestL3;

    public ActionService(ActionResolver resolver,
                         ActionRunner runner,
                         ChangeStore changes,
                         PolicyPort policy,
                         DangerousActionPolicy dangerous,
                         @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
                         @Value("${agent.sandbox.allow-test-l3:false}") boolean allowTestL3) {
        this.resolver = resolver;
        this.runner = runner;
        this.changes = changes;
        this.policy = policy;
        this.dangerous = dangerous;
        this.workspaceRoot = workspaceRoot;
        this.allowTestL3 = allowTestL3;
    }

    /** 项目工作区根目录：{@code <workspaceRoot>/<projectId>}。 */
    public Path workspace(ProjectContext ctx) {
        return Path.of(workspaceRoot).resolve(ctx.projectId()).toAbsolutePath().normalize();
    }

    public List<ActionSpec> list(ProjectContext ctx) {
        return resolver.resolveAll(workspace(ctx));
    }

    /**
     * 解析动作计划；没有配方时抛 {@link IllegalArgumentException}（对应 HTTP 400）。
     */
    public ActionSpec plan(ProjectContext ctx, ActionId action) {
        return resolver.resolve(workspace(ctx), action)
                .orElseThrow(() -> new IllegalArgumentException(
                        "动作 " + action.wireName() + " 没有配方。可在仓库提供 ci/actions.yml（推荐）或 Makefile。"));
    }

    /**
     * 直接执行（仅 L2）。不带参数覆盖。
     */
    public ActionResult run(ProjectContext ctx, ActionId action) {
        return run(ctx, action, Map.of());
    }

    /**
     * 直接执行（仅 L2），允许覆盖动作参数（如 backend_build 的 profile）。
     * 覆盖值会经 {@code allowed} 枚举校验，并注入 steps 的 ${param.x} 占位符。
     */
    public ActionResult run(ProjectContext ctx, ActionId action, Map<String, String> overrides) {
        if (!action.autoExecutable()) {
            throw new IllegalStateException(action.wireName()
                    + " 属 L3（影响生产），不能直接执行。请先 request → confirm → apply。");
        }
        policy.requireNotDenied(ctx, ToolLevel.L2_EXECUTE_ISOLATED, action.wireName());
        Path root = requireWorkspace(ctx);
        ActionSpec spec = plan(ctx, action);
        // 破坏性动作即使在 dev/test 也不能被模型连续直接执行：先登记变更，等人工确认
        String commands = spec.steps().stream()
                .map(argv -> String.join(" ", argv))
                .collect(java.util.stream.Collectors.joining("\n"));
        if (dangerous.requiresConfirmation(action.wireName(), commands)) {
            dangerous.checkFrequency(ctx, action.wireName(), commands);
            ChangePlan change = changes.registerWithHash(ctx.projectId(), ctx.env(),
                    action.wireName(), spec.describe(), specHash(ctx, spec), true);
            throw new ConfirmationRequiredException(change.changeId(), """
                    破坏性动作 %s 需要人工确认，未执行。
                    changeId: %s
                    env: %s
                    next: 请把 changeId 告知用户，在平台确认卡片上确认后再执行；
                          不要重复调用本动作或自行假设已确认。
                    """.formatted(action.wireName(), change.changeId(), ctx.env()));
        }
        ActionSpec bound = bindParams(spec, overrides);
        assertHostSafe(ctx, bound);
        return runner.run(root, bound);
    }

    /**
     * 登记一次 L3 变更（等待人工确认）。返回的 planHash 绑定**当前配方**。
     */
    public ChangePlan requestApproval(ProjectContext ctx, ActionId action) {
        if (action.autoExecutable()) {
            throw new IllegalArgumentException(action.wireName() + " 属 L2，可直接执行，无需审批。");
        }
        policy.requireNotDenied(ctx, ToolLevel.L3_EXECUTE_PROD, action.wireName());
        ActionSpec spec = plan(ctx, action);
        String hash = specHash(ctx, spec);
        return changes.registerWithHash(ctx.projectId(), ctx.env(), action.wireName(),
                spec.describe(), hash, false);
    }

    /**
     * 凭证执行（仅 L3）。调用方必须已校验令牌；这里再校验"配方未被改动"与"宿主安全"。
     *
     * @param confirmedPlanHash 令牌里绑定的 planHash
     */
    public ActionResult apply(ProjectContext ctx, ActionId action, String confirmedPlanHash) {
        return apply(ctx, action, confirmedPlanHash, Map.of());
    }

    /**
     * 凭证执行（仅 L3），允许覆盖动作参数。其余门禁同 {@link #apply}。
     *
     * @param confirmedPlanHash 令牌里绑定的 planHash
     * @param overrides         动作参数覆盖（经 allowed 校验后注入 steps）
     */
    public ActionResult apply(ProjectContext ctx, ActionId action, String confirmedPlanHash,
                              Map<String, String> overrides) {
        if (action.autoExecutable()) {
            throw new IllegalStateException(action.wireName() + " 属 L2，请直接执行。");
        }
        policy.requireNotDenied(ctx, ToolLevel.L3_EXECUTE_PROD, action.wireName());
        Path root = requireWorkspace(ctx);
        ActionSpec spec = plan(ctx, action);
        String actual = specHash(ctx, spec);
        if (!actual.equals(confirmedPlanHash)) {
            throw new IllegalStateException("配方在确认后已被修改，请重新确认（planHash 不匹配）");
        }
        ActionSpec bound = bindParams(spec, overrides);
        assertHostSafe(ctx, bound);
        return runner.run(root, bound);
    }

    /**
     * 凭证执行破坏性 L2（由确认卡片的 apply 入口调用）。
     *
     * <p>与 L3 的区别：动作本身仍是 L2（非生产），但因为它会重置/删除数据，必须先经人工确认。
     * 令牌绑定的 planHash 必须等于当前配方哈希，防止"确认 A、执行 B"。
     */
    public ActionResult applyConfirmed(ProjectContext ctx, ActionId action, String confirmedPlanHash) {
        if (!action.autoExecutable()) {
            throw new IllegalArgumentException(action.wireName() + " 属 L3，请走 L3 的 apply 流程");
        }
        Path root = requireWorkspace(ctx);
        ActionSpec spec = plan(ctx, action);
        String commands = spec.steps().stream()
                .map(argv -> String.join(" ", argv))
                .collect(java.util.stream.Collectors.joining("\n"));
        if (!dangerous.requiresConfirmation(action.wireName(), commands)) {
            throw new IllegalArgumentException(action.wireName() + " 不是破坏性动作，可直接执行");
        }
        policy.requireNotDenied(ctx, ToolLevel.L2_EXECUTE_ISOLATED, action.wireName());
        if (!specHash(ctx, spec).equals(confirmedPlanHash)) {
            throw new IllegalStateException("配方在确认后已被修改，请重新确认（planHash 不匹配）");
        }
        assertHostSafe(ctx, spec);
        return runner.run(root, spec);
    }

    /**
     * 合并参数默认值与运行时覆盖：覆盖 key 必须在声明内，值若声明了 allowed 枚举则必须命中。
     * 合并后把 ${param.x} 占位符替换为实际值。无参数时原样返回。
     */
    private ActionSpec bindParams(ActionSpec spec, Map<String, String> overrides) {
        if (spec.params().isEmpty() && (overrides == null || overrides.isEmpty())) {
            return spec;
        }
        Map<String, String> merged = new LinkedHashMap<>(spec.params());
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            String name = e.getKey();
            if (!spec.allowed().containsKey(name)) {
                throw new IllegalArgumentException("动作 " + spec.action().wireName()
                        + " 不接受参数 " + name + "；可用：" + spec.allowed().keySet());
            }
            List<String> allowed = spec.allowed().get(name);
            if (!allowed.isEmpty() && !allowed.contains(e.getValue())) {
                throw new IllegalArgumentException("参数 " + name + " 取值 " + e.getValue()
                        + " 不在允许范围 " + allowed);
            }
            merged.put(name, e.getValue());
        }
        List<List<String>> steps = spec.steps().stream()
                .map(argv -> argv.stream()
                        .map(t -> {
                            String out = t;
                            for (Map.Entry<String, String> e : merged.entrySet()) {
                                out = out.replace("${param." + e.getKey() + "}", e.getValue());
                            }
                            return out;
                        }).toList())
                .toList();
        return new ActionSpec(spec.action(), steps, spec.produce(), spec.timeout(),
                spec.source(), Map.copyOf(merged), spec.allowed());
    }

    /** 配方哈希：动作 + 来源 + commit + 实际命令，任何一项变化都会改变哈希。 */
    public String specHash(ProjectContext ctx, ActionSpec spec) {
        String commands = spec.steps().stream()
                .map(argv -> String.join(" ", argv))
                .reduce((a, b) -> a + " && " + b)
                .orElse("");
        return PlanHasher.hash(ctx.projectId(), ctx.env(), spec.action().wireName(),
                spec.source().ref(), spec.source().commit(), commands);
    }

    public Optional<ActionSpec> find(ProjectContext ctx, ActionId action) {
        return resolver.resolve(workspace(ctx), action);
    }

    /**
     * fail-closed：宿主进程无隔离时，拒绝不可审计（make/task）或影响生产（L3）的动作。
     * 容器内（isolated=true）放行；宿主仅允许 actions.yml/内置 直写命令的 L2 动作。
     */
    private void assertHostSafe(ProjectContext ctx, ActionSpec spec) {
        // allowTestL3 仅允许在测试环境生效；生产/未知环境即便误配置也走正常校验
        if (runner.isolated() || (allowTestL3 && "test".equals(ctx.env()))) {
            return;
        }
        boolean untrusted = spec.source().kind() == ActionSource.Kind.MAKEFILE
                || spec.source().kind() == ActionSource.Kind.TASKFILE;
        boolean affectsProd = !spec.action().autoExecutable();
        if (untrusted || affectsProd) {
            throw new IllegalStateException("宿主进程无隔离，拒绝执行 " + spec.action().wireName()
                    + "（来源=" + spec.source().ref() + "）：不可审计或影响生产。"
                    + "请启用 Docker 沙箱，或改用 ci/actions.yml 直写命令（L2 且仅非生产）。");
        }
    }

    private Path requireWorkspace(ProjectContext ctx) {
        Path root = workspace(ctx);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("项目工作区不存在：" + root + "（请先接入项目或克隆仓库）");
        }
        return root;
    }
}
