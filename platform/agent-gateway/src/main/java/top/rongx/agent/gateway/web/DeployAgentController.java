package top.rongx.agent.gateway.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.agentscope.core.event.AgentEvent;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.gateway.auth.CurrentUser;
import top.rongx.agent.runtime.ChatService;

/**
 * 发布 Agent 入口：流水线工作台点「发布 Agent」后由这里启动一次自动部署。
 *
 * <p>与通用对话的区别：
 * <ul>
 *   <li>不接收用户自由输入——任务由服务端按项目 / 环境 / 提交 / 环境绑定拼装，
 *       前端只负责传参数，避免把"对话"当成部署入口；</li>
 *   <li>固定使用部署 Agent（工具面只有环境状态 / 触发 / 固化 / 只读日志）；</li>
 *   <li>SSE 实时回传执行过程（环境检查 → 触发 → 跟踪 → 固化），供流水线页面展示。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/deploy")
public class DeployAgentController {

    /** 一次自动部署最长 10 分钟（含构建与健康检查）。 */
    private static final long TIMEOUT_MS = 600_000L;

    private static final String DEFAULT_PIPELINE = "release";

    private static final Logger log = LoggerFactory.getLogger(DeployAgentController.class);

    private final ChatService chatService;
    private final ProjectContextResolver contextResolver;
    private final AgentEventMapper eventMapper;
    private final CurrentUser currentUser;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DeployAgentController(ChatService chatService,
                                 ProjectContextResolver contextResolver,
                                 AgentEventMapper eventMapper,
                                 CurrentUser currentUser) {
        this.chatService = chatService;
        this.contextResolver = contextResolver;
        this.eventMapper = eventMapper;
        this.currentUser = currentUser;
    }

    /**
     * 启动一次自动部署，以 SSE 返回 Agent 执行过程。
     *
     * @param projectId 项目（路径参数）
     * @param request   环境 / 提交 / 流水线 / 已确认的环境绑定
     * @return SSE 发射器
     */
    @PostMapping(value = "/{projectId}/agent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@PathVariable String projectId, @RequestBody DeployAgentRequest request) {
        // 1. 认证：操作人来自 Sa-Token 会话
        String userId = currentUser.requireUserId();
        // 2. 参数校验：自动部署只面向非生产环境，生产必须走「测试证据 + 一次性确认」
        String env = request.env() == null || request.env().isBlank()
                ? "test" : request.env().trim().toLowerCase();
        if ("prod".equals(env)) {
            throw new IllegalArgumentException("生产环境不能由发布 Agent 自动部署：请用「发布到生产」（测试证据 + 一次性确认）");
        }
        String sha = request.sha() == null ? "" : request.sha().trim().toLowerCase();
        if (!sha.matches("[0-9a-f]{7,40}")) {
            throw new IllegalArgumentException("提交 sha 非法（需 7-40 位小写十六进制）");
        }
        String bindingId = request.bindingId() == null ? "" : request.bindingId().trim();
        if (bindingId.isEmpty()) {
            throw new IllegalArgumentException("缺少已确认的环境绑定：请重新点击「发布 Agent」完成确认");
        }
        String pipelineId = request.pipelineId() == null || request.pipelineId().isBlank()
                ? DEFAULT_PIPELINE : request.pipelineId().trim();
        // 3. 服务端解析项目上下文（模型不可伪造项目/环境）
        ProjectContext ctx = contextResolver.resolve(projectId, env, userId);
        // 4. 服务端拼装部署任务（前端不提交自由文本）
        String task = deployTask(ctx, sha, pipelineId, bindingId);
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? "deploy-" + UUID.randomUUID() : request.sessionId();
        log.info("启动发布 Agent：project={} env={} sha={} pipeline={} binding={} user={}",
                ctx.projectId(), ctx.env(), sha, pipelineId, bindingId, userId);
        // 5. SSE 推送（与对话入口同一套事件映射）
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Flux<AgentEvent> events = chatService.stream(ctx, sessionId, task, "deploy");
        executor.submit(() -> {
            try {
                long seq = 0;
                for (AgentEvent event : events.toIterable()) {
                    Map<String, Object> dto = eventMapper.toDto(event);
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(++seq))
                            .name("agent")
                            .data(dto, MediaType.APPLICATION_JSON));
                }
                emitter.complete();
            } catch (IOException clientGone) {
                emitter.complete();
            } catch (RuntimeException error) {
                log.error("发布 Agent 执行异常 project={} env={}", ctx.projectId(), ctx.env(), error);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", safeMessage(error)), MediaType.APPLICATION_JSON));
                } catch (IOException ignored) {
                    // 客户端已断开，无需再写
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 自动部署任务：把"人工确认过的绑定"与目标上下文固化进提示，Agent 不再追问。 */
    private static String deployTask(ProjectContext ctx, String sha, String pipelineId, String bindingId) {
        return """
                操作人在「流水线工作台」点击了「发布 Agent」，请自动完成本次部署，不要向用户追问。
                项目：%s
                环境：%s
                提交：%s
                流水线：%s
                环境绑定：binding_id=%s（已由操作人 %s 确认，直接复用，不要再生成草案）

                执行要求：
                1. 先调 env_summary，确认变量数量与中间件健康；缺变量或中间件异常先说明并停止。
                2. 调 deploy_env_status：已有可用固化脚本且证据成功 → 直接 deploy_run 重放。
                3. 没有可用脚本 → deploy_run，必须带上上面的 binding_id。
                4. 触发后立刻用 wait_run 等待终态（一次最多 90 秒，未结束就再次 wait_run 续等，
                   不要高频轮询 ci_status）；失败用 read_run_log 定位原因，最多再重试两次。
                5. 跑通后必须 deploy_save_recipe 固化脚本，再用 deploy_env_status 确认版本与证据。
                6. 结束时用中文汇报：运行 id、各阶段结果、脚本版本、失败原因（如有）。
                """.formatted(ctx.projectId(), ctx.env(), sha, pipelineId, bindingId, ctx.userId());
    }

    private static String safeMessage(RuntimeException error) {
        String raw = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        raw = raw.replaceAll("\\s+", " ").trim();
        return raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
    }

    /** 自动部署请求体；前端额外带的 message 等字段忽略。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeployAgentRequest(String env, String sha, String pipelineId,
                                     String bindingId, String sessionId) {
    }
}
