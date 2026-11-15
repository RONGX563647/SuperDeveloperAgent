package top.rongx.agent.tools.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import top.rongx.agent.domain.project.ProjectContext;

/** 语义缓存：相似命中、scope 隔离、写失效，以及工具输出预算。 */
class ToolResultCacheTest {

    private static final String SCOPE = "p1|dev||u1";

    private ToolResultCache cache(long ttlSeconds, int maxOutputChars) {
        return new ToolResultCache(true, ttlSeconds, 64, 0.9, maxOutputChars, "");
    }

    @Test
    @DisplayName("相同参数命中，且返回的是缓存内容")
    void exactMatchHits() {
        ToolResultCache cache = cache(60, 10_000);
        cache.store(SCOPE, "read_file", Map.of("path", "docs/README.md"), block("hello"));

        ToolResultBlock hit = cache.lookup(SCOPE, "read_file", Map.of("path", "docs/README.md"));

        assertNotNull(hit);
        assertEquals("hello", text(hit));
    }

    @Test
    @DisplayName("表述相近的查询按相似度命中")
    void similarQueryHits() {
        ToolResultCache cache = cache(60, 10_000);
        cache.store(SCOPE, "ci_list", Map.of("query", "检查生产环境部署状态"), block("ok"));

        assertNotNull(cache.lookup(SCOPE, "ci_list", Map.of("query", "检查生产环境部署状态！")));
    }

    @Test
    @DisplayName("不同工具 / 不同 scope 不命中")
    void isolatesToolAndScope() {
        ToolResultCache cache = cache(60, 10_000);
        cache.store(SCOPE, "read_file", Map.of("path", "a.md"), block("a"));

        assertNull(cache.lookup(SCOPE, "grep", Map.of("path", "a.md")));
        assertNull(cache.lookup("p2|prod|wt-1|u2", "read_file", Map.of("path", "a.md")));
    }

    @Test
    @DisplayName("写操作后同一 scope 的缓存全部失效")
    void invalidatesAfterWrite() {
        ToolResultCache cache = cache(60, 10_000);
        cache.store(SCOPE, "read_file", Map.of("path", "a.md"), block("a"));

        cache.invalidateScope(SCOPE);

        assertNull(cache.lookup(SCOPE, "read_file", Map.of("path", "a.md")));
    }

    @Test
    @DisplayName("过期条目不再命中")
    void expiresAfterTtl() throws InterruptedException {
        ToolResultCache cache = cache(1, 10_000);
        cache.store(SCOPE, "read_file", Map.of("path", "a.md"), block("a"));

        Thread.sleep(1_100);

        assertNull(cache.lookup(SCOPE, "read_file", Map.of("path", "a.md")));
    }

    @Test
    @DisplayName("只读工具第二次调用走缓存；超长输出被截断")
    void wrapperCachesAndTruncates() {
        ToolResultCache cache = cache(60, 1_000);
        CountingTool delegate = new CountingTool("x".repeat(5_000));
        CachingAgentTool tool = new CachingAgentTool(delegate, cache);
        ToolCallParam param = param(Map.of("path", "big.log"), "call-1");

        String first = text(tool.callAsync(param).block());
        ToolCallParam second = param(Map.of("path", "big.log"), "call-2");
        String cached = text(tool.callAsync(second).block());

        assertEquals(1, delegate.calls.get());
        assertTrue(first.contains("工具输出已截断"));
        assertEquals(first, cached);
        assertTrue(cache.stats().contains("hits=1"));
    }

    @Test
    @DisplayName("被排除的实时工具不缓存：每次调用都真正执行")
    void excludedToolAlwaysRuns() {
        ToolResultCache cache = new ToolResultCache(true, 60, 64, 0.9, 10_000,
                "conversation_wait");
        CountingTool delegate = new CountingTool("conversation_wait", "RUNNING");
        CachingAgentTool tool = new CachingAgentTool(delegate, cache);

        tool.callAsync(param(Map.of("run_id", "r1"), "c1")).block();
        tool.callAsync(param(Map.of("run_id", "r1"), "c2")).block();

        assertEquals(2, delegate.calls.get());
        assertTrue(cache.stats().contains("hits=0"));
    }

    @Test
    @DisplayName("install 之后工具名、只读标记与参数 schema 不变")
    void installKeepsSchemaAndFlags() {
        ToolResultCache cache = cache(60, 10_000);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SampleTools());

        CachingAgentTool.install(toolkit, cache);

        AgentTool wrapped = toolkit.getTool("sample_read");
        assertNotNull(wrapped);
        assertTrue(wrapped.isReadOnly());
        assertTrue(wrapped.getParameters().toString().contains("path"),
                "参数 schema 不应被包装破坏：" + wrapped.getParameters());
        assertTrue(text(wrapped.callAsync(param(Map.of("path", "a.md"), "call-9"))
                .block()).contains("ok"));
    }

    private static ToolResultBlock block(String text) {
        return ToolResultBlock.text(text).withIdAndName("call-1", "read_file");
    }

    private static ToolCallParam param(Map<String, Object> input, String callId) {
        RuntimeContext context = RuntimeContext.builder()
                .sessionId("s1")
                .userId("u1")
                .put(ProjectContext.class, new ProjectContext("p1", "dev", "u1", "", null))
                .build();
        return ToolCallParam.builder()
                .input(input)
                .toolUseBlock(ToolUseBlock.builder().id(callId).name("read_file")
                        .input(input).build())
                .runtimeContext(context)
                .build();
    }

    private static String text(ToolResultBlock block) {
        StringBuilder sb = new StringBuilder();
        block.getOutput().stream()
                .filter(part -> part instanceof TextBlock)
                .map(part -> ((TextBlock) part).getText())
                .forEach(sb::append);
        return sb.toString();
    }

    /** 只读假工具：统计真实调用次数，用来验证缓存是否省掉了调用。 */
    static final class SampleTools {
        @Tool(name = "sample_read", description = "读取文件", readOnly = true,
                concurrencySafe = true)
        public String read(@ToolParam(name = "path", description = "路径") String path) {
            return "ok";
        }
    }

    private static final class CountingTool implements AgentTool {
        private final String name;
        private final String output;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingTool(String output) {
            this("read_file", output);
        }

        private CountingTool(String name, String output) {
            this.name = name;
            this.output = output;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "fake";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object");
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            calls.incrementAndGet();
            return Mono.just(ToolResultBlock.text(output));
        }
    }
}
