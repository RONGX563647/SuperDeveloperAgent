package top.rongx.agent.gateway.apidocs;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 文档端点访问控制。
 *
 * <p>{@code SaTokenConfig} 只守 {@code /api/**}，而 springdoc 的规范与 UI 不在该前缀下，
 * 必须单独保护，否则生产环境等于公开了全部接口清单。
 *
 * <ul>
 *   <li>默认：要求登录 + {@code settings:read}（ADMIN / DEVOPS）；</li>
 *   <li>{@code AGENT_API_DOCS_PUBLIC=true}：放开，仅用于本地联调；</li>
 *   <li>ERROR 转发直接放行，避免 404 被二次拦截成 500。</li>
 * </ul>
 */
@Configuration
public class ApiDocsAccessConfig implements WebMvcConfigurer {

    private final boolean publicDocs;

    public ApiDocsAccessConfig(@Value("${agent.api-docs.public:false}") boolean publicDocs) {
        this.publicDocs = publicDocs;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                     Object handler) {
                if (request.getDispatcherType() != DispatcherType.REQUEST || publicDocs) {
                    return true;
                }
                StpUtil.checkLogin();
                StpUtil.checkPermission("settings:read");
                return true;
            }
        }).addPathPatterns(
                "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                "/doc.html", "/swagger-ui.html", "/swagger-ui/**");
    }
}
