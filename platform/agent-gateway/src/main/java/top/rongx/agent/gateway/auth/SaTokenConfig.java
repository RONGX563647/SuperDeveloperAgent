package top.rongx.agent.gateway.auth;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 装配与路由保护。
 *
 * <p>保护策略（fail-closed）：
 * <ul>
 *   <li>登录接口与健康检查放行；</li>
 *   <li>Gitee webhook 用独立共享密钥鉴权，不走登录态（CI 系统没有浏览器会话）；</li>
 *   <li>其余接口一律要求已登录。</li>
 * </ul>
 */
@Configuration
public class SaTokenConfig {

    /** 不需要登录态的路径（顺序匹配）。 */
    private static final String[] WHITELIST = {
            "/api/v1/auth/login",
            "/actuator/health",
            // OAuth 回调是浏览器 302 跳转（无 Authorization 头）；安全性由 state 防 CSRF 保证
            "/api/v1/gitee/oauth/callback",
            "/error"
    };

    @Bean
    public WebMvcConfigurer saTokenWebConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new SaInterceptor(handle ->
                        SaRouter.match("/**")
                                .notMatch(WHITELIST)
                                .notMatch("/api/v1/webhooks/**")
                                .check(r -> StpUtil.checkLogin())
                )).addPathPatterns("/**");
            }
        };
    }
}
