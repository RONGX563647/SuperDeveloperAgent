package top.rongx.agent.gateway.apidocs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Locale;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 元信息。
 *
 * <p>规范本身由 springdoc 在运行时扫描控制器生成（唯一事实源），这里只补充
 * 标题、版本、鉴权方式，避免手写一份容易过期的 openapi.yaml。
 *
 * <p>安全方案只登记 {@code bearer}，不设全局 SecurityRequirement：
 * 登录、健康检查、webhook 等白名单接口并不需要登录，统一挂全局要求会与事实不符。
 */
@Configuration
public class ApiDocsConfig {

    @Bean
    public OpenAPI agentPlatformOpenAPI(@Value("${spring.application.name:agent-platform}") String appName) {
        return new OpenAPI()
                .info(new Info()
                        .title("研发运维智能体平台 API")
                        .version("v1")
                        .description("""
                                由 springdoc 从控制器实时生成（/v3/api-docs），不要手工维护第二份规范。
                                鉴权：Authorization: Bearer <Sa-Token>；黑名单外的 /api/** 均需登录。
                                SSE：/api/v1/chat、/api/v1/workspaces/{projectId}/super/stream、
                                /api/v1/workspaces/{projectId}/events 返回 text/event-stream，
                                OpenAPI 只能描述响应为事件流，事件体见 docs/api/overview.md。"""))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("Sa-Token")
                                .description("登录接口返回的 token")));
    }

    /**
     * 兜底摘要与 tag：本仓库大量接口没有 {@code @Operation}，但方法名本身可读。
     * 没有摘要时用方法名（listProjects → "list projects"），tag 用控制器名去掉 Controller，
     * 让 api_search / list_tags 至少能按模块定位；显式 {@code @Operation} 仍然优先。
     */
    @Bean
    public OperationCustomizer apiDocsReadableFallback() {
        return (operation, handlerMethod) -> {
            if (operation.getSummary() == null || operation.getSummary().isBlank()) {
                operation.setSummary(humanize(handlerMethod.getMethod().getName()));
            }
            if (operation.getTags() == null || operation.getTags().isEmpty()) {
                String controller = handlerMethod.getBeanType().getSimpleName()
                        .replaceFirst("Controller$", "");
                operation.setTags(List.of(humanize(controller)));
            }
            return operation;
        };
    }

    private static String humanize(String identifier) {
        String spaced = identifier
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
                .toLowerCase(Locale.ROOT);
        return spaced.isEmpty() ? identifier : spaced;
    }
}
