package top.rongx.agent.tools.cache;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import top.rongx.agent.domain.project.ProjectContext;

/**
 * 工具执行治理装饰器：给所有已注册工具套一层"语义缓存 + 输出预算"。
 *
 * <ul>
 *   <li><b>只读工具</b>：先查 {@link ToolResultCache}，命中直接返回，省掉一次真实查询；</li>
 *   <li><b>写工具</b>：成功执行后清空同 scope 缓存，避免"刚改完还读到旧值"；</li>
 *   <li><b>所有工具</b>：输出超过 {@code agent.tools.max-output-chars} 时截断，
 *       防止一次 read_file / git_diff 把上下文撑爆。</li>
 * </ul>
 *
 * <p>包装发生在 Toolkit 装配阶段（{@link #install}），模型看到的工具名与 schema 完全不变，
 * 因此不需要改任何工具的写法，也不影响前缀稳定性。
 */
public final class CachingAgentTool implements AgentTool {

    private final AgentTool delegate;
    private final ToolResultCache cache;

    public CachingAgentTool(AgentTool delegate, ToolResultCache cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    /**
     * 原地替换 Toolkit 中的每个工具。必须在所有工具注册完成、且没有开始服务之前调用；
     * 复制出来的 Toolkit（按会话隔离）会带着包装后的工具一起复制。
     */
    public static void install(Toolkit toolkit, ToolResultCache cache) {
        if (!cache.enabled()) {
            return;
        }
        for (String name : List.copyOf(toolkit.getToolNames())) {
            AgentTool tool = toolkit.getTool(name);
            if (tool == null || tool instanceof CachingAgentTool) {
                continue;
            }
            toolkit.removeTool(name);
            toolkit.registerAgentTool(new CachingAgentTool(tool, cache));
        }
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public Map<String, Object> getParameters() {
        return delegate.getParameters();
    }

    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String scope = scopeOf(param);
        if (scope == null) {
            return delegate.callAsync(param).map(this::budget);
        }
        if (!delegate.isReadOnly()) {
            return delegate.callAsync(param)
                    .doOnNext(block -> {
                        if (block != null && block.getState() != ToolResultState.ERROR) {
                            cache.invalidateScope(scope);
                        }
                    })
                    .map(this::budget);
        }
        if (!cache.cacheable(getName())) {
            return delegate.callAsync(param).map(this::budget);
        }
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        return Mono.defer(() -> {
            ToolResultBlock hit = cache.lookup(scope, getName(), input);
            if (hit != null) {
                return Mono.just(withCallIds(hit, param));
            }
            return delegate.callAsync(param)
                    .map(this::budget)
                    .map(block -> {
                        cache.store(scope, getName(), input, block);
                        return block;
                    });
        });
    }

    /** 缓存条目是"某次调用的结果"，复用时必须换成当前调用的 id/name，否则前端对不上。 */
    private static ToolResultBlock withCallIds(ToolResultBlock block, ToolCallParam param) {
        ToolUseBlock call = param.getToolUseBlock();
        if (call == null || call.getId() == null) {
            return block;
        }
        return block.withIdAndName(call.getId(), block.getName());
    }

    /**
     * 输出预算：超长文本保留头尾并标注省略量，让模型知道"不是没有内容，是太长了"。
     * 只处理文本块，图片/结构化块原样保留。
     *
     * <p>保留尾部很重要：构建日志、异常栈的结论都在末尾，只截头会丢掉关键证据。
     */
    private ToolResultBlock budget(ToolResultBlock block) {
        if (block == null) {
            return null;
        }
        List<ContentBlock> output = block.getOutput();
        if (output == null || output.isEmpty()) {
            return block;
        }
        int max = cache.maxOutputChars(delegate.getName());
        boolean changed = false;
        List<ContentBlock> limited = new ArrayList<>(output.size());
        for (ContentBlock part : output) {
            if (part instanceof TextBlock text && text.getText() != null
                    && text.getText().length() > max) {
                String raw = text.getText();
                int head = Math.max(1, max * 6 / 10);
                int tail = Math.max(1, max - head);
                String cut = raw.substring(0, head)
                        + "\n…[tool output truncated: " + (raw.length() - head - tail)
                        + " of " + raw.length() + " chars elided; narrow the query or paginate]\n"
                        + raw.substring(raw.length() - tail);
                limited.add(TextBlock.builder().text(cut).build());
                changed = true;
            } else {
                limited.add(part);
            }
        }
        return changed ? new ToolResultBlock(block.getId(), block.getName(), limited,
                block.getMetadata(), block.getState()) : block;
    }

    /** scope 绑定 project/env/worktree/user：不同项目、不同用户之间绝不互相命中。 */
    private static String scopeOf(ToolCallParam param) {
        RuntimeContext context = param.getRuntimeContext();
        if (context == null) {
            return null;
        }
        ProjectContext project = context.get(ProjectContext.class);
        if (project == null) {
            return null;
        }
        return project.projectId() + '|' + project.env() + '|'
                + (project.worktree() == null ? "" : project.worktree()) + '|' + project.userId();
    }
}
