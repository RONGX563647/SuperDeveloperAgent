package top.rongx.agent.gateway.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.rongx.agent.domain.image.ImageReadiness;
import top.rongx.agent.domain.image.ImageReadinessStore;
import top.rongx.agent.gateway.auth.CurrentUser;

/**
 * 工具链镜像就绪状态（运维只读视图）。
 *
 * <p>镜像拉取失败现在会按 (host, image) 落库并退避，问题是"失败只写进运行日志"——
 * 运维要翻历史日志才知道机器上哪几个镜像坏了、什么时候会自动再试。这里把它们直接摆出来，
 * 前端或 curl 一看就知道该不该人工补镜像。
 *
 * <p>只读、不触发任何拉取：查询接口不能变成"点一下就下载几个 G"。
 */
@RestController
@RequestMapping("/api/v1/settings/sandbox")
public class SandboxImageController {

    private final ObjectProvider<ImageReadinessStore> stores;
    private final CurrentUser currentUser;

    public SandboxImageController(ObjectProvider<ImageReadinessStore> stores,
                                  CurrentUser currentUser) {
        this.stores = stores;
        this.currentUser = currentUser;
    }

    @GetMapping("/images")
    public List<Map<String, Object>> images() {
        currentUser.checkPermission("settings:read");
        ImageReadinessStore store = stores.getIfAvailable();
        if (store == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ImageReadiness readiness : store.listAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("host", readiness.host());
            row.put("image", readiness.image());
            row.put("status", readiness.status().name());
            row.put("source", readiness.source());
            row.put("digest", readiness.digest());
            row.put("attempts", readiness.attempts());
            row.put("lastError", readiness.lastError());
            row.put("updatedAt", readiness.updatedAt());
            // 反过来算成"还要等多少秒"：调用方不用再拿 next_retry_at 和本地时钟相减
            row.put("retryInSeconds", readiness.inBackoff(now)
                    ? Math.max(0, (readiness.nextRetryAt() - now) / 1000) : 0);
            out.add(row);
        }
        return out;
    }
}
