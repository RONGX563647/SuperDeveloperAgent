package top.rongx.agent.gateway.auth;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
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
            "/api/v1/settings/git-hosts/oauth/github/callback",
            "/api/v1/settings/git-hosts/oauth/gitlab/callback",
            // 浏览器自动请求的图标：后端没有静态资源，放行让它正常 404，
            // 而不是给浏览器的控制台塞一条 401
            "/favicon.ico",
            "/error"
    };

    @Bean
    public WebMvcConfigurer saTokenWebConfigurer() {
        // 真正的校验逻辑：只认 REQUEST 转发，见下面的守卫
        SaInterceptor guard = new SaInterceptor(handle ->
                // 只守接口：静态页面（登录页 / assets / SPA 路由）必须匿名可访问，
                // 否则部署出来的前端会先被 401 拦在门口，连登录框都打不开。
                SaRouter.match("/api/**")
                        .notMatch(WHITELIST)
                        .notMatch("/api/v1/webhooks/**")
                        .check(r -> StpUtil.checkLogin())
        );
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                             Object handler) throws Exception {
                        // ERROR 转发（404/500 错误页）没有 Sa-Token 上下文：
                        // 再走 SaRouter 会抛 SaTokenContextException，把 404 变成 500 并刷堆栈。
                        if (request.getDispatcherType() != DispatcherType.REQUEST) {
                            return true;
                        }
                        return guard.preHandle(request, response, handler);
                    }
                }).addPathPatterns("/**");
            }
        };
    }
}
