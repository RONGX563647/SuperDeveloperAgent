package top.rongx.agent.gateway.apidocs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.api.AbstractOpenApiResource;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.api.OpenApiResource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * OpenAPI 快照：把运行时生成的规范序列化为 JSON，落盘给 Agent 工具与外部 MCP 使用。
 *
 * <p>为什么不在构建期生成：规范的事实源是控制器字节码，只有应用上下文装配完成后
 * 才能拿到完整的路由/参数/响应。启动完成（{@link ApplicationReadyEvent}）生成一次，
 * 失败只记日志、不阻塞启动；需要最新规范时可调用 {@code /api/v1/api-docs/snapshot?refresh=true}。
 *
 * <p>为什么走 {@link OpenApiResource} 而不是 {@code OpenAPIService.build()}：
 * 后者只构建 info/components，路径由 {@code AbstractOpenApiResource#getOpenApi} 填充。
 * 该方法在 springdoc 中为 protected，这里通过反射复用同一套生成逻辑，避免手写第二份规范。
 *
 * <p>性能：序列化结果在进程内缓存，默认请求直接复用；只有显式 refresh 才重新扫描控制器。
 */
@Component
public class ApiDocsSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ApiDocsSnapshotService.class);
    /** 反射查一次即可：springdoc 的 public 入口不暴露 getOpenApi。 */
    private static final Method GET_OPEN_API = openApiMethod();

    private final ObjectProvider<OpenApiResource> resources;
    private final ObjectProvider<ObjectMapperProvider> mappers;
    private final Path snapshotPath;

    private volatile String cachedJson;
    private volatile long cachedAtMillis;
    private volatile long lastBuildMillis;

    public ApiDocsSnapshotService(ObjectProvider<OpenApiResource> resources,
                                  ObjectProvider<ObjectMapperProvider> mappers,
                                  @Value("${agent.api-docs.snapshot:./data/api/openapi.json}") String snapshot) {
        this.resources = resources;
        this.mappers = mappers;
        this.snapshotPath = Path.of(snapshot).toAbsolutePath().normalize();
    }

    /** 当前规范 JSON；未生成时懒生成一次。 */
    public String json() {
        String json = cachedJson;
        return json != null ? json : refresh();
    }

    /** 重新扫描控制器并刷新内存缓存与磁盘快照。 */
    public synchronized String refresh() {
        OpenApiResource resource = resources.getIfAvailable();
        ObjectMapperProvider provider = mappers.getIfAvailable();
        if (resource == null || provider == null) {
            throw new IllegalStateException("OpenAPI 未启用（springdoc.api-docs.enabled=false）");
        }
        long started = System.nanoTime();
        String json;
        try {
            OpenAPI spec = (OpenAPI) GET_OPEN_API.invoke(resource, Locale.ROOT);
            ObjectMapper mapper = provider.jsonMapper();
            json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
        }
        catch (ReflectiveOperationException | IOException e) {
            throw new IllegalStateException("OpenAPI 生成失败", e);
        }
        cachedJson = json;
        cachedAtMillis = System.currentTimeMillis();
        lastBuildMillis = (System.nanoTime() - started) / 1_000_000;
        write(json);
        return json;
    }

    public long lastBuildMillis() {
        return lastBuildMillis;
    }

    public long cachedAtMillis() {
        return cachedAtMillis;
    }

    public Path snapshotPath() {
        return snapshotPath;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        // 规范扫描约 1~2 秒：放到虚拟线程，避免拖慢"启动完成"后的主线程；
        // 若此时 Agent 立刻调用文档工具，会走懒加载同步生成。
        Thread.ofVirtual().name("openapi-snapshot-warmup").start(() -> {
            try {
                refresh();
                log.info("OpenAPI 快照已生成：{}（{} ms）", snapshotPath, lastBuildMillis);
            }
            catch (RuntimeException e) {
                // 文档不可用不应影响主流程；Agent 工具会退回到 classpath 快照
                log.warn("OpenAPI 快照生成失败（不影响启动）：{}", e.toString());
            }
        });
    }

    private void write(String json) {
        try {
            Path parent = snapshotPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(snapshotPath, json, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            log.warn("OpenAPI 快照写入失败（不影响运行）：{} {}", snapshotPath, e.toString());
        }
    }

    private static Method openApiMethod() {
        try {
            Method method = AbstractOpenApiResource.class.getDeclaredMethod("getOpenApi", Locale.class);
            method.setAccessible(true);
            return method;
        }
        catch (NoSuchMethodException e) {
            throw new IllegalStateException("springdoc 版本不兼容：找不到 getOpenApi(Locale)", e);
        }
    }
}
