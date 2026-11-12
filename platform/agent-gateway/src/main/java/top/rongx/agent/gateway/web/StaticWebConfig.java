package top.rongx.agent.gateway.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 可选的前端静态托管：部署器注入 {@code AGENT_WEB_DIST} 时，同一端口既提供 /api 又提供 SPA 页面。
 *
 * <p>HOST 部署会把本次构建的前端 dist 复制到版本目录并设置该环境变量，
 * 因此「部署成功」不再等于「只有接口、没有页面」。
 */
@Configuration
public class StaticWebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(StaticWebConfig.class);

    private final Path dist;

    public StaticWebConfig(@Value("${AGENT_WEB_DIST:}") String dist) {
        this.dist = dist == null || dist.isBlank()
                ? null : Path.of(dist).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (dist == null || !Files.isRegularFile(dist.resolve("index.html"))) {
            return;
        }
        log.info("前端静态托管已启用：{}", dist);
        registry.addResourceHandler("/**")
                .addResourceLocations(dist.toUri().toString())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location)
                            throws IOException {
                        // 接口 / actuator 不兜底到 index.html，避免把 404 伪装成页面
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
                            return null;
                        }
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // SPA history 路由：未知路径回退到入口页，由前端路由接管
                        return location.createRelative("index.html");
                    }
                });
    }
}
